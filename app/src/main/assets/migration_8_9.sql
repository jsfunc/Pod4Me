ALTER TABLE feeds ADD COLUMN unsubscribed_move_url TEXT;
UPDATE feeds SET etag=NULL,last_modified=NULL;
