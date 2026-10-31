import test from 'node:test';
import assert from 'node:assert/strict';
import {safeUrl, fold, stableId, shareLink, parseShared, parseFeedList, exportFeedList, emptyLibrary, mergeFeed, rankSuggestions} from '../public/app/domain.mjs';

test('public URLs normalize safely and reject local or ambiguous addresses', () => {
  assert.equal(safeUrl('HTTPS://Feeds.Podcast.org:443/show.xml'), 'https://feeds.podcast.org/show.xml');
  assert.equal(safeUrl('../audio.mp3', 'https://cdn.podcast.org/show/feed.xml'), 'https://cdn.podcast.org/audio.mp3');
  for (const value of ['http://localhost/x', 'https://a.local/x', 'https://a.internal/x', 'http://127.0.0.1/x', 'http://2130706433/x', 'http://0x7f000001/x', 'http://[::1]/x', 'https://a.org:444/x', 'https://user:pass@a.org/x', 'javascript:alert(1)', 'file:///etc/passwd', 'https://a.org/a b', 'https://a.org\\@localhost/x']) assert.throws(() => safeUrl(value), value);
});

test('feed and episode sharing round trip with Android fragment format', () => {
  const feed = {kind: 'feed', url: 'https://radiofrance-podcast.net/podcast09/feed.xml', title: 'Culture & société'};
  const link = shareLink(feed);
  assert.match(link, /^https:\/\/pod4me\.aurelien-garivier\.workers\.dev\/#feed\?url=/);
  assert.match(link, /title=Culture\+%26\+soci%C3%A9t%C3%A9/);
  assert.deepEqual(parseShared(`Podcast suggestion: Culture\n${link}`), {...feed, feedUrl: ''});
  const episode = {kind: 'episode', url: 'https://audio.podcast.org/a.m4a?token=hello%20world&x=2', title: 'Trump face aux midterms', feedUrl: feed.url};
  assert.deepEqual(parseShared(`Episode suggestion: ${episode.title}\n${episode.url}\n${shareLink(episode)}\nInstructions here`), episode);
  assert.deepEqual(parseShared('pod4me://feed?url=https%3A%2F%2Fa.org%2Ffeed.xml&title=An+old+link'), {kind: 'feed', url: 'https://a.org/feed.xml', title: 'An old link', feedUrl: ''});
});

test('malformed typed links fail instead of silently opening raw content', () => {
  for (const text of ['pod4me://feed?url=https://a.org/feed&url=https://b.org/feed', 'pod4me://feed?url=file:///secret', 'pod4me://episode?url=https://a.org/a.mp3&feed=http://localhost/rss', 'pod4me://feed?url=https://a.org/%XX', 'pod4me://feed/path?url=https://a.org/feed', 'x'.repeat(65537)]) assert.throws(() => parseShared(text));
  assert.equal(parseShared('https://a.org/audio.MP3?x=1').kind, 'episode');
  assert.equal(parseShared('https://a.org/feed.xml').kind, 'unknown');
  assert.throws(() => parseShared('https://user:pass@pod4me.aurelien-garivier.workers.dev/#feed?url=https://a.org/rss'));
  assert.throws(() => parseShared('https://pod4me.aurelien-garivier.workers.dev/?url=confusing#feed?url=https://a.org/rss'));
});

test('Android text lists handle BOM, Unicode whitespace and duplicates', () => {
  const feeds = parseFeedList('\uFEFFhttps://a.org/rss\u00a0[Écouter maintenant]\r\nhttps://b.org/a%20b.xml [Another]\nhttps://a.org/rss [duplicate]\n');
  assert.deepEqual(feeds, [{url: 'https://a.org/rss', name: 'Écouter maintenant'}, {url: 'https://b.org/a%20b.xml', name: 'Another'}]);
  const exported = exportFeedList([...feeds.map(feed => ({...feed, subscribed: true})), {url: 'https://hidden.org/rss', subscribed: false}, {url: '', subscribed: true}, {url: 'https://c.org/rss', name: 'A\nnew\tshow', subscribed: true}]);
  assert.equal(exported, 'https://a.org/rss [Écouter maintenant]\nhttps://b.org/a%20b.xml [Another]\nhttps://c.org/rss [A new show]\n');
  assert.equal(parseFeedList(exported).length, 3);
  assert.throws(() => parseFeedList('https://a.org/rss\nfile:///secret'), /Line 2/);
  assert.throws(() => parseFeedList(Array.from({length: 1001}, (_, i) => `https://a.org/${i}`).join('\n')), /1,000/);
});

test('merge preserves identity and all user state; highlights only latest discoveries', () => {
  const state = emptyLibrary();
  const episode = {key: 'guid-1', title: 'First', audioUrl: 'https://audio.org/1.mp3', published: 1, duration: 90, description: 'Old notes', artworkUrl: ''};
  const parsed = {name: 'Publisher title', url: 'https://a.org/rss', artworkUrl: '', episodes: [episode]};
  const feed = mergeFeed(state, parsed, parsed.url, 100);
  const first = state.episodes[0];
  Object.assign(first, {heard: true, stars: 4, position: 70, lists: ['favorites'], bookmarks: [{position: 10, name: 'Important'}]});
  feed.name = 'My custom title';
  const other = mergeFeed(state, {...parsed, url: 'https://b.org/rss'}, 'https://b.org/rss', 100);
  mergeFeed(state, {...parsed, name: 'New publisher name', episodes: [{...episode, title: 'Updated title', audioUrl: 'https://audio.org/new.mp3'}, {...episode, key: 'guid-2', title: 'Second'}]}, parsed.url, 200);
  assert.equal(feed.name, 'My custom title');
  assert.equal(feed.updated, 200);
  assert.equal(first.id, `e-${stableId(`${parsed.url}\nguid-1`)}`);
  assert.equal(first.title, 'Updated title');
  assert.equal(first.heard, true);
  assert.equal(first.stars, 4);
  assert.equal(first.position, 70);
  assert.deepEqual(first.lists, ['favorites']);
  assert.equal(first.bookmarks[0].name, 'Important');
  assert.equal(first.isNew, false);
  assert.equal(state.episodes.find(item => item.feedId === other.id).isNew, true);
  assert.equal(state.episodes.find(item => item.feedId === feed.id && item.key === 'guid-2').isNew, true);
  feed.subscribed = false;
  assert.equal(mergeFeed(state, {...parsed, episodes: []}, parsed.url, 300).id, feed.id);
  assert.equal(state.episodes.length, 3, 'Retain missing episodes and history');
  assert.equal(first.stars, 4);
  assert.equal(feed.subscribed, true);
});

test('suggestions exclude previous batch and subscriptions, but older dislikes can return', () => {
  const state = emptyLibrary();
  state.feeds.push({url: 'https://a.org/subscribed', name: 'History', subscribed: true});
  state.previous = ['https://a.org/previous'];
  state.recent = ['https://a.org/recent'];
  state.votes = {'https://a.org/liked': {vote: 1, name: 'Science and space'}, 'https://a.org/disliked': {vote: -1, name: 'Celebrity gossip'}};
  const entries = ['subscribed', 'previous', 'recent', 'disliked', 'science', 'other'].map(name => ({url: `https://a.org/${name}`, name: name === 'science' ? 'Space Science today' : name}));
  entries.push({...entries[4]}, {url: 'http://localhost/invalid'});
  const ranked = rankSuggestions(entries, state);
  assert.equal(ranked[0].name, 'Space Science today');
  assert.equal(ranked.length, 4);
  assert.ok(ranked.some(item => item.url.endsWith('/disliked')));
  assert.ok(!ranked.some(item => item.url.endsWith('/previous') || item.url.endsWith('/subscribed')));
  state.previous = ranked.map(item => item.url);
  assert.deepEqual(rankSuggestions(entries, state).map(item => item.url), ['https://a.org/previous']);
});

test('fresh libraries do not share mutable state; text matching ignores accents', () => {
  const first = emptyLibrary(), second = emptyLibrary();
  first.queue.push('one');
  assert.equal(second.queue.length, 0);
  assert.equal(fold('  Économie\n Française  '), 'economie francaise');
});
