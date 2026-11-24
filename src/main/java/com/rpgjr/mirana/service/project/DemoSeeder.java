package com.rpgjr.mirana.service.project;

import com.rpgjr.mirana.model.dto.project.ProjectDtos;

import com.rpgjr.mirana.model.dto.project.ProjectDtos.CreateTicketRequest;
import com.rpgjr.mirana.model.dto.project.ProjectDtos.MoveRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Local demo data for one account. Runs only when MIRANA_SEED_EMAIL is set and that user has no projects. */
@Component
@ConditionalOnProperty(name = "mirana.seed-email")
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final JdbcClient db;
    private final ProjectService projects;
    private final TransactionTemplate tx;
    private final String email;

    public DemoSeeder(JdbcClient db, ProjectService projects, TransactionTemplate tx,
            @org.springframework.beans.factory.annotation.Value("${mirana.seed-email}") String email) {
        this.db = db;
        this.projects = projects;
        this.tx = tx;
        this.email = email;
    }

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(s -> seed());
    }

    private void seed() {
        if (email.isBlank()) {
            return;
        }
        var user = db.sql("select id from app_user where email = ?").param(email).query(Long.class).optional();
        if (user.isEmpty()) {
            log.info("Seed skipped: no user {}", email);
            return;
        }
        long uid = user.get();
        if (db.sql("select count(*) from project_member where user_id = ?").param(uid).query(Integer.class).single() > 0) {
            return;
        }
        var hop = projects.create(uid, "hophop", List.of("rpgjr/hophop", "rpgjr/hophop-ui"));
        var mep = projects.create(uid, "mepmep", List.of("rpgjr/mepmep", "rpgjr/mepmep-ui"));
        long hopId = hop.id();

        t(hop.slug(), uid, "Profile page with avatar upload", "Let riders edit their name and photo. Crop to a square and store the file in object storage.", "Medium", "Backlog", 0);
        t(hop.slug(), uid, "Date picker shows the wrong month on Safari", "Opening the picker on Safari 17 starts one month late when the device timezone is behind UTC.", "High", "Backlog", 0);
        t(hop.slug(), uid, "Add forgot password flow", "Email a 6 digit code, then ask for a new password. Reuse the OTP component.", "High", "On hold", 0);
        String login = t(hop.slug(), uid, "Login page redesign", "Move the login form to the new dark layout. Add show password, inline errors that name the field, and keep paste enabled for password managers.", "Urgent", "In progress", 0);
        String webhook = t(hop.slug(), uid, "Payment webhook returns 500 on retry", "The provider retries a failed delivery and we throw on the duplicate event id. Make the handler idempotent.", "Urgent", "In progress", 1);
        String session = t(hop.slug(), uid, "Session expires too early", "Users are signed out after 30 minutes even while active. Extend the idle timeout and refresh on activity.", "Medium", "Done", 0);
        t(mep.slug(), uid, "Import contacts from CSV", "Upload a CSV, map columns, preview the first 20 rows, then import.", "Medium", "In review", 0);
        t(mep.slug(), uid, "Export button does nothing on empty list", "Disable the button and show a hint when there is nothing to export.", "Low", "Backlog", 0);

        pr(hopId, login, "rpgjr/hophop", 42, login + "-login-page", "Redesign login page", "OPEN");
        pr(hopId, login, "rpgjr/hophop", 40, login + "-input-component", "Add shared Input component", "MERGED");
        pr(hopId, webhook, "rpgjr/hophop", 44, webhook + "-webhook-idempotent", "Make payment webhook idempotent", "OPEN");
        pr(hopId, session, "rpgjr/hophop", 39, session + "-session-timeout", "Extend session timeout", "MERGED");

        db.sql("insert into ticket_comment (ticket_id, author_id, body, created_at) select id, ?, ?, ? from ticket where project_id = ? and ticket_key = ?")
                .params(uid, "Design is approved. Ship the new layout behind the same route so the old page stays as a fallback for a day.",
                        Timestamp.from(Instant.now().minus(20, ChronoUnit.HOURS)), hopId, login).update();
        log.warn("Seeded demo projects hophop and mepmep for {}", email);
    }

    private String t(String slug, long uid, String title, String desc, String priority, String column, int position) {
        Long colId = db.sql("select c.id from board_column c join project pr on pr.id = c.project_id where pr.slug = ? and c.name = ?")
                .params(slug, column).query(Long.class).single();
        Long priorityId = db.sql("select tp.id from ticket_priority tp join project pr on pr.id = tp.project_id where pr.slug = ? and tp.name = ?")
                .params(slug, priority).query(Long.class).single();
        var d = projects.createTicket(slug, uid, new CreateTicketRequest(title, desc, priorityId, List.of(uid), colId, null));
        projects.move(slug, d.key(), uid, new MoveRequest(colId, position));
        return d.key();
    }

    private void pr(long projectId, String key, String repo, int number, String branch, String title, String state) {
        db.sql("""
                insert into ticket_pr_link (ticket_id, repo, pr_number, branch, title, url, state, updated_at)
                select id, ?, ?, ?, ?, ?, ?, ? from ticket where project_id = ? and ticket_key = ?""")
                .params(repo, number, branch, title, "https://github.com/" + repo + "/pull/" + number, state,
                        Timestamp.from(Instant.now().minus(number % 5, ChronoUnit.HOURS)), projectId, key).update();
        List.of(key).forEach(k -> db.sql("""
                insert into ticket_event (ticket_id, actor_type, event_type, payload, created_at)
                select id, 'GITHUB', ?, json_object('detail', ?), ? from ticket where project_id = ? and ticket_key = ?""")
                .params("PR_" + state, "#" + number + " " + branch, Timestamp.from(Instant.now().minus(number % 7, ChronoUnit.HOURS)), projectId, k).update());
    }
}
