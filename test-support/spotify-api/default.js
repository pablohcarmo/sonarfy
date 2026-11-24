const http = require('node:http');
let catalogTokenMode = 'ok';
let catalogTokensIssued = 0;
let catalogAlbumRequests = 0;

// Respostas sintéticas no formato oficial de GET /v1/me/player/recently-played (sem campos removidos em 2026).
const page1 = {
  items: [
    {
      track: {
        id: 'track1',
        name: 'Track One',
        duration_ms: 215000,
        explicit: false,
        is_local: false,
        type: 'track',
        album: {
          id: 'album1',
          name: 'Album One',
          release_date: '2024-05-10',
          release_date_precision: 'day',
          images: [{ url: 'https://i.scdn.co/image/cover300', height: 300, width: 300 }],
          artists: [{ id: 'artist1', name: 'Artist One' }]
        },
        artists: [
          { id: 'artist1', name: 'Artist One' },
          { id: 'artist2', name: 'Artist Two' }
        ]
      },
      played_at: '2026-11-21T20:10:00.000Z'
    }
  ],
  cursors: { after: '1795291800000', before: '1795291800000' },
  limit: 50
};

const page2 = {
  items: [
    {
      track: {
        id: 'track0',
        name: 'Track Zero',
        duration_ms: 180000,
        explicit: true,
        is_local: false,
        type: 'track',
        album: {
          id: 'album0',
          name: 'Album Zero',
          release_date: '2020',
          release_date_precision: 'year',
          images: [],
          artists: [{ id: 'artist0', name: 'Artist Zero' }]
        },
        artists: [{ id: 'artist0', name: 'Artist Zero' }]
      },
      played_at: '2026-11-21T19:00:00.000Z'
    }
  ],
  cursors: null,
  limit: 50
};

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const reply = (status, body, extraHeaders = {}) => {
    res.writeHead(status, { 'Content-Type': 'application/json', ...extraHeaders });
    res.end(typeof body === 'string' ? body : JSON.stringify(body));
  };

  if (url.pathname.startsWith('/__catalog/')) {
    catalogTokenMode = url.pathname.split('/').pop();
    if (catalogTokenMode === 'reset') { catalogTokenMode = 'ok'; catalogTokensIssued = 0; catalogAlbumRequests = 0; }
    return reply(200, { tokensIssued: catalogTokensIssued, albumRequests: catalogAlbumRequests });
  }
  if (url.pathname === '/api/token' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => { body += chunk; });
    return req.on('end', () => {
      if (req.headers.authorization !== 'Basic ' + Buffer.from('catalog-client:catalog-secret').toString('base64')
          || new URLSearchParams(body).get('grant_type') !== 'client_credentials') return reply(401, {});
      catalogTokensIssued++;
      if (catalogTokenMode === '503') return reply(503, { error: 'sensitive-provider-body' });
      if (catalogTokenMode === '401') return reply(401, { error: 'sensitive-provider-body' });
      return reply(200, { access_token: 'token-ok', token_type: 'Bearer', expires_in: catalogTokenMode === 'invalid' ? 0 : 3600 });
    });
  }
  // Busca sintetica valida parametros e injeta falhas sem acessar o catalogo real.
  if (url.pathname === '/v1/search' && req.method === 'GET') {
    const q = url.searchParams.get('q');
    if (url.searchParams.get('type') !== 'album' || url.searchParams.get('market') !== 'BR') return reply(400, {});
    const status = Number(q);
    if (status >= 400) return reply(status, { error: 'sensitive-provider-body' },
      status === 429 ? { 'Retry-After': '900' } : {});
    if (q === 'malformed') return reply(200, 'invalid-json');
    const album = { id: '4aawyAB9vmqN3uQ7FjRGTy', name: 'Album One', type: 'album',
      artists: [{ id: 'artist1', name: 'Artist One' }], release_date: '2024-05', release_date_precision: 'month' };
    if (q === 'invalid') album.type = 'track';
    const body = { albums: { items: q === 'empty' ? [] : [album], next: 'https://api.spotify.com/ignored' } };
    if (q === 'timeout') return setTimeout(() => reply(200, body), 11500);
    return reply(200, body);
  }
  if (url.pathname.startsWith('/v1/albums/') && req.method === 'GET') {
    catalogAlbumRequests++;
    const requestedStatus = Number(url.pathname.match(/(401|403|404|429|503)$/)?.[1]);
    if (requestedStatus) return reply(requestedStatus, { error: 'sensitive-provider-body' },
      requestedStatus === 429 ? { 'Retry-After': '900' } : {});
    const token = (req.headers.authorization || '').replace('Bearer ', '');
    if (!token.startsWith('token-')) return reply(401, {});
    const status = Number(token.slice(6));
    if (status >= 400) return reply(status, { error: { message: 'sensitive-provider-body' } },
      status === 429 ? { 'Retry-After': '900' } : {});
    if (token === 'token-malformed') return reply(200, 'invalid-json');
    const album = {
      id: url.pathname.split('/').pop(), name: 'Album One', type: 'album', album_type: 'album',
      total_tracks: 70, release_date: '2024-05', release_date_precision: 'month',
      images: [], artists: [{ id: 'artist1', name: 'Artist One' }],
      tracks: { items: [], total: 70, next: 'https://api.spotify.com/ignored' }
    };
    if (token === 'token-invalid') album.total_tracks = -1;
    if (token === 'token-mismatch') album.id = 'wrong';
    if (token === 'token-null') return reply(200, null);
    if (token === 'token-timeout') return setTimeout(() => reply(200, album), 11500);
    return reply(200, album);
  }
  if (url.pathname !== '/v1/me/player/recently-played' || req.method !== 'GET') {
    return reply(501, { error: { status: 501, message: 'Not modeled' } });
  }

  const auth = req.headers.authorization || '';
  if (!auth.startsWith('Bearer ')) {
    return reply(401, { error: { status: 401, message: 'No token provided' } });
  }
  const token = auth.slice('Bearer '.length).trim();

  if (url.searchParams.has('after') && url.searchParams.has('before')) {
    return reply(400, { error: { status: 400, message: 'after and before cannot be combined' } });
  }
  const limit = Number(url.searchParams.get('limit') || '20');
  if (!Number.isInteger(limit) || limit < 1 || limit > 50) {
    return reply(400, { error: { status: 400, message: 'Invalid limit' } });
  }

  if (token === 'token-401') return reply(401, { error: { status: 401, message: 'The access token expired' } });
  if (token === 'token-403') return reply(403, { error: { status: 403, message: 'User not registered in Developer Dashboard' } });
  if (token === 'token-429') return reply(429, { error: { status: 429, message: 'Too many requests' } }, { 'Retry-After': '45' });
  if (token === 'token-429-large') return reply(429, { error: { status: 429, message: 'Too many requests' } }, { 'Retry-After': '900' });
  if (token === 'token-503') return reply(503, { error: { status: 503, message: 'Service unavailable' } });
  if (token === 'token-malformed') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end('invalid-json');
  }
  if (token === 'token-timeout') {
    return setTimeout(() => reply(200, page1), 11500);
  }
  if (token === 'token-partial-429') {
    if (url.searchParams.has('before')) return reply(429, { error: { status: 429 } }, { 'Retry-After': '900' });
    return reply(200, { ...page1, items: Array.from({ length: 50 }, (_, index) => ({
      ...page1.items[0], played_at: new Date(Date.parse(page1.items[0].played_at) - index * 1000).toISOString()
    })) });
  }
  if (token !== 'token-ok') {
    return reply(401, { error: { status: 401, message: 'Invalid access token' } });
  }

  const before = url.searchParams.get('before');
  return reply(200, before ? page2 : page1);
}).listen(process.env.PORT || 4501);
