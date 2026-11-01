import test, {afterEach} from 'node:test';
import assert from 'node:assert/strict';
import worker from '../worker.mjs';

const ORIGIN = 'https://pod4me.aurelien-garivier.workers.dev';
const FEED = 'https://feeds.publisher.org/podcast.xml';
const RSS = '<?xml version="1.0"?><rss version="2.0"><channel><title>Example</title></channel></rss>';
const realFetch = globalThis.fetch;
const realCaches = globalThis.caches;
const realSetTimeout = globalThis.setTimeout;

afterEach(() => {
  globalThis.fetch = realFetch;
  globalThis.setTimeout = realSetTimeout;
  if (realCaches === undefined) delete globalThis.caches;
  else globalThis.caches = realCaches;
});

function call(path = `/api/feed?url=${encodeURIComponent(FEED)}`, init = {}, env = {}, ctx = {}) {
  return worker.fetch(new Request(`${ORIGIN}${path}`, init), env, ctx);
}

function xml(body = RSS, extra = {}) {
  return new Response(body, {headers: {'Content-Type': 'application/rss+xml', ...extra}});
}

function json(data) {
  return new Response(JSON.stringify(data), {headers: {'Content-Type': 'application/json'}});
}

function neverFetch() {
  globalThis.fetch = () => { assert.fail('Rejected requests must not access the network'); };
}

test('serves static assets through their binding without intercepting Android association or sharing', async () => {
  neverFetch();
  for (const path of ['/', '/app/', '/app/app.js', '/.well-known/assetlinks.json']) {
    let received;
    const response = await call(path, {}, {ASSETS: {fetch(request) { received = request.url; return new Response('static'); }}});
    assert.equal(await response.text(), 'static');
    assert.equal(received, `${ORIGIN}${path}`);
  }
});

test('missing assets and unknown API endpoints fail clearly', async () => {
  neverFetch();
  assert.equal((await call('/app/')).status, 404);
  const response = await call('/api/audio?url=https://feeds.publisher.org/audio.mp3');
  assert.equal(response.status, 404);
  assert.match((await response.json()).error, /endpoint/);
});

test('rejects foreign browser origins and same-site subdomains before contacting publishers', async () => {
  neverFetch();
  for (const headers of [{Origin: 'https://elsewhere.org'}, {Origin: 'null'}, {'Sec-Fetch-Site': 'cross-site'}, {'Sec-Fetch-Site': 'same-site'}]) {
    const response = await call(undefined, {headers});
    assert.equal(response.status, 403);
    assert.equal(response.headers.get('Access-Control-Allow-Origin'), null);
    assert.equal(response.headers.get('Cache-Control'), 'no-store');
  }
});

test('allows GET only and never offers wildcard CORS', async () => {
  neverFetch();
  for (const method of ['POST', 'HEAD', 'OPTIONS', 'PUT', 'DELETE']) {
    const response = await call(undefined, {method});
    assert.equal(response.status, 405);
    assert.equal(response.headers.get('Allow'), 'GET');
    assert.equal(response.headers.get('Access-Control-Allow-Origin'), null);
  }
});

test('validates unsafe, malformed, local, literal-IP, credentialed and nonstandard-port targets', async () => {
  neverFetch();
  const urls = [
    '', 'not a url', 'file:///etc/passwd', 'ftp://feeds.publisher.org/rss', 'data:text/xml,<rss/>',
    'https://user:secret@feeds.publisher.org/rss', 'https://feeds.publisher.org:444/rss', 'https://feeds.publisher.org:80/rss',
    'http://127.0.0.1/rss', 'http://2130706433/rss', 'http://0x7f000001/rss', 'http://0177.0.0.1/rss',
    'http://169.254.169.254/latest/meta-data/', 'https://[::1]/rss', 'https://[::ffff:127.0.0.1]/rss',
    'http://localhost/rss', 'http://localhost./rss', 'https://foo.local/rss', 'https://foo.internal/rss',
    'http://intranet/rss', 'http://host.lan/rss', 'http://host.home/rss', 'https://one.invalid/rss',
    'https://bad_host.publisher.org/rss', 'https://-bad.publisher.org/rss', `${ORIGIN}/api/feed`,
    'https://feeds.publisher.org/\\localhost/rss', 'https://feeds.publisher.org/\nfeed',
    'https://feeds.publisher.org/' + 'x'.repeat(8192),
  ];
  for (const value of urls) {
    const response = await call(`/api/feed?url=${encodeURIComponent(value)}`);
    assert.equal(response.status, 400, value);
  }
});

