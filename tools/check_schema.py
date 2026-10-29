#!/usr/bin/env python3
"""Exercise the app's real SQLite schema without an Android SDK."""
import pathlib
import sqlite3
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]

class SchemaTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.executescript((ROOT / "app/src/main/assets/schema.sql").read_text())
        self.db.execute("INSERT INTO feeds(id,url,name,expiry_days) VALUES('f','https://example.com/rss','Feed',30)")
        self.db.execute("INSERT INTO episodes(id,feed_id,guid,title,audio_url,description,published) VALUES('e','f','g','Episode','https://example.com/e.mp3','',0)")

    def tearDown(self):
        self.db.close()

    def test_system_lists(self):
        self.assertEqual({"unheard", "heard", "4ever"}, {r[0] for r in self.db.execute("SELECT id FROM lists WHERE system=1")})

    def test_list_deletion_keeps_episode_and_other_memberships(self):
        self.db.execute("INSERT INTO lists VALUES('custom','Keep reading',0)")
        self.db.executemany("INSERT INTO memberships VALUES('e',?)", [("custom",), ("4ever",)])
        self.db.execute("INSERT INTO feed_defaults VALUES('f','custom')")
        self.db.execute("DELETE FROM lists WHERE id='custom'")
        self.assertEqual(1, self.db.execute("SELECT COUNT(*) FROM episodes").fetchone()[0])
        self.assertEqual([("4ever",)], self.db.execute("SELECT list_id FROM memberships").fetchall())
        self.assertEqual(0, self.db.execute("SELECT COUNT(*) FROM feed_defaults").fetchone()[0])

    def test_audio_removal_preserves_history(self):
        self.db.execute("UPDATE episodes SET path='/audio',downloaded=123,expiry=456,stars=5,position=100,heard=1 WHERE id='e'")
        self.db.execute("INSERT INTO bookmarks(episode_id,position,name) VALUES('e',10,'Moment')")
        self.db.execute("UPDATE episodes SET path=NULL,downloaded=NULL,expiry=NULL,download_id=NULL WHERE id='e'")
        self.assertEqual((5, 100, 1), self.db.execute("SELECT stars,position,heard FROM episodes").fetchone())
        self.assertEqual(1, self.db.execute("SELECT COUNT(*) FROM bookmarks").fetchone()[0])

    def test_feed_guid_and_name_uniqueness(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO lists VALUES('dup','UNHEARD',0)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO episodes(id,feed_id,guid,title,audio_url,description,published) VALUES('e2','f','g','Other','url','',0)")

    def test_queue_is_unique_and_ratings_are_bounded(self):
        self.db.execute("INSERT OR IGNORE INTO queue VALUES('e',(SELECT COALESCE(MAX(ordinal),0)+1 FROM queue))")
        self.db.execute("INSERT OR IGNORE INTO queue VALUES('e',(SELECT COALESCE(MAX(ordinal),0)+1 FROM queue))")
        self.assertEqual(1, self.db.execute("SELECT COUNT(*) FROM queue").fetchone()[0])
        for invalid in (-1, 6):
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute("UPDATE episodes SET stars=?", (invalid,))

    def test_foreign_keys_reject_orphans(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO memberships VALUES('missing','4ever')")

    def test_android_xml_is_well_formed(self):
        for path in (ROOT / "app/src/main").rglob("*.xml"):
            ET.parse(path)

    def test_artwork_migration_preserves_existing_library(self):
        with sqlite3.connect(":memory:") as old:
            old.execute("PRAGMA foreign_keys=ON")
            old.executescript("CREATE TABLE feeds(id TEXT PRIMARY KEY, url TEXT NOT NULL UNIQUE, name TEXT NOT NULL, expiry_days INTEGER, error TEXT);" +
                             (ROOT / "app/src/main/assets/schema.sql").read_text().partition(";")[2])
            old.execute("INSERT INTO feeds VALUES('old','https://example.com/old','My name',30,NULL)")
            old.execute("INSERT INTO episodes(id,feed_id,guid,title,audio_url,description,published,stars,position,path) VALUES('ep','old','g','Saved','https://example.com/audio','',0,5,42000,'/saved/audio')")
            old.execute("INSERT INTO memberships VALUES('ep','4ever')")
            old.executescript((ROOT / "app/src/main/assets/migration_1_2.sql").read_text())
            self.assertEqual(('My name', None), old.execute("SELECT name,artwork_url FROM feeds").fetchone())
            self.assertEqual((5, 42000, '/saved/audio'), old.execute("SELECT stars,position,path FROM episodes").fetchone())
            self.assertEqual([('ep', '4ever')], old.execute("SELECT * FROM memberships").fetchall())
            old.execute("UPDATE feeds SET artwork_url='https://example.com/cover.jpg'")
            self.assertEqual('https://example.com/cover.jpg', old.execute("SELECT artwork_url FROM feeds").fetchone()[0])

if __name__ == "__main__":
    unittest.main(verbosity=2)
