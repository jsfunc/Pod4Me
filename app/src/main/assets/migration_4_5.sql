ALTER TABLE episodes ADD COLUMN new_in_last_refresh INTEGER NOT NULL DEFAULT 0 CHECK(new_in_last_refresh IN (0,1));
