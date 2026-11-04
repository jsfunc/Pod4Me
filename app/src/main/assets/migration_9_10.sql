ALTER TABLE feeds ADD COLUMN auto_download INTEGER NOT NULL DEFAULT 0 CHECK(auto_download IN (0,1,2));
ALTER TABLE feeds ADD COLUMN auto_download_since INTEGER;
ALTER TABLE episodes ADD COLUMN auto_download_pending INTEGER NOT NULL DEFAULT 0 CHECK(auto_download_pending IN (0,1));
ALTER TABLE episodes ADD COLUMN downloaded_size INTEGER CHECK(downloaded_size IS NULL OR downloaded_size>0);
