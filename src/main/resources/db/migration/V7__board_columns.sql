-- Board columns become: Backlog, On hold, In progress, In review, Done. Columns carry a color and can be managed per project.
ALTER TABLE board_column MODIFY COLUMN status ENUM ('BACKLOG', 'TODO', 'IN_PROGRESS', 'DONE', 'HOLD', 'REVIEW', 'CUSTOM') NOT NULL;
ALTER TABLE board_column ADD COLUMN color VARCHAR(12) NOT NULL DEFAULT 'grey';

UPDATE board_column SET name = 'On hold', status = 'HOLD' WHERE status = 'TODO';
UPDATE board_column SET position = position + 1 WHERE status = 'DONE';
INSERT INTO board_column (project_id, name, position, status, color)
SELECT p.id, 'In review', d.position - 1, 'REVIEW', 'purple'
FROM project p JOIN board_column d ON d.project_id = p.id AND d.status = 'DONE';

UPDATE board_column SET color = 'grey' WHERE status = 'BACKLOG';
UPDATE board_column SET color = 'amber' WHERE status = 'HOLD';
UPDATE board_column SET color = 'blue' WHERE status = 'IN_PROGRESS';
UPDATE board_column SET color = 'purple' WHERE status = 'REVIEW';
UPDATE board_column SET color = 'green' WHERE status = 'DONE';
