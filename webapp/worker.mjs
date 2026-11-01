import {decodeXml, xmlPreamble} from './public/app/xml.mjs';

// Public metadata only. Audio and artwork are loaded directly by the browser.
// No accounts, cookies, bindings requiring storage, or credentials are needed.
const FEED_LIMIT = 32 * 1024 * 1024;
const TIMEOUT_MS = 30_000;
const REDIRECT_LIMIT = 5;
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

async function upstream(startUrl, {signal, limit, ownHost}) {
  let url = publicUrl(startUrl, ownHost);
  for (let redirects = 0; ; redirects++) {
    // A fresh header set deliberately excludes the caller's Cookie,
    // Authorization, Referer, Origin, and all conditional/range headers.
    const response = await fetch(url.href, {
      method: 'GET', redirect: 'manual', credentials: 'omit', signal,
      headers: {Accept: 'application/rss+xml, application/atom+xml, application/xml, text/xml, text/plain;q=0.8',
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
      throw new ApiError(502, `The publisher could not supply this feed (HTTP ${response.status}). Try again later.`);
    }
    const type = (response.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase();
    const allowed = (!type || ['application/rss+xml', 'application/atom+xml', 'application/xml', 'text/xml', 'text/plain', 'application/octet-stream'].includes(type));
    if (!allowed) {
      await cancel(response);
      throw new ApiError(415, 'This address does not return an RSS or Atom feed. Use the podcast’s feed URL, not its audio or website URL.');
    }
    const length = Number(response.headers.get('Content-Length'));
    if (Number.isFinite(length) && length > limit) {
      await cancel(response);
      throw new ApiError(413, 'This feed exceeds the 32 MiB web limit.');
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
          throw new ApiError(413, 'This feed exceeds the 32 MiB web limit.');
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

function verifyFeed(bytes, contentType) {
  try {
    const prefix = xmlPreamble(decodeXml(bytes.subarray(0, 65536), contentType, true));
    if (!/^<(?:[A-Za-z_][\w.-]*:)?(?:rss|feed)(?:\s|\/?>)/.test(prefix)) {
      throw new Error('This address does not contain a supported RSS or Atom feed.');
    }
  } catch (error) { throw new ApiError(415, error.message); }
}

async function api(request, env, ctx) {
  const url = new URL(request.url);
  sameOrigin(request, url);
  if (request.method !== 'GET') return failure(405, 'Only GET requests are supported.', {Allow: 'GET'});
  if (url.pathname !== '/api/feed') return failure(404, 'This podcast API endpoint does not exist.');
  parameters(url, ['url']);
  const source = publicUrl(url.searchParams.get('url'), url.hostname).href;
  const cacheUrl = new URL(url.pathname, url.origin);
  cacheUrl.searchParams.set('url', source);
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
    const result = await upstream(source, {...options, limit: FEED_LIMIT});
    verifyFeed(result.bytes, result.contentType);
    // Preserve declared text encoding, but always give the response an XML
    // media type (plain/octet-stream publisher responses are accepted above).
    const charset = /;\s*charset\s*=\s*(["']?)([a-zA-Z0-9_-]+)\1/i.exec(result.contentType || '')?.[2];
    response = new Response(result.bytes, {headers: {...SECURITY_HEADERS,
      'Content-Type': `application/xml${charset ? `; charset=${charset}` : ''}`,
      'X-Feed-URL': result.url, 'Cache-Control': 'public, max-age=300'}});
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
