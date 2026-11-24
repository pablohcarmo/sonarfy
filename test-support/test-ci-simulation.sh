#!/usr/bin/env bash
set -euo pipefail

task_dir=$(mktemp -d)
trap 'rm -rf -- "$task_dir"' EXIT

# Select an available port instead of assuming a fixed port is free on the runner.
simulation_test_port=$(node -e 'const s = require("net").createServer(); s.listen(0, "127.0.0.1", () => { console.log(s.address().port); s.close(); });')
export SIMULATION_TEST_PORT=$simulation_test_port

# The same port must be reusable after both successful and failed test commands.
assert_port_available() {
  node -e 'const s = require("net").createServer(); s.on("error", e => { console.error(e.message); process.exit(1); }); s.listen(Number(process.env.SIMULATION_TEST_PORT), "127.0.0.1", () => s.close());'
}

# Exercise the KILL fallback with an HTTP worker that deliberately ignores TERM.
mkdir "$task_dir/bin"
cat > "$task_dir/bin/postman" <<'WORKER'
#!/usr/bin/env node
require('child_process').spawn(process.execPath, ['-e', `
  process.on('SIGTERM', () => {});
  require('http').createServer((req, res) => res.end('{}')).listen(Number(process.env.SIMULATION_TEST_PORT), '127.0.0.1');
`], { stdio: 'ignore' });
WORKER
chmod +x "$task_dir/bin/postman"
original_path=$PATH
for mode in postman resistant-worker; do
  export PATH=$original_path
  if [ "$mode" = resistant-worker ]; then export PATH="$task_dir/bin:$PATH"; fi
  for expected in 0 7 0; do
    assert_port_available
    actual=0
    bash -e -c '
      source test-support/ci-simulation.sh
      start_simulation test-support/spotify-recently-played.sim.yaml "$SIMULATION_TEST_PORT" "$1"
      ready=false
      for attempt in {1..60}; do
        if ! kill -0 "$simulation_pid" 2>/dev/null; then
          cat "$simulation_log"
          exit 1
        fi
        status=$(curl --silent --output /dev/null --write-out "%{http_code}" --max-time 1 \
          -H "Authorization: Bearer token-ok" \
          "http://127.0.0.1:$SIMULATION_TEST_PORT/spotify-api/v1/me/player/recently-played?limit=1" || true)
        if [ "$status" = 200 ]; then ready=true; break; fi
        sleep 1
      done
      if [ "$ready" != true ]; then cat "$simulation_log"; exit 1; fi
      exit "$2"
    ' -- "$task_dir/simulation-$expected.log" "$expected" || actual=$?
    if [ "$actual" != "$expected" ]; then
      cat "$task_dir/simulation-$expected.log"
      echo "Expected exit $expected, received $actual" >&2
      exit 1
    fi
    assert_port_available
    echo "Simulation stopped and port released; exit status $expected preserved"
  done
done
