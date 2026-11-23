-- Per project ticket key format: a prefix per ticket type and the number of digits, for example FT-00001.
ALTER TABLE project ADD COLUMN key_digits TINYINT NOT NULL DEFAULT 5;
ALTER TABLE project ADD COLUMN prefix_feature VARCHAR(6) NOT NULL DEFAULT 'FT';
ALTER TABLE project ADD COLUMN prefix_hotfix VARCHAR(6) NOT NULL DEFAULT 'HF';
ALTER TABLE project ADD COLUMN prefix_bugfix VARCHAR(6) NOT NULL DEFAULT 'BUG';
