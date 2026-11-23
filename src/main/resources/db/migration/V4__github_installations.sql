-- GitHub App installations a user has proven they own (verified through GitHub OAuth).
CREATE TABLE github_installation (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    installation_id BIGINT       NOT NULL,
    account_login   VARCHAR(100) NOT NULL,
    account_type    VARCHAR(20)  NOT NULL,
    repo_selection  VARCHAR(20)  NOT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_github_installation PRIMARY KEY (id),
    CONSTRAINT uk_github_installation UNIQUE (user_id, installation_id),
    INDEX ix_github_installation_user (user_id),
    CONSTRAINT fk_github_installation_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);
