-- A project can cover several GitHub repositories (for example a backend and a frontend).
-- Repo names are stored lower-case because GitHub treats them case-insensitively
-- and TiDB compares strings case-sensitively by default.
CREATE TABLE project_repo (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    project_id BIGINT       NOT NULL,
    repo       VARCHAR(200) NOT NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_repo_repo (repo),
    KEY ix_project_repo_project (project_id),
    CONSTRAINT fk_project_repo_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE
);

INSERT INTO project_repo (project_id, repo)
SELECT id, LOWER(github_repo) FROM project WHERE github_repo IS NOT NULL;

ALTER TABLE project DROP INDEX uk_project_github_repo;
ALTER TABLE project DROP COLUMN github_repo;
