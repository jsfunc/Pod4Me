import {emptyLibrary, safeUrl} from './domain.mjs';

const DB_NAME = 'pod4me-web';
const STORE_NAME = 'library';
const CURRENT_KEY = 'current';
let databasePromise;
let writeTail = Promise.resolve();
let expectedRevision = 0;

function storageError(message, cause) {
  const error = new Error(message);
  if (cause) error.cause = cause;
  return error;
}

function openDatabase() {
  if (databasePromise) return databasePromise;
  databasePromise = new Promise((resolve, reject) => {
    if (!globalThis.indexedDB) {
      reject(storageError('Browser storage is unavailable. Changes cannot be saved in this browser.'));
      return;
    }
    let request;
    try { request = indexedDB.open(DB_NAME, 1); }
    catch (error) { reject(storageError('Browser storage could not be opened.', error)); return; }
    let abandoned = false;
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE_NAME)) request.result.createObjectStore(STORE_NAME);
    };
    request.onblocked = () => {
      abandoned = true;
      reject(storageError('Storage is blocked by another Pod4Me tab. Close other tabs and reload.'));
    };
    request.onerror = () => reject(storageError('Browser storage could not be opened.', request.error));
    request.onsuccess = () => {
      const database = request.result;
      if (abandoned) { database.close(); return; }
      database.onversionchange = () => { database.close(); databasePromise = undefined; };
      database.onclose = () => { databasePromise = undefined; };
      resolve(database);
    };
  }).catch(error => { databasePromise = undefined; throw error; });
  return databasePromise;
}

function transactionValue(database, mode, operation) {
  return new Promise((resolve, reject) => {
    let transaction, value;
    try {
      transaction = database.transaction(STORE_NAME, mode);
      const request = operation(transaction.objectStore(STORE_NAME));
      request.onsuccess = () => { value = request.result; };
      request.onerror = () => { /* The transaction abort reports the failure. */ };
      transaction.oncomplete = () => resolve(value);
      transaction.onabort = () => reject(storageError(mode === 'readonly' ? 'Your library could not be read from browser storage.' : 'Your changes could not be saved. Browser storage may be full or unavailable. Export a backup before closing this page.', transaction.error || request.error));
      transaction.onerror = () => { /* onabort is the definitive transaction result. */ };
    } catch (error) {
      reject(storageError(mode === 'readonly' ? 'Your library could not be read from browser storage.' : 'Your changes could not be saved to browser storage.', error));
    }
  });
}

export async function loadLibrary() {
  await writeTail;
  const database = await openDatabase();
  const stored = await transactionValue(database, 'readonly', store => store.get(CURRENT_KEY));
  try {
    const record = storedRecord(stored);
    const library = record.library === undefined ? emptyLibrary() : validateLibrary(record.library);
    expectedRevision = record.revision;
    return library;
  }
  catch (error) { throw storageError(`The saved library could not be read safely: ${error.message} Existing browser data has not been overwritten.`, error); }
}

function storedRecord(stored) {
  if (stored === undefined) return {revision: 0, library: undefined};
  // The initial web version wrote the library directly. Its first conditional
  // write upgrades the record in place, preserving all subscription history.
  if (stored && stored.version === 1 && stored.revision === undefined) return {revision: 0, library: stored};
  if (!stored || !Number.isSafeInteger(stored.revision) || stored.revision < 0 || !stored.library) throw storageError('The browser storage revision is invalid.');
  return stored;
}

function commitSnapshot(database, snapshot) {
  return new Promise((resolve, reject) => {
    let transaction, failure, nextRevision;
    try {
      transaction = database.transaction(STORE_NAME, 'readwrite');
      const store = transaction.objectStore(STORE_NAME);
      const read = store.get(CURRENT_KEY);
      read.onsuccess = () => {
        try {
          const record = storedRecord(read.result);
          if (record.revision !== expectedRevision) {
            failure = storageError('Your library changed in another tab. Reload this tab before making more changes.');
            failure.code = 'STALE_LIBRARY';
            transaction.abort();
            return;
          }
          nextRevision = expectedRevision + 1;
          if (!Number.isSafeInteger(nextRevision)) throw storageError('The browser storage revision is out of range.');
          store.put({revision: nextRevision, library: snapshot}, CURRENT_KEY);
        } catch (error) { failure = error; transaction.abort(); }
      };
      transaction.oncomplete = () => { expectedRevision = nextRevision; resolve(); };
      transaction.onabort = () => reject(failure || storageError('Your changes could not be saved. Browser storage may be full or unavailable. Export a backup before closing this page.', transaction.error || read.error));
      transaction.onerror = () => { /* onabort reports the definitive failure. */ };
    } catch (error) { reject(storageError('Your changes could not be saved to browser storage.', error)); }
  });
}

