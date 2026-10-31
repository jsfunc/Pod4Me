#!/usr/bin/env node
// Dependency-free development server, Node 18+. It runs the real relay module;
// Cloudflare cache, CPU quotas, edge networking and asset routing need deployed QA.
import {createServer} from 'node:http';
import {readFile, stat} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {resolve, dirname, extname, sep} from 'node:path';
import {Readable} from 'node:stream';
import worker from './worker.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const arguments_ = process.argv.slice(2);
if (arguments_.includes('--help')) {
  console.log('Usage: node webapp/dev.mjs [--port 18876]\nOpen http://127.0.0.1:18876/app/ (local machine only).');
  process.exit(0);
}
const portText = arguments_.length ? arguments_[1] : '18876';
if ((arguments_.length && (arguments_.length !== 2 || arguments_[0] !== '--port')) || !/^\d+$/.test(portText || '') || Number(portText) < 1 || Number(portText) > 65535) {
  console.error('Use: node webapp/dev.mjs [--port 18876]');
  process.exit(1);
}
const port = Number(portText);
const MIME = {'.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.json': 'application/json; charset=utf-8', '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.txt': 'text/plain; charset=utf-8'};
const SECURITY = {'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer', 'X-Frame-Options': 'DENY',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=()', 'Cache-Control': 'no-store'};

function escapeHtml(value) {
  return value.replace(/[&<>"']/g, char => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[char]));
}

async function staticAsset(request) {
  if (!['GET', 'HEAD'].includes(request.method)) return new Response('Method not allowed', {status: 405, headers: {Allow: 'GET, HEAD', ...SECURITY}});
  const url = new URL(request.url);
  let path;
  try { path = decodeURIComponent(url.pathname); } catch { return new Response('Invalid path', {status: 400}); }
  if (path.includes('\0') || path.includes('\\') || path.split('/').includes('..')) return new Response('Invalid path', {status: 400});
  if (path === '/app') return new Response(null, {status: 308, headers: {Location: '/app/' + url.search, ...SECURITY}});
  let file;
  if (path.startsWith('/app/')) {
    const appRoot = resolve(root, 'webapp/public/app');
    file = resolve(appRoot, path.slice('/app/'.length) || 'index.html');
    if (!file.startsWith(appRoot + sep)) return new Response('Not found', {status: 404});
  } else if (path === '/.well-known/assetlinks.json') {
    for (const candidate of ['dist/Pod4Me-web/public/.well-known/assetlinks.json', 'dist/Pod4Me-website/.well-known/assetlinks.json']) {
      try { await stat(resolve(root, candidate)); file = resolve(root, candidate); break; } catch { /* Try the other generated package. */ }
    }
    if (!file) return new Response('Run python3 tools/package-web-app.py to generate verified Android association data.', {status: 503, headers: SECURITY});
  } else if (path === '/tests/parser.html') {
    file = resolve(root, 'webapp/tests/parser.html');
  } else if (['/', '/index.html', '/style.css', '/link.js', '/page.js', '/404.html'].includes(path)) {
    file = resolve(root, 'website', path === '/' ? 'index.html' : path.slice(1));
  }
  try {
    if (!file) throw new Error('Not found');
    let data = await readFile(file);
    if (file === resolve(root, 'website/index.html')) {
      const downloadUrl = (await readFile(resolve(root, 'website/download-url.txt'), 'utf8')).split(/\r?\n/).map(line => line.trim()).find(line => line && !line.startsWith('#')) || '';
      data = Buffer.from(data.toString('utf8').replaceAll('@@APK_DOWNLOAD_URL@@', escapeHtml(downloadUrl)));
    }
    return new Response(request.method === 'HEAD' ? null : data, {headers: {...SECURITY, 'Content-Type': MIME[extname(file)] || 'application/octet-stream'}});
  } catch {
    return new Response(request.method === 'HEAD' ? null : 'Not found', {status: 404, headers: {...SECURITY, 'Content-Type': 'text/plain; charset=utf-8'}});
  }
}

const server = createServer(async (incoming, outgoing) => {
  try {
    const host = incoming.headers.host || `127.0.0.1:${port}`;
    if (![ `127.0.0.1:${port}`, `localhost:${port}` ].includes(host)) {
      outgoing.writeHead(400); outgoing.end('Use the localhost address printed by this server.'); return;
    }
    const url = new URL(incoming.url, `http://${host}`);
    const request = new Request(url, {method: incoming.method, headers: incoming.headers});
    const response = await worker.fetch(request, {ASSETS: {fetch: staticAsset}}, {waitUntil(promise) { promise.catch(() => {}); }});
    outgoing.writeHead(response.status, Object.fromEntries(response.headers));
    if (request.method === 'HEAD' || !response.body) outgoing.end();
    else {
      const body = Readable.fromWeb(response.body);
      body.on('error', () => outgoing.destroy());
      outgoing.on('close', () => body.destroy());
      body.pipe(outgoing);
    }
  } catch (error) {
    console.error(error.message);
    if (!outgoing.headersSent) outgoing.writeHead(500, {'Content-Type': 'text/plain; charset=utf-8'});
    outgoing.end('Local server error. Check the terminal.');
  }
});
server.on('error', error => { console.error(error.message); process.exitCode = 1; });
server.listen(port, '127.0.0.1', () => {
  console.log(`Pod4Me web: http://127.0.0.1:${port}/app/\nSharing page: http://127.0.0.1:${port}/\nPress Ctrl+C to stop. No deployment is performed.`);
});
