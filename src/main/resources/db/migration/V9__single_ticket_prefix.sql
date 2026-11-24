-- Ticket types are gone: tags say what a ticket is. A project now has one key prefix, and one number sequence.
ALTER TABLE project ADD COLUMN key_prefix VARCHAR(6) NOT NULL DEFAULT 'FT';
UPDATE project SET key_prefix = prefix_feature;
ALTER TABLE project DROP COLUMN prefix_feature;
ALTER TABLE project DROP COLUMN prefix_hotfix;
ALTER TABLE project DROP COLUMN prefix_bugfix;

-- Renumber every ticket per project in creation order. Temporary keys first, so the unique key never collides.
UPDATE ticket SET ticket_key = CONCAT('~', id);
UPDATE ticket t
    JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY project_id ORDER BY id) AS rn FROM ticket) r ON r.id = t.id
    JOIN project p ON p.id = t.project_id
SET t.number     = r.rn,
    t.ticket_key = CONCAT(p.key_prefix, '-', LPAD(r.rn, p.key_digits, '0'));

DELETE FROM ticket_counter WHERE prefix <> 'FT';
UPDATE ticket_counter c SET c.last_number = COALESCE((SELECT MAX(t.number) FROM ticket t WHERE t.project_id = c.project_id), 0);

ALTER TABLE ticket DROP COLUMN type;
