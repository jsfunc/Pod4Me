// Public metadata only. Audio and artwork are loaded directly by the browser.
// No accounts, cookies, bindings requiring storage, or credentials are needed.
const FEED_LIMIT = 32 * 1024 * 1024;
const DIRECTORY_LIMIT = 2 * 1024 * 1024;
const TIMEOUT_MS = 30_000;
const REDIRECT_LIMIT = 5;
const PREFIX_LIMIT = 64 * 1024;
const DIRECTORY_HOSTS = new Set(['itunes.apple.com']);
const SECURITY_HEADERS = {
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
  'Cross-Origin-Resource-Policy': 'same-origin',
  'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'; sandbox",
};

class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function failure(status, message, extra = {}) {
  return new Response(JSON.stringify({error: message}), {
    status,
    headers: {...SECURITY_HEADERS, 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...extra},
  });
}

// Reject literal IPs (including URL-normalized hexadecimal/integer IPv4), local
// names, credentials, and nonstandard ports on every redirect. This is URL-level
// validation, not DNS pinning; publisher DNS and Cloudflare's network controls
// still apply. Only a bounded RSS/Atom response can leave this relay.
function publicUrl(value, ownHost) {
  if (typeof value !== 'string' || value.length > 8192 || /[\u0000-\u0020\u007f\\]/u.test(value)) {
    throw new ApiError(400, 'Enter a valid public HTTP or HTTPS feed URL.');
  }
  let url;
  try { url = new URL(value); } catch { throw new ApiError(400, 'Enter a valid public HTTP or HTTPS feed URL.'); }
  const host = url.hostname.toLowerCase().replace(/\.$/, '');
  const labels = host.split('.');
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.port ||
      host === ownHost || !host.includes('.') || host.includes(':') || /^[\d.]+$/.test(host) ||
      host.length > 253 || labels.some(label => !/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(label)) ||
      /(?:^|\.)(?:localhost|local|localdomain|internal|intranet|home|lan|test|invalid|example|onion|arpa)$/.test(host)) {
    throw new ApiError(400, 'Only public HTTP or HTTPS hosts on their standard ports are supported.');
  }
  url.hostname = host;
  url.hash = '';
  return url;
}

function parameters(url, names) {
  for (const key of url.searchParams.keys()) {
    if (!names.includes(key) || url.searchParams.getAll(key).length !== 1) {
      throw new ApiError(400, 'This request has unsupported or repeated parameters.');
    }
  }
}

function sameOrigin(request, url) {
  const origin = request.headers.get('Origin');
  const site = request.headers.get('Sec-Fetch-Site');
  if ((origin !== null && origin !== url.origin) ||
      (site !== null && !['same-origin', 'none'].includes(site))) {
    throw new ApiError(403, 'Open Pod4Me to use its podcast browser.');
  }
}

async function cancel(response) {
  try { await response.body?.cancel(); } catch { /* Already closed or aborted. */ }
}

async function upstream(startUrl, {signal, limit, directory = false, ownHost}) {
  let url = publicUrl(startUrl, ownHost);
  for (let redirects = 0; ; redirects++) {
    if (directory && !DIRECTORY_HOSTS.has(url.hostname)) {
      throw new ApiError(502, 'The podcast directory redirected to an unsupported address.');
    }
    // A fresh header set deliberately excludes the caller's Cookie,
    // Authorization, Referer, Origin, and all conditional/range headers.
    const response = await fetch(url.href, {
      method: 'GET', redirect: 'manual', credentials: 'omit', signal,
      headers: {Accept: directory ? 'application/json' : 'application/rss+xml, application/atom+xml, application/xml, text/xml, text/plain;q=0.8',
        'User-Agent': 'Pod4Me-Web/1.0 (public podcast metadata)'},
    });
    if ([301, 302, 303, 307, 308].includes(response.status)) {
      const location = response.headers.get('Location');
      await cancel(response);
      if (!location || redirects >= REDIRECT_LIMIT) {
        throw new ApiError(502, 'The publisher returned too many redirects or an incomplete redirect.');
      }
      let destination;
      try { destination = new URL(location, url).href; } catch { throw new ApiError(502, 'The publisher returned an invalid redirect.'); }
      url = publicUrl(destination, ownHost);
      continue;
    }
    if (!response.ok || response.status === 204 || response.status === 206) {
      await cancel(response);
      throw new ApiError(502, `The ${directory ? 'podcast directory' : 'publisher'} could not supply this feed (HTTP ${response.status}). Try again later.`);
    }
    const type = (response.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase();
    const allowed = directory ? (type === 'application/json' || type === 'text/javascript' || type === 'text/json') :
      (!type || ['application/rss+xml', 'application/atom+xml', 'application/xml', 'text/xml', 'text/plain', 'application/octet-stream'].includes(type));
    if (!allowed) {
      await cancel(response);
      throw new ApiError(415, directory ? 'The podcast directory returned an unexpected format.' : 'This address does not return an RSS or Atom feed. Use the podcast’s feed URL, not its audio or website URL.');
    }
    const length = Number(response.headers.get('Content-Length'));
    if (Number.isFinite(length) && length > limit) {
      await cancel(response);
      throw new ApiError(413, directory ? 'The directory response is too large.' : 'This feed exceeds the 32 MiB web limit.');
    }
    if (!response.body) throw new ApiError(502, 'The publisher returned an empty response.');
    const reader = response.body.getReader();
    const chunks = [];
    let size = 0;
    try {
      while (true) {
        const {done, value} = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > limit) {
          await reader.cancel();
          throw new ApiError(413, directory ? 'The directory response is too large.' : 'This feed exceeds the 32 MiB web limit.');
        }
        chunks.push(value);
      }
    } finally { reader.releaseLock(); }
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    return {bytes, url: url.href, contentType: response.headers.get('Content-Type')};
  }
}

