package com.rpgjr.mirana.service.github;

import com.rpgjr.mirana.service.project.TicketFormatReader;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
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
    /** Only people with a role on the repository can link a PR to a ticket; anyone can open a PR on a public repo. */
    private static final Set<String> TRUSTED_AUTHORS = Set.of("OWNER", "MEMBER", "COLLABORATOR");
    private static final Set<String> PR_ACTIONS = Set.of("opened", "reopened", "edited", "synchronize", "closed", "ready_for_review");

    private final JdbcClient db;
    private final TicketFormatReader formats;
    private final JsonMapper json = JsonMapper.builder().build();

    public GithubWebhookService(JdbcClient db, TicketFormatReader formats) {
        this.db = db;
        this.formats = formats;
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
        } else if ("push".equals(event)) {
            push(json.readTree(body));
        }
        db.sql("update webhook_delivery set processed_at = ? where delivery_id = ?")
                .params(Timestamp.from(Instant.now()), deliveryId).update();
    }

    /**
     * Records each commit of a push on the tickets named in the branch or in the commit message. The event detail is
     * three parts split by a tab: the short sha, the first line of the message and the author.
     */
    private void push(JsonNode root) {
        String ref = root.path("ref").asString("");
        String repo = root.path("repository").path("full_name").asString("").toLowerCase(java.util.Locale.ROOT);
        if (!ref.startsWith("refs/heads/") || root.path("deleted").asBoolean(false) || repo.isBlank()) {
            return;
        }
        Long projectId = db.sql("select project_id from project_repo where repo = ?").param(repo).query(Long.class).optional().orElse(null);
        if (projectId == null) {
            return;
        }
        String branch = ref.substring("refs/heads/".length());
        var format = formats.load(projectId);
        int seen = 0;
        for (JsonNode c : root.path("commits")) {
            if (++seen > 20) {
                break;
            }
            String sha = c.path("id").asString("");
            String message = c.path("message").asString("");
            String first = cut(message.lines().findFirst().orElse(""), 200);
            Set<String> keys = new LinkedHashSet<>(format.find(branch));
            keys.addAll(format.find(message));
            for (String key : keys) {
                Long ticketId = db.sql("select id from ticket where project_id = ? and ticket_key = ?")
                        .params(projectId, key).query(Long.class).optional().orElse(null);
                if (ticketId == null || sha.length() < 7) {
                    continue;
                }
                String short7 = sha.substring(0, 7);
                int already = db.sql("select count(*) from ticket_event where ticket_id = ? and event_type = 'COMMIT' and json_unquote(json_extract(payload, '$.sha')) = ?")
                        .params(ticketId, short7).query(Integer.class).single();
                if (already > 0) {
                    continue;
                }
                String detail = short7 + "\t" + first + "\t" + cut(c.path("author").path("name").asString(""), 100);
                String commitUrl = c.path("url").asString("");
                var payload = new java.util.LinkedHashMap<String, String>();
                payload.put("detail", detail);
                payload.put("sha", short7);
                payload.put("branch", cut(branch, 250));
                payload.put("repo", repo);
                if (commitUrl.startsWith("https://github.com/")) {
                    payload.put("url", cut(commitUrl, 500));
                }
                db.sql("insert into ticket_event (ticket_id, actor_type, event_type, payload, created_at) values (?, 'GITHUB', 'COMMIT', ?, ?)")
                        .params(ticketId, json.writeValueAsString(payload), Timestamp.from(Instant.now()))
                        .update();
            }
        }
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
        var format = formats.load(projectId);
        for (String text : new String[] {branch, title, pr.path("body").asString("")}) {
            keys.addAll(format.find(text));
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
                var payload = new java.util.LinkedHashMap<String, String>();
                payload.put("detail", "#" + number + " " + state.toLowerCase(Locale.ROOT));
                payload.put("repo", repo);
                payload.put("branch", cut(branch, 250));
                if (url != null) {
                    payload.put("url", cut(url, 500));
                }
                db.sql("insert into ticket_event (ticket_id, actor_type, event_type, payload, created_at) values (?, 'GITHUB', ?, ?, ?)")
                        .params(ticketId, before == null ? "PR_LINKED" : "PR_" + state, json.writeValueAsString(payload), Timestamp.from(Instant.now()))
                        .update();
            }
            log.info("PR {}#{} linked to {} ({})", repo, number, key, state);
        }
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