// Take the snapshot before waiting for earlier writes. Every caller receives its
// own failure, while a failed transaction does not prevent a later save attempt.
export function saveLibrary(state) {
  let snapshot;
  try { snapshot = validateLibrary(state); }
  catch (error) { return Promise.reject(storageError(`Your library could not be prepared for saving: ${error.message}`, error)); }
  const write = writeTail.then(async () => {
    const database = await openDatabase();
    await commitSnapshot(database, snapshot);
  });
  writeTail = write.catch(() => {});
  return write;
}

function fail(message) { throw new Error(`Invalid backup: ${message}`); }
function record(value, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(`${label} must be an object.`);
  return value;
}
function array(value, label, maximum, fallback = []) {
  if (value === undefined) return fallback;
  if (!Array.isArray(value) || value.length > maximum) fail(`${label} must be a list of at most ${maximum.toLocaleString('en-US')} items.`);
  return value;
}
function text(value, label, maximum, fallback = '') {
  if (value === undefined) return fallback;
  if (typeof value !== 'string' || value.length > maximum) fail(`${label} must be text of at most ${maximum.toLocaleString('en-US')} characters.`);
  return value.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, '');
}
function id(value, label) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$/.test(value)) fail(`${label} is not a valid identifier.`);
  return value;
}
function number(value, label, maximum = 31536000, fallback = 0) {
  if (value === undefined) return fallback;
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > maximum) fail(`${label} is out of range.`);
  return value;
}
function boolean(value, label, fallback = false) {
  if (value === undefined) return fallback;
  if (typeof value !== 'boolean') fail(`${label} must be true or false.`);
  return value;
}
function url(value, label, allowEmpty = false) {
  if (allowEmpty && (value === undefined || value === '')) return '';
  try { return safeUrl(text(value, label, 16384)); }
  catch { fail(`${label} must be a public HTTP or HTTPS address.`); }
}
function timestamp(value, label) { return number(value, label, 8640000000000000); }
function unique(values, label) {
  const set = new Set(values);
  if (set.size !== values.length) fail(`${label} contains duplicate identifiers.`);
  return set;
}

// Check the complete input, including ignored fields, before whitelisting. JSON
// imports cannot sneak prototype-related properties into later object merges.
function inspectInput(input) {
  let nodes = 0, characters = 0;
  const ancestors = new Set();
  function visit(value, depth) {
    if (++nodes > 5000000 || depth > 20) fail('The file is too complex.');
    if (typeof value === 'string') { characters += value.length; if (characters > 64 * 1024 * 1024) fail('The file contains too much text.'); return; }
    if (value === null || typeof value === 'boolean' || typeof value === 'number' && Number.isFinite(value)) return;
    if (!value || typeof value !== 'object') fail('Only JSON data is supported.');
    const prototype = Object.getPrototypeOf(value);
    if (!Array.isArray(value) && prototype !== Object.prototype && prototype !== null) fail('Custom object prototypes are not supported.');
    if (ancestors.has(value)) fail('Circular objects are not supported.');
    ancestors.add(value);
    for (const key of Object.keys(value)) {
      if (['__proto__', 'prototype', 'constructor'].includes(key)) fail('Prototype-related properties are not supported.');
      const descriptor = Object.getOwnPropertyDescriptor(value, key);
      if (descriptor.get || descriptor.set) fail('Accessor properties are not supported.');
      visit(descriptor.value, depth + 1);
    }
    ancestors.delete(value);
  }
  visit(input, 0);
}