test('rejects missing, duplicate and unsupported query parameters', async () => {
  neverFetch();
  for (const path of ['/api/feed', `/api/feed?url=${FEED}&url=${FEED}`, `/api/feed?url=${FEED}&headers=secret`]) {
    assert.equal((await call(path)).status, 400, path);
  }
});

test('returns XML and final URL while stripping both caller credentials and publisher cookies', async () => {
  let requested;
  globalThis.fetch = async (url, options) => {
    requested = {url, options};
    return xml(RSS, {'Set-Cookie': 'publisher=secret', 'Access-Control-Allow-Origin': '*'});
  };
  const response = await call(undefined, {headers: {Origin: ORIGIN, 'Sec-Fetch-Site': 'same-origin', Cookie: 'private=1',
    Authorization: 'Bearer secret', Referer: ORIGIN + '/app/', Range: 'bytes=0-100'}});
  assert.equal(response.status, 200);
  assert.equal(await response.text(), RSS);
  assert.equal(response.headers.get('X-Feed-URL'), FEED);
  assert.equal(response.headers.get('Content-Type'), 'application/xml');
  assert.equal(response.headers.get('Cache-Control'), 'public, max-age=300');
  assert.equal(response.headers.get('Set-Cookie'), null);
  assert.equal(response.headers.get('Access-Control-Allow-Origin'), null);
  assert.equal(response.headers.get('X-Content-Type-Options'), 'nosniff');
  assert.equal(response.headers.get('Cross-Origin-Resource-Policy'), 'same-origin');
  assert.match(response.headers.get('Content-Security-Policy'), /sandbox/);
  assert.equal(requested.url, FEED);
  assert.equal(requested.options.credentials, 'omit');
  assert.equal(requested.options.redirect, 'manual');
  const headers = new Headers(requested.options.headers);
  for (const name of ['Cookie', 'Authorization', 'Referer', 'Origin', 'Range']) assert.equal(headers.get(name), null);
});

test('accepts public HTTP and default ports, normalizing URL fragments', async () => {
  let target;
  globalThis.fetch = async url => { target = url; return xml(); };
  const response = await call(`/api/feed?url=${encodeURIComponent('http://feeds.publisher.org:80/rss#latest')}`);
  assert.equal(response.status, 200);
  assert.equal(target, 'http://feeds.publisher.org/rss');
  assert.equal(response.headers.get('X-Feed-URL'), target);
});

test('follows relative and absolute redirects and returns the final feed location', async () => {
  const targets = [];
  globalThis.fetch = async url => {
    targets.push(url);
    if (targets.length === 1) return new Response(null, {status: 301, headers: {Location: '/new.xml'}});
    if (targets.length === 2) return new Response(null, {status: 307, headers: {Location: 'https://cdn.publisher.org/final.xml'}});
    return xml();
  };
  const response = await call();
  assert.equal(response.status, 200);
  assert.deepEqual(targets, [FEED, 'https://feeds.publisher.org/new.xml', 'https://cdn.publisher.org/final.xml']);
  assert.equal(response.headers.get('X-Feed-URL'), targets[2]);
});

test('validates redirect targets before making another request', async () => {
  for (const location of ['http://127.0.0.1/rss', 'http://private.local/rss', 'https://user:pass@feeds.publisher.org/rss', `${ORIGIN}/api/feed`, 'file:///etc/passwd']) {
    let count = 0;
    globalThis.fetch = async () => { count++; return new Response(null, {status: 302, headers: {Location: location}}); };
    const response = await call();
    assert.equal(response.status, 400, location);
    assert.equal(count, 1);
  }
});

