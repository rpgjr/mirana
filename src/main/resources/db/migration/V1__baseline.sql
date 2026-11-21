-- Users and authentication

CREATE TABLE app_user (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    email             VARCHAR(254) NOT NULL,
    email_verified_at DATETIME(3)  NULL,
    display_name      VARCHAR(100) NOT NULL,
    avatar_url        VARCHAR(500) NULL,
    github_id         BIGINT       NULL,
    github_login      VARCHAR(100) NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_email (email),
    UNIQUE KEY uk_app_user_github_id (github_id)
);

CREATE TABLE user_credential (
    user_id             BIGINT       NOT NULL,
    password_hash       VARCHAR(255) NOT NULL,
    password_changed_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    failed_attempts     INT          NOT NULL DEFAULT 0,
    locked_until        DATETIME(3)  NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_credential_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

CREATE TABLE email_otp (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    purpose     ENUM ('VERIFY_EMAIL', 'RESET_PASSWORD', 'NEW_DEVICE') NOT NULL,
    code_hash   CHAR(64)    NOT NULL,
    expires_at  DATETIME(3) NOT NULL,
    attempts    INT         NOT NULL DEFAULT 0,
    consumed_at DATETIME(3) NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_email_otp_user_purpose (user_id, purpose, created_at),
    CONSTRAINT fk_otp_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

CREATE TABLE api_token (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    name         VARCHAR(100) NOT NULL,
    token_hash   CHAR(64)     NOT NULL,
    last_used_at DATETIME(3)  NULL,
    revoked_at   DATETIME(3)  NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_token_hash (token_hash),
    CONSTRAINT fk_api_token_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

-- Projects, membership and invites

CREATE TABLE project (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    slug        VARCHAR(100) NOT NULL,
    github_repo VARCHAR(200) NULL,
    created_by  BIGINT       NOT NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_slug (slug),
    UNIQUE KEY uk_project_github_repo (github_repo),
    CONSTRAINT fk_project_creator FOREIGN KEY (created_by) REFERENCES app_user (id)
);

CREATE TABLE project_member (
    project_id BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    role       ENUM ('OWNER', 'MEMBER') NOT NULL DEFAULT 'MEMBER',
    joined_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (project_id, user_id),
    KEY ix_member_user (user_id),
    CONSTRAINT fk_member_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

CREATE TABLE project_invite (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    project_id  BIGINT       NOT NULL,
    email       VARCHAR(254) NOT NULL,
    role        ENUM ('OWNER', 'MEMBER') NOT NULL DEFAULT 'MEMBER',
    token_hash  CHAR(64)     NOT NULL,
    invited_by  BIGINT       NOT NULL,
    expires_at  DATETIME(3)  NOT NULL,
    accepted_at DATETIME(3)  NULL,
    revoked_at  DATETIME(3)  NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_invite_token (token_hash),
    KEY ix_invite_email (email),
    KEY ix_invite_project (project_id),
    CONSTRAINT fk_invite_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT fk_invite_inviter FOREIGN KEY (invited_by) REFERENCES app_user (id)
);

-- Board and tickets

CREATE TABLE board_column (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    project_id BIGINT      NOT NULL,
    name       VARCHAR(60) NOT NULL,
    position   INT         NOT NULL,
    status     ENUM ('BACKLOG', 'TODO', 'IN_PROGRESS', 'DONE') NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_column_position (project_id, position),
    CONSTRAINT fk_column_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE
);

-- One row per project and prefix. Increment inside the ticket-create transaction.
CREATE TABLE ticket_counter (
    project_id  BIGINT      NOT NULL,
    prefix      VARCHAR(10) NOT NULL,
    last_number INT         NOT NULL DEFAULT 100,
    PRIMARY KEY (project_id, prefix),
    CONSTRAINT fk_counter_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE
);

CREATE TABLE ticket (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    project_id  BIGINT       NOT NULL,
    type        ENUM ('FEATURE', 'HOTFIX', 'BUGFIX') NOT NULL,
    number      INT          NOT NULL,
    ticket_key  VARCHAR(20)  NOT NULL,
    title       VARCHAR(200) NOT NULL,
    description TEXT         NULL,
    column_id   BIGINT       NOT NULL,
    position    INT          NOT NULL DEFAULT 0,
    priority    ENUM ('LOW', 'MEDIUM', 'HIGH', 'URGENT') NOT NULL DEFAULT 'MEDIUM',
    assignee_id BIGINT       NULL,
    created_by  BIGINT       NOT NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_key (project_id, ticket_key),
    KEY ix_ticket_column (column_id, position),
    KEY ix_ticket_assignee (assignee_id),
    CONSTRAINT fk_ticket_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT fk_ticket_column FOREIGN KEY (column_id) REFERENCES board_column (id),
    CONSTRAINT fk_ticket_assignee FOREIGN KEY (assignee_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_ticket_creator FOREIGN KEY (created_by) REFERENCES app_user (id)
);

CREATE TABLE ticket_pr_link (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    ticket_id  BIGINT       NOT NULL,
    repo       VARCHAR(200) NOT NULL,
    pr_number  INT          NOT NULL,
    branch     VARCHAR(250) NULL,
    title      VARCHAR(300) NULL,
    url        VARCHAR(500) NULL,
    state      ENUM ('OPEN', 'CLOSED', 'MERGED') NOT NULL DEFAULT 'OPEN',
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pr_link (ticket_id, repo, pr_number),
    KEY ix_pr_repo_number (repo, pr_number),
    CONSTRAINT fk_pr_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id) ON DELETE CASCADE
);

CREATE TABLE ticket_comment (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    ticket_id  BIGINT      NOT NULL,
    author_id  BIGINT      NOT NULL,
    body       TEXT        NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_comment_ticket (ticket_id, created_at),
    CONSTRAINT fk_comment_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_author FOREIGN KEY (author_id) REFERENCES app_user (id)
);

CREATE TABLE ticket_event (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    ticket_id  BIGINT      NOT NULL,
    actor_id   BIGINT      NULL,
    actor_type ENUM ('USER', 'CLAUDE', 'GITHUB', 'SYSTEM') NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload    JSON        NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_event_ticket (ticket_id, created_at),
    CONSTRAINT fk_event_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id) ON DELETE CASCADE,
    CONSTRAINT fk_event_actor FOREIGN KEY (actor_id) REFERENCES app_user (id) ON DELETE SET NULL
);

-- GitHub webhook replay protection

CREATE TABLE webhook_delivery (
    delivery_id  VARCHAR(64) NOT NULL,
    event        VARCHAR(50) NOT NULL,
    received_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    processed_at DATETIME(3) NULL,
    PRIMARY KEY (delivery_id)
);
