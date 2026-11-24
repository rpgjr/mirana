-- A ticket can be assigned to several people.
CREATE TABLE ticket_assignee (
    ticket_id BIGINT NOT NULL,
    user_id   BIGINT NOT NULL,
    PRIMARY KEY (ticket_id, user_id),
    KEY ix_ticket_assignee_user (user_id),
    CONSTRAINT fk_ticket_assignee_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id) ON DELETE CASCADE,
    CONSTRAINT fk_ticket_assignee_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

INSERT INTO ticket_assignee (ticket_id, user_id) SELECT id, assignee_id FROM ticket WHERE assignee_id IS NOT NULL;

ALTER TABLE ticket DROP FOREIGN KEY fk_ticket_assignee;
ALTER TABLE ticket DROP INDEX ix_ticket_assignee;
ALTER TABLE ticket DROP COLUMN assignee_id;
