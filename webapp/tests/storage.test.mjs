import test from 'node:test';
import assert from 'node:assert/strict';
import {emptyLibrary, mergeFeed} from '../public/app/domain.mjs';
import {loadLibrary, saveLibrary, validateBackup, makeBackup} from '../public/app/storage.mjs';

function library() {
  const state = emptyLibrary();
  const feed = mergeFeed(state, {url: 'https://podcast.org/rss', name: 'A show', artworkUrl: 'https://podcast.org/art.jpg', episodes: [{key: 'guid-1', title: 'Episode', audioUrl: 'https://podcast.org/audio.mp3', description: 'Notes'}]}, 'https://podcast.org/rss', 100);
  feed.name = 'Custom name';
  state.lists = [{id: 'list-1', name: 'Favorites'}];
  Object.assign(state.episodes[0], {heard: true, forever: true, stars: 5, position: 40, lists: ['list-1'], bookmarks: [{id: 'bookmark-1', position: 25, label: 'Remember'}]});
  state.queue = [state.episodes[0].id];
  state.votes = {'https://other.org/rss': {vote: 1, name: 'Science', genres: ['Astronomy']}};
  state.previous = ['https://previous.org/rss'];
  state.recent = ['https://recent.org/rss'];
  state.settings = {speed: 1.5, country: 'fr'};
  return state;
}

test('web backups preserve user state and use an explicit independent format', () => {
  const state = library();
  const backup = makeBackup(state);
  assert.equal(backup.format, 'pod4me-web');
  assert.deepEqual(validateBackup(JSON.parse(JSON.stringify(backup))), state);
  backup.library.episodes[0].stars = 1;
  assert.equal(state.episodes[0].stars, 5, 'Backup returns independent objects');
  assert.throws(() => validateBackup({version: 1, feeds: []}), /not a supported/);
  assert.throws(() => validateBackup({...backup, version: 99}), /not a supported/);
});

test('unsafe URLs and prototype payloads are rejected before restoration', () => {
  for (const invalid of ['javascript:alert(1)', 'file:///secret', 'http://localhost/rss', 'http://127.0.0.1/a', 'https://user:pass@podcast.org/feed']) {
    const backup = makeBackup(library());
    backup.library.episodes[0].audioUrl = invalid;
    assert.throws(() => validateBackup(backup), /audio URL/);
  }
  const polluted = JSON.parse(JSON.stringify(makeBackup(library())).replace('"library":{', '"library":{"__proto__":{"polluted":true},'));
  assert.throws(() => validateBackup(polluted), /Prototype-related/);
  assert.equal({}.polluted, undefined);
  const custom = makeBackup(library());
  custom.library.feeds[0] = Object.assign(Object.create({hidden: true}), custom.library.feeds[0]);
  assert.throws(() => validateBackup(custom), /Custom object prototypes/);
  const getter = makeBackup(library());
  Object.defineProperty(getter.library, 'secret', {enumerable: true, get() { throw new Error('Getter executed'); }});
  assert.throws(() => validateBackup(getter), /Accessor properties/);
});

test('plain text remains plain data; unknown fields are removed', () => {
  const backup = makeBackup(library());
  const payload = '<script>alert(1)</script><img src=x onerror=alert(2)>';
  backup.library.episodes[0].description = payload;
  backup.library.episodes[0].onclick = 'alert(3)';
  backup.library.settings.secret = 'not imported';
  const validated = validateBackup(backup);
  assert.equal(validated.episodes[0].description, payload);
  assert.equal(validated.episodes[0].onclick, undefined);
  assert.equal(validated.settings.secret, undefined);
});

test('invalid references, duplicates and out-of-range user state fail atomically', () => {
  const mutations = [
    state => { state.episodes[0].feedId = 'missing'; },
    state => { state.episodes[0].lists = ['missing']; },
    state => { state.queue = ['missing']; },
    state => { state.feeds.push({...state.feeds[0]}); },
    state => { state.episodes[0].stars = 5.5; },
    state => { state.episodes[0].bookmarks[0].position = -1; },
    state => { state.settings.speed = 0.5; },
    state => { state.settings.country = '<>'; },
    state => { state.episodes[0].id = '<script>'; },
    state => { state.votes['https://other.org/rss'].vote = 2; },
  ];
  for (const mutate of mutations) {
    const backup = makeBackup(library());
    mutate(backup.library);
    assert.throws(() => validateBackup(backup), /Invalid backup/);
  }
});

