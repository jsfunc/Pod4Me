package com.pod4me.domain

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.pod4me.data.LibraryDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LibraryMigrationTest {
    @Test fun upgradesVersionOneWithoutLosingLibrary() { checkUpgrade(1) }
    @Test fun upgradesVersionTwoWithoutLosingLibrary() { checkUpgrade(2) }

    @Test fun upgradesVersionThreeWithoutLosingLibrary() { checkUpgrade(3) }
    @Test fun upgradesVersionFourWithoutMarkingExistingEpisodesNew() { checkUpgrade(4) }
    @Test fun upgradesVersionFivePreservingAudioAndHistory() { checkUpgrade(5) }

    @Test fun upgradesVersionSixPreservingAudioAndHistory() { checkUpgrade(6) }

    @Test fun upgradesVersionSevenPreservingLibrary() { checkUpgrade(7) }
    @Test fun upgradesVersionEightPreservingLibraryAndRefetchingMoveMetadata() { checkUpgrade(8) }
    @Test fun upgradesVersionNineWithAutomaticDownloadsDisabled() { checkUpgrade(9) }

    @Test fun versionEightCapsOnlyDownloadedHeardDeadlinesWithoutExtendingEarlierOnes() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("pod4me.db")
        val file = context.getDatabasePath("pod4me.db")
        file.parentFile!!.mkdirs()
        val now = System.currentTimeMillis()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE feeds(id TEXT)")
            db.execSQL("CREATE TABLE episodes(id TEXT, heard INTEGER, path TEXT, expiry INTEGER)")
            for ((id, heard, path, expiry) in listOf(
                listOf("long", 1, "/file", now + 7 * Policy.DAY), listOf("short", 1, "/file", now + Policy.DAY),
                listOf("forever", 1, "/file", null), listOf("unheard", 0, "/file", now + 30 * Policy.DAY),
                listOf("missing", 1, null, now + 7 * Policy.DAY))) {
                db.execSQL("INSERT INTO episodes VALUES(?,?,?,?)", arrayOf<Any?>(id, heard, path, expiry))
            }
            context.assets.open("migration_7_8.sql").bufferedReader().useLines { lines -> lines.filter(String::isNotBlank).forEach(db::execSQL) }
            db.rawQuery("SELECT id,expiry FROM episodes", null).use { rows ->
                while (rows.moveToNext()) {
                    val expiry = rows.getLong(1)
                    when (rows.getString(0)) {
                        "long", "forever" -> assertTrue(expiry in (now + 3 * Policy.DAY - 1000)..(System.currentTimeMillis() + 3 * Policy.DAY))
                        "short" -> assertEquals(now + Policy.DAY, expiry)
                        "unheard" -> assertEquals(now + 30 * Policy.DAY, expiry)
                        "missing" -> assertEquals(now + 7 * Policy.DAY, expiry)
                    }
                }
            }
            db.rawQuery("SELECT etag,last_modified,last_refreshed FROM feeds", null).close()
        }
    }

    private fun checkUpgrade(oldVersion: Int) {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("pod4me.db")
        val file = context.getDatabasePath("pod4me.db")
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val artwork = (if (oldVersion >= 2) ", artwork_url TEXT" else "") + (if (oldVersion >= 3) ", subscribed INTEGER NOT NULL DEFAULT 1" else "") +
                (if (oldVersion >= 8) ", etag TEXT, last_modified TEXT, last_refreshed INTEGER" else "") +
                (if (oldVersion >= 9) ", unsubscribed_move_url TEXT" else "")
            db.execSQL("CREATE TABLE feeds(id TEXT PRIMARY KEY, url TEXT NOT NULL UNIQUE, name TEXT NOT NULL, expiry_days INTEGER, error TEXT$artwork)")
            val rest = context.assets.open("schema.sql").bufferedReader().use { it.readText() }.substringAfter(';')
                .let { if (oldVersion < 5) it.replace(", new_in_last_refresh INTEGER NOT NULL DEFAULT 0 CHECK(new_in_last_refresh IN (0,1))", "") else it }
                .let { if (oldVersion < 6) it.replace(", is_video INTEGER NOT NULL DEFAULT 0 CHECK(is_video IN (0,1))", "") else it }
                .let { if (oldVersion < 7) it.replace(", byte_size INTEGER CHECK(byte_size IS NULL OR byte_size>0)", "") else it }
                .let { if (oldVersion < 4) it.replace(", manager_id INTEGER", "") else it }
                .replace(", auto_download_pending INTEGER NOT NULL DEFAULT 0 CHECK(auto_download_pending IN (0,1))", "")
                .replace(", downloaded_size INTEGER CHECK(downloaded_size IS NULL OR downloaded_size>0)", "")
            rest.split(';').map(String::trim).filter(String::isNotEmpty).forEach(db::execSQL)
            db.execSQL("INSERT INTO feeds(id,url,name,expiry_days) VALUES('f','https://example.com/feed','My name',30)")
            if (oldVersion >= 8) db.execSQL("UPDATE feeds SET etag='v1',last_modified='date',last_refreshed=123")
            db.execSQL("INSERT INTO episodes(id,feed_id,guid,title,audio_url,description,published,path,position,stars) VALUES('e','f','guid','Saved','https://example.com/audio','',0,'/audio',42000,5)")
            db.execSQL("INSERT INTO memberships VALUES('e','4ever')")
            db.execSQL("INSERT INTO bookmarks(episode_id,position,name) VALUES('e',1000,'Keep')")
            db.execSQL("INSERT INTO queue VALUES('e',0)")
            db.version = oldVersion
        }
        val helper = LibraryDatabase(context)
        try {
            val db = helper.writableDatabase
            assertEquals(10, db.version)
            db.rawQuery("SELECT etag,last_modified,last_refreshed,unsubscribed_move_url FROM feeds", null).use {
                assertTrue(it.moveToFirst()); assertTrue(it.isNull(3))
                if (oldVersion < 9) { assertTrue(it.isNull(0)); assertTrue(it.isNull(1)) }
                else { assertEquals("v1", it.getString(0)); assertEquals("date", it.getString(1)) }
                if (oldVersion >= 8) assertEquals(123L, it.getLong(2))
            }
            db.rawQuery("SELECT auto_download,auto_download_since FROM feeds", null).use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertTrue(it.isNull(1))
            }
            db.rawQuery("SELECT auto_download_pending,downloaded_size FROM episodes", null).use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertTrue(it.isNull(1))
            }
            db.rawQuery("SELECT name,subscribed,artwork_url FROM feeds", null).use {
                assertTrue(it.moveToFirst()); assertEquals("My name", it.getString(0)); assertEquals(1, it.getInt(1))
            }
            db.rawQuery("SELECT path,position,stars,new_in_last_refresh,is_video,byte_size FROM episodes", null).use {
                assertTrue(it.moveToFirst()); assertEquals("/audio", it.getString(0)); assertEquals(42000, it.getInt(1)); assertEquals(5, it.getInt(2))
                assertEquals(0, it.getInt(3))
                assertEquals(0, it.getInt(4)); assertTrue(it.isNull(5))
            }
            for (table in listOf("memberships", "bookmarks", "queue")) db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        } finally { helper.close() }
    }
}
