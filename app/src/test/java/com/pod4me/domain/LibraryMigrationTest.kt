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

    private fun checkUpgrade(oldVersion: Int) {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("pod4me.db")
        val file = context.getDatabasePath("pod4me.db")
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val artwork = (if (oldVersion >= 2) ", artwork_url TEXT" else "") + (if (oldVersion >= 3) ", subscribed INTEGER NOT NULL DEFAULT 1" else "")
            db.execSQL("CREATE TABLE feeds(id TEXT PRIMARY KEY, url TEXT NOT NULL UNIQUE, name TEXT NOT NULL, expiry_days INTEGER, error TEXT$artwork)")
            val rest = context.assets.open("schema.sql").bufferedReader().use { it.readText() }.substringAfter(';')
                .replace(", new_in_last_refresh INTEGER NOT NULL DEFAULT 0 CHECK(new_in_last_refresh IN (0,1))", "")
                .let { if (oldVersion < 4) it.replace(", manager_id INTEGER", "") else it }
            rest.split(';').map(String::trim).filter(String::isNotEmpty).forEach(db::execSQL)
            db.execSQL("INSERT INTO feeds(id,url,name,expiry_days) VALUES('f','https://example.com/feed','My name',30)")
            db.execSQL("INSERT INTO episodes(id,feed_id,guid,title,audio_url,description,published,path,position,stars) VALUES('e','f','guid','Saved','https://example.com/audio','',0,'/audio',42000,5)")
            db.execSQL("INSERT INTO memberships VALUES('e','4ever')")
            db.execSQL("INSERT INTO bookmarks(episode_id,position,name) VALUES('e',1000,'Keep')")
            db.execSQL("INSERT INTO queue VALUES('e',0)")
            db.version = oldVersion
        }
        val helper = LibraryDatabase(context)
        try {
            val db = helper.writableDatabase
            assertEquals(5, db.version)
            db.rawQuery("SELECT name,subscribed,artwork_url FROM feeds", null).use {
                assertTrue(it.moveToFirst()); assertEquals("My name", it.getString(0)); assertEquals(1, it.getInt(1))
            }
            db.rawQuery("SELECT path,position,stars,new_in_last_refresh FROM episodes", null).use {
                assertTrue(it.moveToFirst()); assertEquals("/audio", it.getString(0)); assertEquals(42000, it.getInt(1)); assertEquals(5, it.getInt(2))
                assertEquals(0, it.getInt(3))
            }
            for (table in listOf("memberships", "bookmarks", "queue")) db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        } finally { helper.close() }
    }
}