function verifyFeed(bytes) {
  // Inspect only a bounded preamble. Parsing and episode extraction happen in
  // the browser, away from the Free Worker's short CPU allowance. XML entities
  // are never expanded here; DTD declarations before the root are rejected.
  let prefix = new TextDecoder().decode(bytes.subarray(0, PREFIX_LIMIT)).replace(/^\uFEFF/, '').trimStart();
  for (let i = 0; i < 256; i++) {
    if (prefix.startsWith('<?')) {
      const end = prefix.indexOf('?>');
      if (end < 0) break;
      prefix = prefix.slice(end + 2).trimStart();
    } else if (prefix.startsWith('<!--')) {
      const end = prefix.indexOf('-->');
      if (end < 0) break;
      prefix = prefix.slice(end + 3).trimStart();
    } else break;
  }
  if (/^<!DOCTYPE\b/i.test(prefix)) throw new ApiError(415, 'Feeds containing XML document type declarations are not supported.');
  if (!/^<(?:[A-Za-z_][\w.-]*:)?(?:rss|feed)(?:\s|\/?>)/.test(prefix)) {
    throw new ApiError(415, 'This address does not contain a supported RSS or Atom feed.');
  }
}

function jsonDirectory(bytes) {
  try { return JSON.parse(new TextDecoder().decode(bytes)); }
  catch { throw new ApiError(502, 'The podcast directory returned unreadable data. Try again later.'); }
}

function shortText(value, limit) {
  return typeof value === 'string' ? value.replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, limit) : '';
}

function optionalUrl(value) {
  try { return publicUrl(value).href; } catch { return ''; }
}

function directoryRows(data) {
  const results = [];
  const seen = new Set();
  for (const item of (Array.isArray(data?.results) ? data.results : []).slice(0, 200)) {
    if (!item || typeof item !== 'object') continue;
    const feedUrl = optionalUrl(item.feedUrl);
    const name = shortText(item.collectionName || item.trackName, 300);
    if (!feedUrl || !name || seen.has(feedUrl)) continue;
    seen.add(feedUrl);
    const collectionUrl = optionalUrl(item.collectionViewUrl || item.trackViewUrl);
    results.push({
      id: String(item.collectionId || item.trackId || '').replace(/[^0-9]/g, '').slice(0, 30),
      name, artist: shortText(item.artistName, 300), feedUrl,
      artworkUrl: optionalUrl(item.artworkUrl600 || item.artworkUrl100),
      collectionUrl: collectionUrl && ['podcasts.apple.com', 'itunes.apple.com'].includes(new URL(collectionUrl).hostname) ? collectionUrl : '',
      genres: Array.isArray(item.genres) ? item.genres.slice(0, 12).map(genre => shortText(genre, 100)).filter(Boolean) : [],
    });
    if (results.length === 40) break;
  }
  return results;
}

function waitForDirectory(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(signal.reason); return; }
    const abort = () => { clearTimeout(timer); reject(signal.reason); };
    const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve(); }, ms);
    signal.addEventListener('abort', abort, {once: true});
  });
}

