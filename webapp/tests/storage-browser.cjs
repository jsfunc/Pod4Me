const assert = require('node:assert/strict');

module.exports = async function storageChecks(browser, base) {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    await page.goto(base + '/tests/parser.html');
    const result = await page.evaluate(async () => {
      const check = (value, message) => { if (!value) throw new Error(message); };
      const {emptyLibrary, mergeFeed} = await import('/app/domain.mjs');
      const initial = emptyLibrary();
      mergeFeed(initial, {url: 'https://podcast.org/rss', name: 'Show', episodes: [{key:'one', title:'One', audioUrl:'https://podcast.org/one.mp3', description:'Notes'}]});
      initial.episodes[0].position = 12;
      // Exercise the explicit v1 -> v2 upgrade on a real browser database.
      await new Promise((resolve,reject) => {
        const request = indexedDB.open('pod4me-web',1);
        request.onupgradeneeded = () => request.result.createObjectStore('library');
        request.onsuccess = () => {
          const db = request.result, tx = db.transaction('library','readwrite');
          tx.objectStore('library').put({revision:7,library:initial},'current');
          tx.oncomplete = () => {db.close();resolve();};
          tx.onabort = () => reject(tx.error);
        };
      });
      const storage = await import('/app/storage.mjs');
      let state = await storage.loadLibrary();
      check(state.episodes[0].position===12,'Migration lost progress');
      state.feeds[0].name='First snapshot';
      const save1=storage.saveLibrary(state);
      state.feeds[0].name='Second snapshot';
      const save2=storage.saveLibrary(state);
      state.feeds[0].name='Unsaved mutation';
      await Promise.all([save1,save2]);
      state=await storage.loadLibrary();
      check(state.feeds[0].name==='Second snapshot','Full saves were not snapshotted/ordered');
      const e = state.episodes[0];
      e.position=30;e.duration=100;e.heard=true;
      const first = storage.savePlayback(e);
      e.position=0;e.heard=false;
      const second = storage.savePlayback(e);
      await Promise.all([first,second]);
      state=await storage.loadLibrary();
      check(state.episodes[0].position===0&&!state.episodes[0].heard,'Queued reset lost');
      const stale = await import('/app/storage.mjs?other-tab');
      await stale.loadLibrary();
      state.episodes[0].position=40;
      await storage.savePlayback(state.episodes[0]);
      let rejected=false;
      try{await stale.savePlayback({...state.episodes[0],position:99});}catch(error){rejected=error.code==='STALE_LIBRARY';}
      check(rejected,'Stale progress accepted');
      rejected=false;
      try{await stale.saveLibrary(state);}catch(error){rejected=error.code==='STALE_LIBRARY';}
      check(rejected,'Stale full snapshot accepted');
      const backup=storage.makeBackup(await storage.loadLibrary());
      check(backup.library.episodes[0].position===40,'Backup omitted overlay');
      backup.library.episodes[0].position=5;
      await storage.saveLibrary(storage.validateBackup(backup));
      check((await storage.loadLibrary()).episodes[0].position===5,'Restore retained stale overlay');

      // Force a transaction abort, then verify atomicity and recovery.
      const put=IDBObjectStore.prototype.put;
      IDBObjectStore.prototype.put=function(value,...args){if(this.name==='library'&&args[0]==='revision')throw new DOMException('Full','QuotaExceededError');return put.call(this,value,...args);};
      try {await storage.savePlayback({...e,position:88});check(false,'Expected write failure');}
      catch(error){check(error.message.includes('could not be saved'),'Wrong storage error');}
      finally {IDBObjectStore.prototype.put=put;}
      check((await storage.loadLibrary()).episodes[0].position===5,'Aborted transaction changed progress');
      await storage.savePlayback({...e,position:42});
      check((await storage.loadLibrary()).episodes[0].position===42,'Writes did not recover');

      // Large-library saves must touch only one small progress record + revision.
      state=await storage.loadLibrary();
      state.episodes=Array.from({length:100000},(_,i)=>({...state.episodes[0],id:`e-${i}`,key:`key-${i}`,description:'Notes '.repeat(20),lists:[],bookmarks:[]}));
      await storage.saveLibrary(state);
      const writes=[],reads=[],get=IDBObjectStore.prototype.get;
      IDBObjectStore.prototype.put=function(value,...args){writes.push({store:this.name,size:JSON.stringify(value).length});return put.call(this,value,...args);};
      IDBObjectStore.prototype.get=function(key){reads.push(key);return get.call(this,key);};
      const started=performance.now();
      try {await storage.savePlayback({...state.episodes[99999],position:50});}
      finally {IDBObjectStore.prototype.put=put;IDBObjectStore.prototype.get=get;}
      const elapsed=performance.now()-started;
      check(reads.length===1&&reads[0]==='revision','Playback read the full library');
      check(writes.length===2&&writes.every(w=>w.size<200),'Playback rewrote the full library');
      check((await storage.loadLibrary()).episodes[99999].position===50,'Large library progress not saved');
      return {elapsed,writes,episodes:state.episodes.length};
    });
    assert.equal(result.episodes,100000);
    console.log('PASS real IndexedDB migration, ordered saves, reset, stale tabs, rollback, backup/restore and 100,000 episodes:',JSON.stringify(result));
  } finally {await context.close();}
};
