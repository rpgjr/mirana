-- Colors are stored as hex, so any color works. Priorities become an editable ordered list per project.
UPDATE board_column SET color = CASE color WHEN 'grey' THEN '#64748b' WHEN 'blue' THEN '#3b82f6' WHEN 'purple' THEN '#a855f7'
    WHEN 'amber' THEN '#f59e0b' WHEN 'red' THEN '#ef4444' WHEN 'green' THEN '#22c55e' WHEN 'pink' THEN '#ec4899' ELSE color END;
UPDATE project_tag SET color = CASE color WHEN 'grey' THEN '#64748b' WHEN 'blue' THEN '#3b82f6' WHEN 'purple' THEN '#a855f7'
    WHEN 'amber' THEN '#f59e0b' WHEN 'red' THEN '#ef4444' WHEN 'green' THEN '#22c55e' WHEN 'pink' THEN '#ec4899' ELSE color END;
UPDATE project_priority SET color = CASE color WHEN 'grey' THEN '#64748b' WHEN 'blue' THEN '#3b82f6' WHEN 'purple' THEN '#a855f7'
    WHEN 'amber' THEN '#f97316' WHEN 'red' THEN '#ef4444' WHEN 'green' THEN '#22c55e' WHEN 'pink' THEN '#ec4899' ELSE color END;

CREATE TABLE ticket_priority (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    project_id BIGINT      NOT NULL,
    name       VARCHAR(20) NOT NULL,
    color      VARCHAR(12) NOT NULL,
    position   INT         NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_priority_position (project_id, position),
    CONSTRAINT fk_ticket_priority_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE
);

INSERT INTO ticket_priority (project_id, name, color, position)
SELECT project_id, label, color, CASE level WHEN 'LOW' THEN 0 WHEN 'MEDIUM' THEN 1 WHEN 'HIGH' THEN 2 ELSE 3 END FROM project_priority;

INSERT INTO ticket_priority (project_id, name, color, position)
SELECT p.id, v.name, v.color, v.position
FROM project p
CROSS JOIN (SELECT 'Low' AS name, '#64748b' AS color, 0 AS position
            UNION ALL SELECT 'Medium', '#3b82f6', 1
            UNION ALL SELECT 'High', '#f97316', 2
            UNION ALL SELECT 'Urgent', '#ef4444', 3) v
WHERE NOT EXISTS (SELECT 1 FROM ticket_priority tp WHERE tp.project_id = p.id);

ALTER TABLE ticket ADD COLUMN priority_id BIGINT NULL;
UPDATE ticket t
    JOIN ticket_priority p ON p.project_id = t.project_id
        AND p.position = CASE t.priority WHEN 'LOW' THEN 0 WHEN 'MEDIUM' THEN 1 WHEN 'HIGH' THEN 2 ELSE 3 END
SET t.priority_id = p.id;
ALTER TABLE ticket MODIFY COLUMN priority_id BIGINT NOT NULL;
ALTER TABLE ticket ADD CONSTRAINT fk_ticket_priority FOREIGN KEY (priority_id) REFERENCES ticket_priority (id);
ALTER TABLE ticket DROP COLUMN priority;
DROP TABLE project_priority;
