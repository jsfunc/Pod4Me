// Shared, dependency-free domain logic. XML functions require the browser DOMParser.
export const SHARE_BASE = 'https://pod4me.aurelien-garivier.workers.dev/';
const XML_NS = 'http://www.w3.org/XML/1998/namespace';
const AUDIO_EXTENSION = /\.(mp3|m4a|aac|ogg|oga|opus|wav|flac|aiff|aif)(?:$|[?#])/i;
const MAX_FEEDS = 1000;

export function safeUrl(value, base) {
  if (typeof value !== 'string' || !value || /[\s\u0000-\u001f\u007f\\]/u.test(value)) throw new Error('Enter a public HTTP or HTTPS URL without spaces.');
  let url;
  try { url = new URL(value, base); } catch { throw new Error('Invalid URL.'); }
  const host = url.hostname.toLowerCase().replace(/\.$/, '');
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.port ||
      !host.includes('.') || /^[\d.]+$/.test(host) || host.includes(':') ||
      /(?:^|\.)(localhost|local|internal|home|lan|test|invalid|example)$/.test(host) ||
      !/^[a-z0-9.-]+$/.test(host) || host.split('.').some(part => !part || part.startsWith('-') || part.endsWith('-'))) {
    throw new Error('Only public HTTP or HTTPS addresses on standard ports are supported.');
  }
  return url.href;
}

function optionalUrl(value, base) {
  try { return safeUrl(String(value || '').trim(), base); } catch { return ''; }
}

export function fold(value) {
  return String(value ?? '').normalize('NFD').replace(/\p{M}/gu, '').toLocaleLowerCase().replace(/\s+/gu, ' ').trim();
}

export function stableId(value) {
  let a = 2166136261, b = 2246822507;
  for (const ch of String(value)) {
    const code = ch.codePointAt(0);
    a = Math.imul(a ^ code, 16777619);
    b = Math.imul(b ^ code, 3266489909);
  }
  return `${(a >>> 0).toString(36)}${(b >>> 0).toString(36)}`;
}

function xmlDocument(text, limit) {
  if (typeof text !== 'string' || new TextEncoder().encode(text).length > limit) throw new Error('This file is too large.');
  if (/<!DOCTYPE/i.test(text)) throw new Error('XML document types are not supported.');
  if (typeof DOMParser === 'undefined') throw new Error('XML parsing needs a browser.');
  const document = new DOMParser().parseFromString(text, 'application/xml');
  if (document.getElementsByTagName('parsererror').length || document.documentElement?.localName === 'parsererror') throw new Error('The feed is not valid XML.');
  return document;
}

const children = (node, name, ns) => Array.from(node?.children || []).filter(child => child.localName === name && (ns === undefined || child.namespaceURI === ns));
const child = (node, name, ns) => children(node, name, ns)[0];
const content = node => node?.textContent?.trim() || '';
const ownText = (node, name) => content(child(node, name, node.namespaceURI));

function nodeBase(node, fallback) {
  const ancestors = [];
  for (let current = node; current?.nodeType === 1; current = current.parentElement) ancestors.push(current);
  let base = fallback;
  for (const current of ancestors.reverse()) {
    const value = current.getAttributeNS(XML_NS, 'base');
    if (value) base = optionalUrl(value, base) || base;
  }
  return base;
}

function plainText(value) {
  // Template contents are inert: images, scripts and iframes are never attached
  // to the live page, nor can they start requests during description parsing.
  const template = globalThis.document.createElement('template');
  template.innerHTML = String(value || '');
  template.content.querySelectorAll('script,style,iframe,object,embed,template,noscript').forEach(node => node.remove());
  template.content.querySelectorAll('br').forEach(node => node.replaceWith('\n'));
  template.content.querySelectorAll('p,div,li,h1,h2,h3,h4,blockquote').forEach(node => node.append('\n'));
  return template.content.textContent.replace(/[\t\r ]+/g, ' ').replace(/ *\n */g, '\n').replace(/\n{3,}/g, '\n\n').trim();
}

function description(node) {
  const candidates = ['encoded', 'content', 'description', 'summary', 'subtitle'];
  for (const name of candidates) {
    const element = child(node, name);
    if (!element) continue;
    const text = element.children.length ? element.innerHTML : element.textContent;
    const result = plainText(text);
    if (result) return result;
  }
  return '';
}

function duration(value) {
  if (!value) return 0;
  const parts = value.trim().split(':');
  if (parts.length > 3 || parts.some(part => !/^\d+(?:\.\d+)?$/.test(part))) return 0;
  const result = parts.reduce((total, part) => total * 60 + Number(part), 0);
  return Number.isFinite(result) && result >= 0 && result <= 31536000 ? result : 0;
}

function artwork(node, sourceUrl) {
  for (const image of children(node, 'image')) {
    const result = optionalUrl(image.getAttribute('href') || content(child(image, 'url')), nodeBase(image, sourceUrl));
    if (result) return result;
  }
  for (const image of children(node, 'thumbnail')) {
    const result = optionalUrl(image.getAttribute('url'), nodeBase(image, sourceUrl));
    if (result) return result;
  }
  for (const name of ['logo', 'icon']) {
    const image = child(node, name);
    const result = optionalUrl(content(image), nodeBase(image, sourceUrl));
    if (result) return result;
  }
  return '';
}

export function parseFeed(xml, sourceUrl) {
  sourceUrl = safeUrl(sourceUrl);
  const document = xmlDocument(xml, 32 * 1024 * 1024);
  const root = document.documentElement;
  const atom = root.localName === 'feed' && root.namespaceURI === 'http://www.w3.org/2005/Atom';
  const channel = atom ? root : child(root, 'channel');
  if (!channel || (!atom && !['rss', 'RDF'].includes(root.localName))) throw new Error('This address does not contain an RSS or Atom podcast feed.');
  const items = atom ? children(root, 'entry', root.namespaceURI) : children(root.localName === 'RDF' ? root : channel, 'item');
  const result = {name: plainText(ownText(channel, 'title')) || new URL(sourceUrl).hostname, url: sourceUrl, artworkUrl: artwork(channel, sourceUrl), episodes: [], skipped: 0};
  const seen = new Set();
  for (const item of items) {
    const enclosures = atom ? children(item, 'link', item.namespaceURI).filter(link => link.getAttribute('rel') === 'enclosure') : children(item, 'enclosure');
    let audioUrl = '';
    for (const enclosure of enclosures) {
      const address = optionalUrl(enclosure.getAttribute(atom ? 'href' : 'url'), nodeBase(enclosure, sourceUrl));
      const mime = (enclosure.getAttribute('type') || '').toLowerCase().split(';')[0].trim();
      if (address && (mime.startsWith('audio/') || mime === 'application/ogg' || (!mime && AUDIO_EXTENSION.test(address)))) { audioUrl = address; break; }
    }
    if (!audioUrl) { result.skipped++; continue; }
    const key = ownText(item, atom ? 'id' : 'guid') || audioUrl;
    if (seen.has(key)) continue;
    seen.add(key);
    const date = ownText(item, atom ? 'published' : 'pubDate') || ownText(item, 'updated') || content(child(item, 'date'));
    const timestamp = Date.parse(date);
    result.episodes.push({key, title: plainText(ownText(item, 'title')) || 'Untitled episode', audioUrl,
      published: Number.isFinite(timestamp) ? timestamp : 0, duration: duration(content(child(item, 'duration'))),
      description: description(item), artworkUrl: artwork(item, sourceUrl) || result.artworkUrl});
  }
  return result;
}

export function shareLink(item, base = SHARE_BASE) {
  const kind = item.kind || (item.audioUrl ? 'episode' : 'feed');
  if (!['feed', 'episode'].includes(kind)) throw new Error('Unsupported share type.');
  const params = new URLSearchParams({url: safeUrl(item.url || item.audioUrl)});
  const title = String(item.title || item.name || '').replace(/\s+/gu, ' ').trim();
  if (title) params.set('title', title);
  if (kind === 'episode' && (item.feedUrl || item.feed)) params.set('feed', safeUrl(item.feedUrl || item.feed));
  const target = new URL(safeUrl(base));
  target.search = '';
  target.hash = `${kind}?${params}`;
  return target.href;
}

function typedShare(kind, query) {
  if (!['feed', 'episode'].includes(kind)) throw new Error('Unsupported Pod4Me link.');
  if (/%(?![0-9a-f]{2})/i.test(query)) throw new Error('Invalid link encoding.');
  const params = new URLSearchParams(query);
  const seen = new Set();
  for (const [key] of params) {
    if (seen.has(key)) throw new Error('The shared link has duplicate fields.');
    seen.add(key);
  }
  const url = safeUrl(params.get('url') || '');
  const feedUrl = params.has('feed') ? safeUrl(params.get('feed')) : '';
  return {kind, url, title: (params.get('title') || '').replace(/\s+/gu, ' ').trim(), feedUrl};
}

export function parseShared(text) {
  if (typeof text !== 'string' || text.length > 65536) throw new Error('The shared message is too large.');
  const links = text.trim().match(/(?:https?:\/\/|pod4me:\/\/)[^\s<>"\u0000-\u001f]+/giu) || [];
  // Typed Pod4Me links take priority over a raw enclosure in old messages.
  for (const link of links) {
    let target;
    try { target = new URL(link); } catch { continue; }
    if (target.protocol === 'pod4me:') {
      if (target.username || target.password || target.port || target.pathname || target.hash) throw new Error('Invalid Pod4Me link.');
      return typedShare(target.hostname, target.search.slice(1));
    }
    if (target.origin === new URL(SHARE_BASE).origin && target.pathname === '/' && target.hash) {
      if (target.username || target.password || target.search) throw new Error('Invalid Pod4Me link.');
      const fragment = target.hash.slice(1);
      const separator = fragment.indexOf('?');
      if (separator < 0) throw new Error('Invalid Pod4Me link.');
      return typedShare(fragment.slice(0, separator), fragment.slice(separator + 1));
    }
  }
  for (const link of links) {
    const url = safeUrl(link);
    return {kind: AUDIO_EXTENSION.test(url) ? 'episode' : 'unknown', url, title: '', feedUrl: ''};
  }
  throw new Error('No supported link was found in this message.');
}

export function parseFeedList(text) {
  if (typeof text !== 'string' || new TextEncoder().encode(text).length > 4 * 1024 * 1024) throw new Error('Feed lists must be smaller than 4 MiB.');
  text = text.replace(/^\uFEFF/, '').trim();
  const feeds = [], seen = new Set();
  const add = (url, name = '') => {
    url = safeUrl(url);
    if (seen.has(url)) return;
    if (feeds.length >= MAX_FEEDS) throw new Error('A feed list can contain at most 1,000 feeds.');
    seen.add(url);
    feeds.push({url, name: String(name).replace(/\s+/gu, ' ').trim()});
  };
  if (text.startsWith('<')) {
    const document = xmlDocument(text, 4 * 1024 * 1024);
    if (document.documentElement.localName.toLowerCase() !== 'opml') throw new Error('This XML file is not an OPML subscription list.');
    for (const outline of document.getElementsByTagName('outline')) {
      const url = outline.getAttribute('xmlUrl') || outline.getAttribute('xmlurl');
      if (url) add(url.trim(), outline.getAttribute('title') || outline.getAttribute('text') || '');
    }
  } else {
    for (const [index, line] of text.split(/\r?\n/).entries()) {
      const match = line.trim().match(/^(\S+)(?:\s+([\s\S]*))?$/u);
      if (!match) continue;
      try { add(match[1], (match[2] || '').replace(/^\[([\s\S]*)\]$/, '$1')); }
      catch (error) { throw new Error(`Line ${index + 1}: ${error.message}`); }
    }
  }
  return feeds;
}

export function exportFeedList(feeds) {
  const seen = new Set();
  return feeds.filter(feed => feed.subscribed && feed.url).map(feed => {
    const url = safeUrl(feed.url);
    if (seen.has(url)) return '';
    seen.add(url);
    const name = String(feed.name || '').replace(/\s+/gu, ' ').trim();
    return name ? `${url} [${name}]` : url;
  }).filter(Boolean).join('\n') + '\n';
}

export function emptyLibrary() {
  return {version: 1, feeds: [], episodes: [], lists: [], queue: [], votes: {}, recent: [], previous: [], settings: {speed: 1, country: 'fr'}};
}

// Mutates the supplied library and returns its feed. Refresh callers must avoid
// applying an obsolete response after the user has unsubscribed.
export function mergeFeed(state, parsed, url = parsed.url, now = Date.now()) {
  url = safeUrl(url);
  let feed = state.feeds.find(item => item.url === url);
  if (!feed) {
    feed = {id: `f-${stableId(url)}`, url, name: parsed.name || new URL(url).hostname, subscribed: true, updated: now, artworkUrl: '', error: ''};
    state.feeds.push(feed);
  }
  Object.assign(feed, {subscribed: true, updated: now, artworkUrl: parsed.artworkUrl || '', error: ''});
  const existing = new Map();
  for (const episode of state.episodes) {
    if (episode.feedId !== feed.id) continue;
    episode.isNew = false;
    existing.set(episode.key || episode.audioUrl, episode);
  }
  for (const episode of parsed.episodes) {
    const key = episode.key || episode.audioUrl;
    const metadata = {key, title: episode.title, audioUrl: episode.audioUrl, published: episode.published || 0, duration: episode.duration || 0, description: episode.description || '', artworkUrl: episode.artworkUrl || feed.artworkUrl};
    if (existing.has(key)) { Object.assign(existing.get(key), metadata); continue; }
    const created = {id: `e-${stableId(`${url}\n${key}`)}`, feedId: feed.id, ...metadata, heard: false, stars: 0, lists: [], bookmarks: [], position: 0, isNew: true};
    state.episodes.push(created);
    existing.set(key, created);
  }
  return feed;
}

const words = item => new Set(fold([item.name, item.title, item.artist, item.artistName, item.author, ...(item.genres || [])].filter(Boolean).join(' ')).match(/[\p{L}\p{N}]{3,}/gu) || []);
const historyUrls = entries => new Set((entries || []).map(entry => typeof entry === 'string' ? entry : entry.url || entry.feedUrl));

export function rankSuggestions(candidates, state) {
  const subscribed = new Set(state.feeds.filter(feed => feed.subscribed).map(feed => feed.url));
  const previous = historyUrls(state.previous);
  const recent = historyUrls(state.recent);
  const votes = state.votes || {};
  const affinities = new Map();
  for (const [url, value] of Object.entries(votes)) {
    const vote = typeof value === 'number' ? value : value.vote ?? value.value ?? 0;
    const metadata = typeof value === 'object' ? value : candidates.find(item => (item.url || item.feedUrl) === url) || {};
    for (const word of words(metadata)) affinities.set(word, (affinities.get(word) || 0) + Math.sign(vote));
  }
  for (const feed of state.feeds.filter(feed => feed.subscribed)) {
    for (const word of words(feed)) affinities.set(word, (affinities.get(word) || 0) + 0.25);
  }
  const seen = new Set();
  const ranked = [];
  for (const [index, candidate] of candidates.entries()) {
    const url = optionalUrl(candidate.url || candidate.feedUrl);
    if (!url || seen.has(url) || subscribed.has(url) || previous.has(url)) continue;
    seen.add(url);
    const voteValue = votes[url];
    const vote = typeof voteValue === 'number' ? voteValue : voteValue?.vote ?? voteValue?.value ?? 0;
    let score = Math.sign(vote) * 6 - (recent.has(url) ? 4 : 0);
    for (const word of words(candidate)) score += affinities.get(word) || 0;
    ranked.push({...candidate, url, feedUrl: url, name: String(candidate.name || candidate.title || new URL(url).hostname), score, _order: index});
  }
  return ranked.sort((a, b) => b.score - a.score || a._order - b._order).map(({_order, ...candidate}) => candidate);
}
