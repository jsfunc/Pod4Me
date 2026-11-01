import {directoryRows} from './domain.mjs';

const LIMIT = 2 * 1024 * 1024;
const ORIGIN = 'https://itunes.apple.com';

export function createDirectoryClient({fetcher = (...args) => fetch(...args), now = Date.now,
  wait = ms => new Promise(resolve => setTimeout(resolve, ms))} = {}) {
  const cache = new Map();
  let tail = Promise.resolve(), last = -Infinity;
  function request(url) {
    const run = tail.then(async () => {
      const cached = cache.get(url.href);
      if (cached && now() - cached.time < 600000) return structuredClone(cached.data);
      await wait(Math.max(0, 3100 - (now() - last)));
      last = now();
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 30000);
      try {
        const response = await fetcher(url.href, {signal: controller.signal, credentials: 'omit',
          redirect: 'error', referrerPolicy: 'no-referrer', headers: {Accept: 'application/json'}});
        if (!response.ok || response.status === 204 || response.status === 206) {
          await response.body?.cancel();
          throw new Error(response.status === 429 ? 'The podcast directory is busy. Please try again shortly.' : 'The podcast directory is unavailable. Please try again later.');
        }
        if (Number(response.headers.get('Content-Length')) > LIMIT) {
          await response.body?.cancel();
          throw new Error('The directory response is too large.');
        }
        if (!response.body) throw new Error('The directory returned an empty response.');
        const reader = response.body.getReader(), chunks = [];
        let size = 0;
        try {
          while (true) {
            const {done, value} = await reader.read();
            if (done) break;
            size += value.byteLength;
            if (size > LIMIT) { await reader.cancel(); throw new Error('The directory response is too large.'); }
            chunks.push(value);
          }
        } finally { reader.releaseLock(); }
        const bytes = new Uint8Array(size);
        let offset = 0;
        for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
        let data;
        try { data = JSON.parse(new TextDecoder().decode(bytes)); }
        catch { throw new Error('The directory returned unreadable data. Please try again.'); }
        cache.delete(url.href);
        cache.set(url.href, {time: now(), data});
        while (cache.size > 40) cache.delete(cache.keys().next().value);
        return structuredClone(data);
      } catch (error) {
        if (controller.signal.aborted) throw new Error('The podcast directory took too long to respond. Please try again.');
        if (error instanceof TypeError) throw new Error('Could not reach the podcast directory. Check your connection and try again.');
        throw error;
      } finally { clearTimeout(timer); }
    });
    tail = run.catch(() => {});
    return run;
  }
  return async function directory(term = '', country = 'fr') {
    if (!/^[a-z]{2}$/.test(country)) throw new Error('Choose a valid directory country.');
    if (typeof term !== 'string' || term.length > 150 || /[\u0000-\u001f\u007f]/.test(term)) throw new Error('Use a search of at most 150 characters.');
    if (term.trim()) {
      const url = new URL('/search', ORIGIN);
      url.search = new URLSearchParams({term: term.trim(), country, media: 'podcast', entity: 'podcast', limit: '40'});
      return directoryRows(await request(url));
    }
    const chart = await request(new URL(`/${country}/rss/toppodcasts/limit=40/json`, ORIGIN));
    const entries = Array.isArray(chart?.feed?.entry) ? chart.feed.entry : [];
    const ids = [...new Set(entries.slice(0, 40).map(entry => String(entry?.id?.attributes?.['im:id'] || '')).filter(id => /^\d{1,20}$/.test(id)))];
    if (!ids.length) return [];
    const url = new URL('/lookup', ORIGIN);
    url.search = new URLSearchParams({id: ids.join(','), country, entity: 'podcast', limit: '40'});
    return directoryRows(await request(url)).sort((a, b) => ids.indexOf(a.id) - ids.indexOf(b.id));
  };
}
