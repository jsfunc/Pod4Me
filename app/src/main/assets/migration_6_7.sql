ALTER TABLE episodes ADD COLUMN byte_size INTEGER CHECK(byte_size IS NULL OR byte_size>0);
