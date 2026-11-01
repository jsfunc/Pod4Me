import test from 'node:test';
import assert from 'node:assert/strict';
import {createDirectoryClient} from '../public/app/directory.mjs';
import {directoryRows} from '../public/app/domain.mjs';

const podcast = id => ({collectionId: id, collectionName: `Show ${id}`, artistName: 'Publisher',
  feedUrl: `https://podcasts.org/${id}`, collectionViewUrl: `https://podcasts.apple.com/podcast/id${id}`});
const json = data => new Response(JSON.stringify(data));
function client(fetcher) {
  let time = 0;
  const waits = [];
  return {waits, directory: createDirectoryClient({fetcher, now: () => time, wait: async ms => { waits.push(ms); time += ms; }})};
}

test('Apple results are bounded, sanitised, deduplicated and restricted to public URLs', () => {
  const results = directoryRows({results: [podcast(1), podcast(1), {...podcast(2), feedUrl: 'http://127.0.0.1/x'},
    {...podcast(3), collectionName: 'x'.repeat(500), artistName: 'A\u0000B', artworkUrl600: 'javascript:x', collectionViewUrl: 'https://evil.org/x', genres: ['Science', 123]}]});
  assert.equal(results.length, 2);
  assert.equal(results[1].name.length, 300);
  assert.equal(results[1].artist, 'A B');
  assert.equal(results[1].collectionUrl, '');
  assert.equal(results[1].artworkUrl, '');
  assert.deepEqual(results[1].genres, ['Science']);
  assert.equal(directoryRows({results: Array.from({length: 100}, (_, n) => podcast(n))}).length, 40);
});

test('search is direct, safely encoded, spaced, cached and has no credentials or redirects', async () => {
  const requests = [];
  const {directory, waits} = client(async (url, options) => { requests.push({url: new URL(url), options}); return json({results: [podcast(1)]}); });
  const first = await directory('science & radio', 'fr');
  first[0].name = 'Modified';
  assert.equal((await directory('science & radio', 'fr'))[0].name, 'Show 1');
  await directory('history', 'gb');
  assert.equal(requests.length, 2);
  assert.equal(requests[0].url.origin + requests[0].url.pathname, 'https://itunes.apple.com/search');
  assert.equal(requests[0].url.searchParams.get('term'), 'science & radio');
  assert.equal(requests[0].options.credentials, 'omit');
  assert.equal(requests[0].options.redirect, 'error');
  assert.deepEqual(waits, [0, 3100]);
});

test('chart resolves validated IDs in chart order with spacing between chart and lookup', async () => {
  const requests = [];
  const {directory, waits} = client(async url => {
    requests.push(new URL(url));
    return requests.length === 1 ? json({feed: {entry: ['2', '1', '2', 'bad&x=3'].map(id => ({id: {attributes: {'im:id': id}}}))}}) : json({results: [podcast(1), podcast(2)]});
  });
  assert.deepEqual((await directory('', 'fr')).map(item => item.id), ['2', '1']);
  assert.equal(requests[1].pathname, '/lookup');
  assert.equal(requests[1].searchParams.get('id'), '2,1');
  assert.deepEqual(waits, [0, 3100]);
});

test('failed requests are not cached; invalid inputs and oversized bodies fail clearly', async () => {
  let calls = 0;
  const {directory} = client(async () => ++calls === 1 ? new Response('', {status: 429}) : json({results: [podcast(1)]}));
  await assert.rejects(directory('science'), /busy/);
  assert.equal((await directory('science')).length, 1);
  assert.equal(calls, 2);
  await assert.rejects(directory('x'.repeat(151)), /150/);
  await assert.rejects(directory('x', 'invalid'), /country/);
  const large = client(async () => new Response('x'.repeat(2 * 1024 * 1024 + 1)));
  await assert.rejects(large.directory('x'), /too large/);
  const malformed = client(async () => new Response('not JSON'));
  await assert.rejects(malformed.directory('x'), /unreadable/);
});
