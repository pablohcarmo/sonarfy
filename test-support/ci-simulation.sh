#!/usr/bin/env bash

# Source this helper before starting one simulation in a CI step.
start_simulation() {
  local manifest=$1 port=$2
  simulation_log=$3
  simulation_pid_file=$(mktemp)
  # Record the actual session leader even if setsid forks its launcher.
  setsid bash -c 'echo "$$" > "$1"; shift; exec "$@"' -- "$simulation_pid_file" \
    postman simulation run "$manifest" --port "$port" --no-history --output ndjson > "$simulation_log" 2>&1 &
  simulation_launcher_pid=$!
  trap stop_simulation EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  for attempt in {1..50}; do
    if [ -s "$simulation_pid_file" ]; then
      simulation_pid=$(cat "$simulation_pid_file")
      return 0
    fi
    if ! kill -0 "$simulation_launcher_pid" 2>/dev/null; then
      cat "$simulation_log"
      return 1
    fi
    sleep 0.1
  done
  echo "Simulation did not report its process group" >&2
  return 1
}

# A CLI parent can exit on TERM while its HTTP workers keep the port bound.
stop_simulation() {
  local result=$?
  trap - EXIT INT TERM
  if [ -s "$simulation_pid_file" ]; then
    local group_id
    group_id=$(cat "$simulation_pid_file")
    if [[ "$group_id" =~ ^[1-9][0-9]*$ ]]; then
      kill -TERM -- -"$group_id" 2>/dev/null || true
      sleep 1
      kill -KILL -- -"$group_id" 2>/dev/null || true
    fi
  fi
  kill -TERM "$simulation_launcher_pid" 2>/dev/null || true
  wait "$simulation_launcher_pid" 2>/dev/null || true
  rm -f -- "$simulation_pid_file"
  exit "$result"
}