async function api(request, env, ctx) {
  const url = new URL(request.url);
  sameOrigin(request, url);
  if (request.method !== 'GET') return failure(405, 'Only GET requests are supported.', {Allow: 'GET'});
  let source;
  let country;
  let term;
  if (url.pathname === '/api/feed') {
    parameters(url, ['url']);
    source = publicUrl(url.searchParams.get('url'), url.hostname).href;
  } else if (url.pathname === '/api/search' || url.pathname === '/api/chart') {
    parameters(url, url.pathname === '/api/search' ? ['term', 'country'] : ['country']);
    country = (url.searchParams.get('country') || 'fr').toLowerCase();
    if (!/^[a-z]{2}$/.test(country)) throw new ApiError(400, 'Choose a two-letter country code.');
    if (url.pathname === '/api/search') {
      term = (url.searchParams.get('term') || '').trim();
      if (!term || term.length > 150 || /[\u0000-\u001f\u007f]/.test(term)) throw new ApiError(400, 'Enter a search between 1 and 150 characters.');
    }
  } else return failure(404, 'This podcast API endpoint does not exist.');

  const cacheUrl = new URL(url.pathname, url.origin);
  if (source) cacheUrl.searchParams.set('url', source);
  else {
    cacheUrl.searchParams.set('country', country);
    if (term) cacheUrl.searchParams.set('term', term);
  }
  const cacheKey = new Request(cacheUrl.href);
  const cache = globalThis.caches?.default;
  if (cache) {
    try {
      const cached = await cache.match(cacheKey);
      if (cached) return cached;
    } catch { /* Cache is optional, including during local development. */ }
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new Error('Podcast request timed out.')), TIMEOUT_MS);
  const options = {signal: controller.signal, ownHost: url.hostname};
  let response;
  try {
    if (source) {
      const result = await upstream(source, {...options, limit: FEED_LIMIT});
      verifyFeed(result.bytes);
      // Preserve declared text encoding, but always give the response an XML
      // media type (plain/octet-stream publisher responses are accepted above).
      const charset = /;\s*charset\s*=\s*(["']?)([a-zA-Z0-9_-]+)\1/i.exec(result.contentType || '')?.[2];
      response = new Response(result.bytes, {headers: {...SECURITY_HEADERS,
        'Content-Type': `application/xml${charset ? `; charset=${charset}` : ''}`,
        'X-Feed-URL': result.url, 'Cache-Control': 'public, max-age=300'}});
    } else {
      options.limit = DIRECTORY_LIMIT;
      options.directory = true;
      let data;
      if (term) {
        const search = new URL('https://itunes.apple.com/search');
        search.search = new URLSearchParams({term, country, media: 'podcast', entity: 'podcast', limit: '40'}).toString();
        data = jsonDirectory((await upstream(search.href, options)).bytes);
      } else {
        const started = Date.now();
        const chart = jsonDirectory((await upstream(`https://itunes.apple.com/${country}/rss/toppodcasts/limit=40/json`, options)).bytes);
        const entries = Array.isArray(chart?.feed?.entry) ? chart.feed.entry : [];
        const ids = [...new Set(entries.slice(0, 40).map(entry => String(entry?.id?.attributes?.['im:id'] || '')).filter(id => /^\d{1,20}$/.test(id)))];
        if (!ids.length) data = {results: []};
        else {
          // Chart metadata omits RSS URLs. Resolve IDs with one bounded lookup,
          // preserving the directory's 3.1-second spacing within this request.
          await waitForDirectory(Math.max(0, 3100 - (Date.now() - started)), controller.signal);
          const lookup = new URL('https://itunes.apple.com/lookup');
          lookup.search = new URLSearchParams({id: ids.join(','), country, entity: 'podcast', limit: '40'}).toString();
          data = jsonDirectory((await upstream(lookup.href, options)).bytes);
          if (Array.isArray(data?.results)) data.results.sort((a, b) => ids.indexOf(String(a?.collectionId)) - ids.indexOf(String(b?.collectionId)));
        }
      }
      response = new Response(JSON.stringify({results: directoryRows(data)}), {headers: {...SECURITY_HEADERS,
        'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'public, max-age=600'}});
    }
  } catch (error) {
    if (controller.signal.aborted) throw new ApiError(504, 'The publisher took too long to respond. Please try again.');
    if (error instanceof ApiError) throw error;
    throw new ApiError(502, 'Could not reach the podcast publisher. Please try again later.');
  } finally { clearTimeout(timer); }

  if (cache) {
    try {
      const storing = Promise.resolve(cache.put(cacheKey, response.clone())).catch(() => {});
      if (ctx?.waitUntil) ctx.waitUntil(storing);
      else await storing;
    } catch { /* A cache failure must not hide a successfully retrieved feed. */ }
  }
  return response;
}

export default {
  async fetch(request, env = {}, ctx = {}) {
    const path = new URL(request.url).pathname;
    if (path !== '/api' && !path.startsWith('/api/')) {
      if (env.ASSETS?.fetch) return env.ASSETS.fetch(request);
      return failure(404, 'The Pod4Me website assets are not configured.');
    }
    try { return await api(request, env, ctx); }
    catch (error) {
      return failure(error instanceof ApiError ? error.status : 502,
        error instanceof ApiError ? error.message : 'The podcast request failed. Please try again.');
    }
  },
};