test('caps and shared pseudo-feed are validated', () => {
  const state = emptyLibrary();
  state.feeds.push({id: 'shared', url: '', name: 'Shared episodes', subscribed: false, updated: 0, artworkUrl: '', error: ''});
  assert.equal(makeBackup(state).library.feeds[0].id, 'shared');
  const standalone = library();
  standalone.episodes[0].feedId = '';
  standalone.episodes[0].published = Date.now() + 100000000;
  assert.equal(makeBackup(standalone).library.episodes[0].feedId, '');
  const tooMany = makeBackup(emptyLibrary());
  tooMany.library.feeds = new Array(1001).fill({});
  assert.throws(() => validateBackup(tooMany), /at most 1,000/);
  const tooLong = makeBackup(library());
  tooLong.library.episodes[0].description = 'a'.repeat(1024 * 1024 + 1);
  assert.throws(() => validateBackup(tooLong), /episode description/);
});

test('unavailable browser storage rejects explicitly without pretending to save', async () => {
  await assert.rejects(loadLibrary(), /storage is unavailable/);
  await assert.rejects(saveLibrary(library()), /storage is unavailable/);
  await assert.rejects(saveLibrary(library()), /storage is unavailable/, 'A failed save does not poison later attempts');
});

test('saves snapshot immediately, commit in order, and recover after a rejected transaction', async () => {
  const pending = [];
  let stored;
  const original = globalThis.indexedDB;
  const database = {
    objectStoreNames: {contains: name => name === 'library'},
    close() {},
    transaction(name, mode) {
      assert.equal(name, 'library');
      const transaction = {
        abort() { transaction.onabort(); },
        objectStore() {
          return {
            put(value, key) {
              assert.equal(key, 'current');
              const request = {};
              pending.push({value, finish(fail = false) {
                if (fail) { transaction.error = new Error('Quota exceeded'); transaction.onabort(); }
                else { stored = structuredClone(value); request.onsuccess?.(); transaction.oncomplete(); }
              }});
              return request;
            },
            get(key) {
              assert.equal(key, 'current');
              const request = {};
              queueMicrotask(() => { request.result = structuredClone(stored); request.onsuccess(); if (mode === 'readonly') transaction.oncomplete(); });
              return request;
            },
          };
        },
      };
      return transaction;
    },
  };
  globalThis.indexedDB = {
    open(name, version) {
      assert.equal(name, 'pod4me-web');
      assert.equal(version, 1);
      const request = {result: database};
      queueMicrotask(() => request.onsuccess());
      return request;
    },
  };
  const tick = () => new Promise(resolve => setImmediate(resolve));
  try {
    const state = library();
    const first = saveLibrary(state);
    state.feeds[0].name = 'Second snapshot';
    const second = saveLibrary(state);
    state.feeds[0].name = 'Later unsaved mutation';
    await tick();
    assert.equal(pending.length, 1, 'The second transaction waits for first commit');
    assert.equal(pending[0].value.library.feeds[0].name, 'Custom name');
    assert.equal(pending[0].value.revision, 1);
    pending.shift().finish();
    await first;
    await tick();
    assert.equal(pending.length, 1);
    assert.equal(pending[0].value.library.feeds[0].name, 'Second snapshot');
    assert.equal(pending[0].value.revision, 2);
    pending.shift().finish();
    await second;
    assert.equal((await loadLibrary()).feeds[0].name, 'Second snapshot');
    const rejected = saveLibrary(state);
    const rejection = assert.rejects(rejected, /could not be saved/);
    const recovery = saveLibrary(library());
    await tick();
    pending.shift().finish(true);
    await rejection;
    await tick();
    pending.shift().finish();
    await recovery;
    assert.equal((await loadLibrary()).feeds[0].name, 'Custom name');
    assert.equal(stored.revision, 3);
    // Another tab commits without sending a BroadcastChannel message. The
    // database transaction itself must prevent this stale whole-library save.
    stored = {revision: 4, library: {...library(), settings: {speed: 2, country: 'gb'}}};
    await assert.rejects(saveLibrary(library()), error => error.code === 'STALE_LIBRARY');
    assert.equal(stored.revision, 4);
    assert.equal(stored.library.settings.country, 'gb');
    assert.equal(pending.length, 0, 'Stale transaction never writes a snapshot');
    assert.equal((await loadLibrary()).settings.speed, 2);
    const afterReload = saveLibrary(library());
    await tick();
    assert.equal(pending[0].value.revision, 5);
    pending.shift().finish();
    await afterReload;
    // Existing databases from the initial unwrapped format migrate on save.
    stored = library();
    assert.equal((await loadLibrary()).feeds[0].name, 'Custom name');
    const upgrade = saveLibrary(library());
    await tick();
    assert.equal(pending[0].value.revision, 1);
    pending.shift().finish();
    await upgrade;
  } finally {
    database.onversionchange?.();
    if (original === undefined) delete globalThis.indexedDB;
    else globalThis.indexedDB = original;
  }
});
