const http = require('node:http');
// Respostas sintéticas no formato dos endpoints oficiais searchJSON e getJSON.
const places = [
  { geonameId: 1, name: 'Campinas', adminName1: 'São Paulo', countryName: 'Brasil', fcl: 'P' },
  { geonameId: 2, name: 'Santa Maria', adminName1: 'Rio Grande do Sul', countryName: 'Brasil', fcl: 'P' },
  { geonameId: 3, name: 'Santa Maria', adminName1: 'California', countryName: 'Estados Unidos', fcl: 'P' }
];
http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const reply = (status, body) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };
  if (!['/searchJSON', '/getJSON'].includes(url.pathname) || req.method !== 'GET') return reply(501, {});
  if (url.searchParams.get('username') !== 'test-only' || url.searchParams.get('lang') !== 'pt') return reply(400, {});
  if (url.pathname === '/searchJSON') {
    if (url.searchParams.get('featureClass') !== 'P' || url.searchParams.get('maxRows') !== '10'
        || url.searchParams.get('orderby') !== 'population') return reply(400, {});
    const query = url.searchParams.get('name_startsWith');
    if (query === 'quota') return reply(200, { status: { value: 19, message: 'hourly limit' } });
    if (query === 'malformed') return reply(200, { unexpected: true });
    return reply(200, { geonames: places.filter(p => p.name.toLowerCase().startsWith((query || '').toLowerCase())) });
  }
  const id = Number(url.searchParams.get('geonameId'));
  if (id === 9001) return reply(200, { status: { value: 11, message: 'record does not exist' } });
  if (id === 9002) return reply(200, { ...places[0], geonameId: id, fcl: 'A' });
  if (id === 9003) return reply(503, {});
  if (id === 9004) return reply(200, places[0]);
  if (id === 9005) { res.writeHead(200, { 'Content-Type': 'application/json' }); return res.end('invalid-json'); }
  if (id === 9006) return setTimeout(() => reply(200, places[0]), 3500);
  return reply(200, places.find(p => p.geonameId === id) || { status: { value: 11, message: 'record does not exist' } });
}).listen(process.env.PORT || 4500);
