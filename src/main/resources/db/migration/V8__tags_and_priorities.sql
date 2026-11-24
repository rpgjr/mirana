-- Per project tags for tickets, and per project label and color for each priority level.
CREATE TABLE project_tag (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    project_id BIGINT      NOT NULL,
    name       VARCHAR(30) NOT NULL,
    color      VARCHAR(12) NOT NULL DEFAULT 'grey',
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_tag_name (project_id, name),
    CONSTRAINT fk_project_tag_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE
);

CREATE TABLE ticket_tag (
    ticket_id BIGINT NOT NULL,
    tag_id    BIGINT NOT NULL,
    PRIMARY KEY (ticket_id, tag_id),
    KEY ix_ticket_tag_tag (tag_id),
    CONSTRAINT fk_ticket_tag_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id) ON DELETE CASCADE,
    CONSTRAINT fk_ticket_tag_tag FOREIGN KEY (tag_id) REFERENCES project_tag (id) ON DELETE CASCADE
);

CREATE TABLE project_priority (
    project_id BIGINT      NOT NULL,
    level      VARCHAR(10) NOT NULL,
    label      VARCHAR(20) NOT NULL,
    color      VARCHAR(12) NOT NULL,
    PRIMARY KEY (project_id, level),
    CONSTRAINT fk_project_priority_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE
);

INSERT INTO project_priority (project_id, level, label, color)
SELECT p.id, v.level, v.label, v.color
FROM project p
CROSS JOIN (SELECT 'LOW' AS level, 'Low' AS label, 'grey' AS color
            UNION ALL SELECT 'MEDIUM', 'Medium', 'blue'
            UNION ALL SELECT 'HIGH', 'High', 'amber'
            UNION ALL SELECT 'URGENT', 'Urgent', 'red') v;