test('limits redirect loops to five hops and handles missing redirect locations', async () => {
  let count = 0;
  globalThis.fetch = async () => { count++; return new Response(null, {status: 302, headers: {Location: FEED}}); };
  assert.equal((await call()).status, 502);
  assert.equal(count, 6);
  globalThis.fetch = async () => new Response(null, {status: 302});
  assert.equal((await call()).status, 502);
});

test('rejects upstream error, empty and partial responses without exposing their bodies', async () => {
  for (const status of [204, 206, 401, 403, 404, 429, 500]) {
    globalThis.fetch = async () => new Response(status === 204 ? null : 'upstream secrets', {status});
    const response = await call();
    assert.equal(response.status, 502);
    const error = (await response.json()).error;
    assert.ok(!error.includes('upstream secrets'));
    assert.ok(error.includes(String(status)));
  }
});

test('rejects HTML, audio, binary downloads and arbitrary XML rather than acting as a content proxy', async () => {
  for (const [body, type] of [['<html>bad</html>', 'text/html'], ['audio', 'audio/mpeg'], ['archive', 'application/zip'],
    ['<html>bad</html>', 'text/plain'], ['<root>not a feed</root>', 'application/xml'], ['{"feed":[]}', 'application/json']]) {
    globalThis.fetch = async () => new Response(body, {headers: {'Content-Type': type}});
    assert.equal((await call()).status, 415, type + ': ' + body);
  }
});

test('supports RSS and Atom, XML preambles, and text/octet-stream publisher MIME types', async () => {
  for (const [body, type] of [[RSS, 'text/plain'], ['<!-- syndicated --><feed xmlns="http://www.w3.org/2005/Atom"/>', 'application/atom+xml'],
    ['\uFEFF<?xml version="1.0"?>\n<!-- a -->\n<?publisher note?><rss/>', 'application/octet-stream'],
    ['<atom:feed xmlns:atom="http://www.w3.org/2005/Atom"/>', 'text/xml; charset="ISO-8859-1"']]) {
    globalThis.fetch = async () => new Response(body, {headers: {'Content-Type': type}});
    const response = await call();
    assert.equal(response.status, 200, body);
    if (type.includes('ISO')) assert.equal(response.headers.get('Content-Type'), 'application/xml; charset=ISO-8859-1');
  }
});

test('rejects document type declarations and an overlong or malformed XML preamble', async () => {
  for (const body of ['<!DOCTYPE rss [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss>&x;</rss>',
    '<?xml version="1.0"?><!DOCTYPE rss><rss/>', '<!--' + 'x'.repeat(65536) + '--><rss/>', '<rssfake/>', '']) {
    globalThis.fetch = async () => xml(body);
    assert.equal((await call()).status, 415);
  }
});

test('refuses oversized Content-Length without consuming the response', async () => {
  let canceled = false;
  globalThis.fetch = async () => new Response(new ReadableStream({cancel() { canceled = true; }}), {
    headers: {'Content-Type': 'application/xml', 'Content-Length': String(32 * 1024 * 1024 + 1)},
  });
  const response = await call();
  assert.equal(response.status, 413);
  assert.equal(canceled, true);
  assert.match((await response.json()).error, /32 MiB/);
});

test('bounds the actual streamed body even when Content-Length is missing or misleading', async () => {
  let canceled = false;
  let reads = 0;
  const chunk = new Uint8Array(1024 * 1024);
  globalThis.fetch = async () => new Response(new ReadableStream({
    pull(controller) { reads++; controller.enqueue(chunk); },
    cancel() { canceled = true; },
  }), {headers: {'Content-Type': 'application/xml', 'Content-Length': '100'}});
  assert.equal((await call()).status, 413);
  assert.equal(canceled, true);
  assert.ok(reads <= 34);
});

