ALTER TABLE service ADD COLUMN allows_art_level BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE service SET allows_art_level = TRUE WHERE id IN (1, 2, 5);