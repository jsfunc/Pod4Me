ALTER TABLE feeds ADD COLUMN etag TEXT;
ALTER TABLE feeds ADD COLUMN last_modified TEXT;
ALTER TABLE feeds ADD COLUMN last_refreshed INTEGER;
UPDATE episodes SET expiry=MIN(COALESCE(expiry,CAST(strftime('%s','now') AS INTEGER)*1000+259200000),CAST(strftime('%s','now') AS INTEGER)*1000+259200000) WHERE heard=1 AND path IS NOT NULL;