function validateLibrary(input, inspected = false) {
  if (!inspected) inspectInput(input);
  const source = record(input, 'library');
  if (source.version !== 1) fail('Unsupported library version.');
  const output = emptyLibrary();
  output.lists = array(source.lists, 'lists', 1000).map(item => {
    record(item, 'list');
    return {id: id(item.id, 'list ID'), name: text(item.name, 'list name', 500)};
  });
  const listIds = unique(output.lists.map(item => item.id), 'lists');
  output.feeds = array(source.feeds, 'feeds', 1000).map(item => {
    record(item, 'feed');
    const feedId = id(item.id, 'feed ID');
    return {id: feedId, url: url(item.url, 'feed URL', feedId === 'shared'), name: text(item.name, 'feed name', 2000),
      subscribed: boolean(item.subscribed, 'subscription status'), updated: timestamp(item.updated, 'feed update date'),
      artworkUrl: url(item.artworkUrl, 'feed artwork URL', true), error: text(item.error, 'feed error', 10000)};
  });
  const feedIds = unique(output.feeds.map(item => item.id), 'feeds');
  unique(output.feeds.filter(item => item.url).map(item => item.url), 'feed URLs');
  let totalBookmarks = 0;
  output.episodes = array(source.episodes, 'episodes', 100000).map(item => {
    record(item, 'episode');
    const feedId = item.feedId === '' ? '' : id(item.feedId, 'episode feed ID');
    if (feedId && !feedIds.has(feedId)) fail('An episode refers to an unknown feed.');
    const memberships = array(item.lists, 'episode lists', 1000).map(value => id(value, 'list membership'));
    unique(memberships, 'episode lists');
    if (memberships.some(value => !listIds.has(value))) fail('An episode belongs to an unknown custom list.');
    const bookmarks = array(item.bookmarks, 'bookmarks', 1000).map(bookmark => {
      record(bookmark, 'bookmark');
      return {id: id(bookmark.id, 'bookmark ID'), position: number(bookmark.position, 'bookmark position'), label: text(bookmark.label, 'bookmark label', 2000)};
    });
    totalBookmarks += bookmarks.length;
    if (totalBookmarks > 100000) fail('There are too many bookmarks.');
    unique(bookmarks.map(bookmark => bookmark.id), 'episode bookmarks');
    const stars = number(item.stars, 'episode stars', 5);
    if (!Number.isInteger(stars)) fail('Episode stars must be a whole number.');
    return {id: id(item.id, 'episode ID'), feedId, key: text(item.key, 'episode key', 16384), title: text(item.title, 'episode title', 4000),
      audioUrl: url(item.audioUrl, 'episode audio URL'), published: timestamp(item.published, 'publication date'),
      duration: number(item.duration, 'episode duration'), description: text(item.description, 'episode description', 1024 * 1024),
      artworkUrl: url(item.artworkUrl, 'episode artwork URL', true), heard: boolean(item.heard, 'heard status'), stars,
      lists: memberships, bookmarks, position: number(item.position, 'playback position'), isNew: boolean(item.isNew, 'new episode status'),
      forever: boolean(item.forever, 'forever status')};
  });
  const episodeIds = unique(output.episodes.map(item => item.id), 'episodes');
  output.queue = array(source.queue, 'queue', 100000).map(value => id(value, 'queue entry'));
  unique(output.queue, 'queue');
  if (output.queue.some(value => !episodeIds.has(value))) fail('The queue contains an unknown episode.');
  for (const field of ['recent', 'previous']) {
    output[field] = [...new Set(array(source[field], field, 10000).map(value => url(typeof value === 'string' ? value : record(value, 'suggestion history').url || value.feedUrl, 'suggestion history URL')))];
  }
  const votes = source.votes === undefined ? {} : record(source.votes, 'suggestion votes');
  if (Object.keys(votes).length > 10000) fail('There are too many suggestion votes.');
  for (const [key, value] of Object.entries(votes)) {
    const address = url(key, 'suggestion vote URL');
    const vote = typeof value === 'number' ? value : record(value, 'suggestion vote').vote ?? value.value;
    if (![-1, 0, 1].includes(vote)) fail('A suggestion vote must be -1, 0 or 1.');
    if (typeof value === 'number') { output.votes[address] = vote; continue; }
    const metadata = {vote};
    for (const field of ['name', 'title', 'artist', 'artistName', 'author']) if (value[field] !== undefined) metadata[field] = text(value[field], `suggestion ${field}`, 2000);
    if (value.genres !== undefined) metadata.genres = array(value.genres, 'suggestion genres', 100).map(genre => text(genre, 'genre', 200));
    output.votes[address] = metadata;
  }
  const settings = source.settings === undefined ? {} : record(source.settings, 'settings');
  const speed = number(settings.speed, 'playback speed', 3, 1);
  if (speed < 1) fail('Playback speed must be between 1 and 3.');
  const country = text(settings.country, 'country', 2, 'fr').toLowerCase();
  if (!/^[a-z]{2}$/.test(country)) fail('Country must be a two-letter code.');
  output.settings = {speed, country};
  return output;
}

export function validateBackup(parsed) {
  inspectInput(parsed);
  record(parsed, 'backup');
  if (parsed.format !== 'pod4me-web' || parsed.version !== 1) fail('This is not a supported Pod4Me web backup. Android feed-list files can be imported separately.');
  return validateLibrary(parsed.library, true);
}

export function makeBackup(state) {
  return {format: 'pod4me-web', version: 1, library: validateLibrary(state)};
}
