package com.rpgjr.mirana.utils;

import org.springframework.jdbc.core.JdbcTemplate;

/** Empties every table in foreign key order so tests never depend on each other's leftovers. */
public final class TestDb {

    private static final String[] TABLES = {"ticket_event", "ticket_comment", "ticket_pr_link", "ticket", "ticket_counter",
            "board_column", "project_invite", "project_member", "project", "SPRING_SESSION_ATTRIBUTES", "SPRING_SESSION",
            "github_installation", "trusted_device", "api_token", "webhook_delivery", "email_otp", "user_credential", "app_user"};

    private TestDb() {
    }

    public static void clean(JdbcTemplate jdbc) {
        for (String t : TABLES) {
            jdbc.update("delete from " + t);
        }
    }
}