test('aborts a stalled publisher and reports a friendly timeout', async () => {
  globalThis.setTimeout = (fn, ms, ...args) => realSetTimeout(fn, ms === 30_000 ? 1 : ms, ...args);
  globalThis.fetch = async (_url, {signal}) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(signal.reason), {once: true});
  });
  const response = await call();
  assert.equal(response.status, 504);
  assert.match((await response.json()).error, /too long/);
});

test('network failures have no upstream implementation or credential details', async () => {
  globalThis.fetch = async () => { throw new Error('secret upstream diagnostic'); };
  const response = await call();
  assert.equal(response.status, 502);
  assert.ok(!(await response.text()).includes('secret'));
});

test('the deadline also covers a response body that stalls after its headers', async () => {
  globalThis.setTimeout = (fn, ms, ...args) => realSetTimeout(fn, ms === 30_000 ? 1 : ms, ...args);
  globalThis.fetch = async (_url, {signal}) => new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode('<rss>'));
      signal.addEventListener('abort', () => controller.error(signal.reason), {once: true});
    },
  }), {headers: {'Content-Type': 'application/rss+xml'}});
  assert.equal((await call()).status, 504);
});

test('caches successful metadata by normalized URL and checks caller origin before cache access', async () => {
  const saved = new Map();
  let fetches = 0;
  let reads = 0;
  const background = [];
  globalThis.caches = {default: {
    async match(request) { reads++; return saved.get(request.url)?.clone(); },
    async put(request, response) { saved.set(request.url, response); },
  }};
  globalThis.fetch = async () => { fetches++; return xml(); };
  const context = {waitUntil(promise) { background.push(promise); }};
  assert.equal((await call(undefined, {}, {}, context)).status, 200);
  await Promise.all(background);
  assert.equal((await call(`/api/feed?url=${encodeURIComponent(FEED + '#ignored')}`)).status, 200);
  assert.equal(fetches, 1);
  assert.equal(reads, 2);
  assert.equal((await call(undefined, {headers: {Origin: 'https://hostile.org'}})).status, 403);
  assert.equal(reads, 2);
});

test('does not cache errors and tolerates unavailable cache storage', async () => {
  let writes = 0;
  globalThis.caches = {default: {async match() { throw new Error('unavailable'); }, async put() { writes++; throw new Error('unavailable'); }}};
  globalThis.fetch = async () => new Response('bad', {status: 503});
  assert.equal((await call()).status, 502);
  assert.equal(writes, 0);
  globalThis.fetch = async () => xml();
  assert.equal((await call()).status, 200);
  assert.equal(writes, 1);
  globalThis.caches.default.put = () => { throw new Error('synchronous cache failure'); };
  assert.equal((await call()).status, 200);
});


test('directory endpoints are retired without contacting upstream', async () => {
  neverFetch();
  for (const path of ['/api/search?term=science', '/api/chart?country=fr']) assert.equal((await call(path)).status, 404);
});

test('UTF-16 feeds and harmless DOCTYPE text pass; real DTDs fail in any encoding', async () => {
  for (const bigEndian of [false, true]) {
    const encode = value => {
      const bytes = Buffer.from('\ufeff' + value, 'utf16le');
      return bigEndian ? bytes.swap16() : bytes;
    };
    const body = RSS.replace('<title>Example</title>', '<description><![CDATA[<!DOCTYPE html><html>Notes</html>]]></description>');
    globalThis.fetch = async () => xml(encode(body));
    assert.equal((await call()).status, 200);
    globalThis.fetch = async () => xml(encode('<!DOCTYPE rss [<!ENTITY x "boom">]><rss><channel/></rss>'));
    assert.equal((await call()).status, 415);
  }
  globalThis.fetch = async () => xml('<!-- <!DOCTYPE harmless> -->' + RSS.replace('<?xml version="1.0"?>', ''));
  assert.equal((await call()).status, 200);
});
