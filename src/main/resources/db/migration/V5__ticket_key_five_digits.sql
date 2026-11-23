-- Ticket keys become FT-00001. Existing tickets are renumbered per project and type in creation order,
-- then the counters continue from the highest number.
UPDATE ticket t
    JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY project_id, type ORDER BY id) AS rn FROM ticket) r ON r.id = t.id
SET t.number     = r.rn,
    t.ticket_key = CONCAT(CASE t.type WHEN 'FEATURE' THEN 'FT' WHEN 'HOTFIX' THEN 'HF' ELSE 'BUG' END, '-', LPAD(r.rn, 5, '0'));

UPDATE ticket_counter c
SET c.last_number = COALESCE((SELECT MAX(t.number) FROM ticket t
                              WHERE t.project_id = c.project_id
                                AND t.type = CASE c.prefix WHEN 'FT' THEN 'FEATURE' WHEN 'HF' THEN 'HOTFIX' ELSE 'BUGFIX' END), 0);

ALTER TABLE ticket_counter ALTER COLUMN last_number SET DEFAULT 0;
