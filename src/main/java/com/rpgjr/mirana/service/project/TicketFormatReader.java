package com.rpgjr.mirana.service.project;

import com.rpgjr.mirana.model.dto.project.TicketFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Reads a project's ticket key format. Kept apart so the webhook and GitHub services need not depend on ProjectService. */
@Service
public class TicketFormatReader {

    private final JdbcClient db;

    public TicketFormatReader(JdbcClient db) {
        this.db = db;
    }

    public TicketFormat load(long projectId) {
        return db.sql("select key_digits, key_prefix from project where id = ?")
                .param(projectId)
                .query((rs, i) -> new TicketFormat(rs.getInt(1), rs.getString(2)))
                .optional().orElse(TicketFormat.DEFAULT);
    }

    /** Format of the project that tracks this repository, or the default when none does. */
    public TicketFormat forRepo(String repoLower) {
        return db.sql("select p.key_digits, p.key_prefix from project p join project_repo r on r.project_id = p.id where r.repo = ?")
                .param(repoLower)
                .query((rs, i) -> new TicketFormat(rs.getInt(1), rs.getString(2)))
                .optional().orElse(TicketFormat.DEFAULT);
    }
}
