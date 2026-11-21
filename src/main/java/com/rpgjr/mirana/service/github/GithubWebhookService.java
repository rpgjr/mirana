package com.rpgjr.mirana.service.github;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional
public class GithubWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GithubWebhookService.class);
    private static final Pattern KEY = Pattern.compile("\\b(FT|HF|BUG)-(\\d{1,9})\\b", Pattern.CASE_INSENSITIVE);
    /** Only people with a role on the repository can link a PR to a ticket; anyone can open a PR on a public repo. */
    private static final Set<String> TRUSTED_AUTHORS = Set.of("OWNER", "MEMBER", "COLLABORATOR");
    private static final Set<String> PR_ACTIONS = Set.of("opened", "reopened", "edited", "synchronize", "closed", "ready_for_review");

    private final JdbcClient db;
    private final JsonMapper json = JsonMapper.builder().build();

    public GithubWebhookService(JdbcClient db) {
        this.db = db;
    }

    public void handle(String deliveryId, String event, byte[] body) {
        try {
            db.sql("insert into webhook_delivery (delivery_id, event, received_at) values (?, ?, ?)")
                    .params(deliveryId, event, Timestamp.from(Instant.now())).update();
        } catch (DuplicateKeyException replay) {
            return;
        }
        if ("pull_request".equals(event)) {
            pullRequest(json.readTree(body));
        }
        db.sql("update webhook_delivery set processed_at = ? where delivery_id = ?")
                .params(Timestamp.from(Instant.now()), deliveryId).update();
    }

    private void pullRequest(JsonNode root) {
        String action = root.path("action").asString("");
        JsonNode pr = root.path("pull_request");
        String repo = root.path("repository").path("full_name").asString("").toLowerCase(java.util.Locale.ROOT);
        if (!PR_ACTIONS.contains(action) || repo.isBlank() || pr.isMissingNode()) {
            return;
        }
        if (!TRUSTED_AUTHORS.contains(pr.path("author_association").asString(""))) {
            return;
        }
        Long projectId = db.sql("select project_id from project_repo where repo = ?").param(repo).query(Long.class).optional().orElse(null);
        if (projectId == null) {
            return;
        }
        int number = pr.path("number").asInt();
        String branch = pr.path("head").path("ref").asString("");
        String title = pr.path("title").asString("");
        String url = pr.path("html_url").asString("");
        if (!url.startsWith("https://github.com/")) {
            url = null;
        }
        String state = !"closed".equals(pr.path("state").asString("")) ? "OPEN" : pr.path("merged").asBoolean(false) ? "MERGED" : "CLOSED";

        Set<String> keys = new LinkedHashSet<>();
        for (String text : new String[] {branch, title, pr.path("body").asString("")}) {
            Matcher m = KEY.matcher(text);
            while (m.find()) {
                keys.add(m.group(1).toUpperCase(Locale.ROOT) + "-" + m.group(2));
            }
        }
        for (String key : keys) {
            Long ticketId = db.sql("select id from ticket where project_id = ? and ticket_key = ?")
                    .params(projectId, key).query(Long.class).optional().orElse(null);
            if (ticketId == null) {
                continue;
            }
            String before = db.sql("select state from ticket_pr_link where ticket_id = ? and repo = ? and pr_number = ?")
                    .params(ticketId, repo, number).query(String.class).optional().orElse(null);
            db.sql("""
                    insert into ticket_pr_link (ticket_id, repo, pr_number, branch, title, url, state, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    on duplicate key update branch = values(branch), title = values(title), url = values(url),
                                            state = values(state), updated_at = values(updated_at)""")
                    .params(ticketId, repo, number, cut(branch, 250), cut(title, 300), cut(url, 500), state, Timestamp.from(Instant.now()))
                    .update();
            if (!state.equals(before)) {
                String detail = "#" + number + " " + state.toLowerCase(Locale.ROOT);
                db.sql("insert into ticket_event (ticket_id, actor_type, event_type, payload, created_at) values (?, 'GITHUB', ?, ?, ?)")
                        .params(ticketId, before == null ? "PR_LINKED" : "PR_" + state, "{\"detail\":\"" + detail.replace("\"", "") + "\"}",
                                Timestamp.from(Instant.now()))
                        .update();
            }
            log.info("PR {}#{} linked to {} ({})", repo, number, key, state);
        }
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
