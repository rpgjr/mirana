package com.rpgjr.mirana.service.project;

import com.rpgjr.mirana.enums.Priority;
import com.rpgjr.mirana.enums.TicketType;

import com.rpgjr.mirana.exception.ApiException;
import com.rpgjr.mirana.service.github.GithubConnectService;

import com.rpgjr.mirana.model.dto.project.ProjectDtos.*;
import com.rpgjr.mirana.model.dto.project.TicketFormat;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProjectService {

    private static final String[][] DEFAULT_COLUMNS = {
            {"Backlog", "BACKLOG"}, {"To do", "TODO"}, {"In progress", "IN_PROGRESS"}, {"Done", "DONE"}};

    private final JdbcClient db;
    private final GithubConnectService github;
    private final TicketFormatReader formats;

    public ProjectService(JdbcClient db, GithubConnectService github, TicketFormatReader formats) {
        this.db = db;
        this.github = github;
        this.formats = formats;
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    // ---------- projects ----------

    public List<ProjectSummary> list(Long userId) {
        return db.sql("""
                select p.id, p.name, p.slug, m.role,
                       (select count(*) from ticket t where t.project_id = p.id) as tickets
                from project p join project_member m on m.project_id = p.id
                where m.user_id = ? order by p.name""")
                .param(userId)
                .query((rs, i) -> new ProjectSummary(rs.getLong("id"), rs.getString("name"), rs.getString("slug"),
                        List.of(), rs.getString("role"), rs.getInt("tickets")))
                .list().stream().map(this::withRepos).toList();
    }

    private ProjectSummary withRepos(ProjectSummary p) {
        List<String> repos = db.sql("select repo from project_repo where project_id = ? order by repo")
                .param(p.id()).query(String.class).list();
        return new ProjectSummary(p.id(), p.name(), p.slug(), repos, p.role(), p.ticketCount());
    }

    static String normalizeRepo(String repo) {
        return repo.trim().toLowerCase(Locale.ROOT);
    }

    public ProjectSummary create(Long userId, String name, List<String> repoNames) {
        List<String> repos = (repoNames == null ? List.<String>of() : repoNames).stream()
                .filter(r -> r != null && !r.isBlank()).map(ProjectService::normalizeRepo).distinct().toList();
        repos.forEach(this::requireRepoFree);
        repos.forEach(r -> github.requireAccess(userId, r));
        String slug = uniqueSlug(name);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        db.sql("insert into project (name, slug, created_by, created_at) values (?, ?, ?, ?)")
                .params(name.trim(), slug, userId, now()).update(keys);
        long projectId = keys.getKey().longValue();
        repos.forEach(r -> db.sql("insert into project_repo (project_id, repo) values (?, ?)").params(projectId, r).update());
        db.sql("insert into project_member (project_id, user_id, role, joined_at) values (?, ?, 'OWNER', ?)")
                .params(projectId, userId, now()).update();
        for (int i = 0; i < DEFAULT_COLUMNS.length; i++) {
            db.sql("insert into board_column (project_id, name, position, status) values (?, ?, ?, ?)")
                    .params(projectId, DEFAULT_COLUMNS[i][0], i, DEFAULT_COLUMNS[i][1]).update();
        }
        for (TicketType t : TicketType.values()) {
            db.sql("insert into ticket_counter (project_id, prefix, last_number) values (?, ?, 0)")
                    .params(projectId, t.prefix()).update();
        }
        return new ProjectSummary(projectId, name.trim(), slug, repos, "OWNER", 0);
    }

    private String uniqueSlug(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isEmpty()) {
            base = "project";
        }
        String slug = base;
        for (int n = 2; db.sql("select count(*) from project where slug = ?").param(slug).query(Integer.class).single() > 0; n++) {
            slug = base + "-" + n;
        }
        return slug;
    }

    /** Membership gate. Returns 404 for both missing and forbidden projects so slugs do not leak. */
    ProjectSummary requireMember(String slug, Long userId) {
        return db.sql("""
                select p.id, p.name, p.slug, m.role,
                       (select count(*) from ticket t where t.project_id = p.id) as tickets
                from project p join project_member m on m.project_id = p.id
                where p.slug = ? and m.user_id = ?""")
                .params(slug, userId)
                .query((rs, i) -> new ProjectSummary(rs.getLong("id"), rs.getString("name"), rs.getString("slug"),
                        List.of(), rs.getString("role"), rs.getInt("tickets")))
                .optional()
                .map(this::withRepos)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Project not found."));
    }

    /** Changes the key format and renames every existing ticket to match. Numbers never change. */
    public TicketFormat setTicketFormat(String slug, Long userId, UpdateTicketFormatRequest r) {
        ProjectSummary project = requireOwner(slug, userId);
        TicketFormat f = new TicketFormat(r.digits(), r.featurePrefix().toUpperCase(Locale.ROOT),
                r.hotfixPrefix().toUpperCase(Locale.ROOT), r.bugfixPrefix().toUpperCase(Locale.ROOT));
        if (f.featurePrefix().equals(f.hotfixPrefix()) || f.featurePrefix().equals(f.bugfixPrefix()) || f.hotfixPrefix().equals(f.bugfixPrefix())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_PREFIX", "Each ticket type needs its own prefix.",
                    Map.of("featurePrefix", "Each ticket type needs its own prefix."));
        }
        db.sql("update project set key_digits = ?, prefix_feature = ?, prefix_hotfix = ?, prefix_bugfix = ? where id = ?")
                .params(f.digits(), f.featurePrefix(), f.hotfixPrefix(), f.bugfixPrefix(), project.id()).update();
        // Two steps, because a swap such as FT to HF and HF to FT would otherwise collide on the unique key.
        db.sql("update ticket set ticket_key = concat('~', id) where project_id = ?").param(project.id()).update();
        for (TicketType t : TicketType.values()) {
            db.sql("update ticket set ticket_key = concat(?, '-', lpad(number, ?, '0')) where project_id = ? and type = ?")
                    .params(f.prefix(t), f.digits(), project.id(), t.name()).update();
        }
        return f;
    }

    /** Renames the project. The slug stays the same so links and bookmarks keep working. */
    public ProjectSummary rename(String slug, Long userId, String name) {
        ProjectSummary project = requireOwner(slug, userId);
        db.sql("update project set name = ? where id = ?").params(name.trim(), project.id()).update();
        return requireMember(slug, userId);
    }

    /** Replaces the whole repository list in one step. Only repositories that are new to the project are checked. */
    public List<String> setRepos(String slug, Long userId, List<String> repoNames) {
        ProjectSummary project = requireOwner(slug, userId);
        List<String> wanted = (repoNames == null ? List.<String>of() : repoNames).stream()
                .filter(r -> r != null && !r.isBlank()).map(ProjectService::normalizeRepo).distinct().toList();
        if (wanted.size() > 10) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_REPOS", "A project can have at most 10 repositories.",
                    Map.of("repos", "A project can have at most 10 repositories."));
        }
        List<String> current = project.repos();
        List<String> added = wanted.stream().filter(r -> !current.contains(r)).toList();
        added.forEach(this::requireRepoFree);
        added.forEach(r -> github.requireAccess(userId, r));
        current.stream().filter(r -> !wanted.contains(r)).forEach(r ->
                db.sql("delete from project_repo where project_id = ? and repo = ?").params(project.id(), r).update());
        added.forEach(r -> db.sql("insert into project_repo (project_id, repo) values (?, ?)").params(project.id(), r).update());
        return db.sql("select repo from project_repo where project_id = ? order by repo")
                .param(project.id()).query(String.class).list();
    }

    public List<String> addRepo(String slug, Long userId, String repoName) {
        ProjectSummary project = requireOwner(slug, userId);
        String repo = normalizeRepo(repoName);
        if (project.repos().size() >= 10) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_REPOS", "A project can have at most 10 repositories.",
                    Map.of("repo", "A project can have at most 10 repositories."));
        }
        requireRepoFree(repo);
        github.requireAccess(userId, repo);
        db.sql("insert into project_repo (project_id, repo) values (?, ?)").params(project.id(), repo).update();
        return db.sql("select repo from project_repo where project_id = ? order by repo")
                .param(project.id()).query(String.class).list();
    }

    public List<String> removeRepo(String slug, Long userId, String repoName) {
        ProjectSummary project = requireOwner(slug, userId);
        db.sql("delete from project_repo where project_id = ? and repo = ?")
                .params(project.id(), normalizeRepo(repoName)).update();
        return db.sql("select repo from project_repo where project_id = ? order by repo")
                .param(project.id()).query(String.class).list();
    }

    private ProjectSummary requireOwner(String slug, Long userId) {
        ProjectSummary project = requireMember(slug, userId);
        if (!"OWNER".equals(project.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the project owner can change project settings.");
        }
        return project;
    }

    private void requireRepoFree(String repo) {
        if (db.sql("select count(*) from project_repo where repo = ?").param(repo).query(Integer.class).single() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "REPO_TAKEN", "That repository already belongs to a project.",
                    Map.of("repo", "That repository already belongs to a project."));
        }
    }

    public List<Member> members(Long projectId) {
        return db.sql("""
                select u.id, u.display_name, u.email, m.role from project_member m
                join app_user u on u.id = m.user_id where m.project_id = ? order by u.display_name""")
                .param(projectId)
                .query((rs, i) -> new Member(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list();
    }

    // ---------- board ----------

    public Board board(String slug, Long userId) {
        ProjectSummary project = requireMember(slug, userId);
        Map<Long, Column> columns = new LinkedHashMap<>();
        db.sql("select id, name, status, position from board_column where project_id = ? order by position")
                .param(project.id())
                .query((rs, i) -> new Column(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), new ArrayList<>()))
                .list().forEach(c -> columns.put(c.id(), c));
        db.sql("""
                select t.id, t.ticket_key, t.type, t.title, t.priority, t.column_id, t.assignee_id, a.display_name,
                       (select count(*) from ticket_pr_link l where l.ticket_id = t.id) as prs,
                       (select l.state from ticket_pr_link l where l.ticket_id = t.id order by l.updated_at desc, l.id desc limit 1) as pr_state,
                       (select count(*) from ticket_comment c where c.ticket_id = t.id) as comments
                from ticket t left join app_user a on a.id = t.assignee_id
                where t.project_id = ? order by t.column_id, t.position, t.id""")
                .param(project.id())
                .query((rs, i) -> {
                    Long assignee = rs.getObject("assignee_id") == null ? null : rs.getLong("assignee_id");
                    TicketCard card = new TicketCard(rs.getLong("id"), rs.getString("ticket_key"),
                            TicketType.valueOf(rs.getString("type")), rs.getString("title"),
                            Priority.valueOf(rs.getString("priority")), assignee, rs.getString("display_name"),
                            rs.getInt("prs"), rs.getString("pr_state"), rs.getInt("comments"));
                    Column col = columns.get(rs.getLong("column_id"));
                    if (col != null) {
                        col.tickets().add(card);
                    }
                    return card;
                }).list();
        return new Board(project, new ArrayList<>(columns.values()), members(project.id()), formats.load(project.id()));
    }

    // ---------- tickets ----------

    public TicketDetail createTicket(String slug, Long userId, CreateTicketRequest r) {
        ProjectSummary project = requireMember(slug, userId);
        long columnId = r.columnId() != null ? requireColumn(project.id(), r.columnId())
                : db.sql("select id from board_column where project_id = ? and status = 'BACKLOG' order by position limit 1")
                        .param(project.id()).query(Long.class).single();
        if (r.assigneeId() != null) {
            requireProjectMember(project.id(), r.assigneeId());
        }
        String prefix = r.type().prefix();
        int number = db.sql("select last_number from ticket_counter where project_id = ? and prefix = ? for update")
                .params(project.id(), prefix).query(Integer.class).optional().map(n -> n + 1).orElse(1);
        db.sql("""
                insert into ticket_counter (project_id, prefix, last_number) values (?, ?, ?)
                on duplicate key update last_number = values(last_number)""")
                .params(project.id(), prefix, number).update();
        int position = db.sql("select coalesce(max(position) + 1, 0) from ticket where column_id = ?")
                .param(columnId).query(Integer.class).single();
        String key = formats.load(project.id()).key(r.type(), number);
        Timestamp ts = now();
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        db.sql("""
                insert into ticket (project_id, type, number, ticket_key, title, description, column_id, position,
                                    priority, assignee_id, created_by, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(project.id(), r.type().name(), number, key, r.title().trim(), blankToNull(r.description()), columnId, position,
                        (r.priority() == null ? Priority.MEDIUM : r.priority()).name(), r.assigneeId(), userId, ts, ts)
                .update(keys);
        event(keys.getKey().longValue(), userId, "USER", "CREATED", null);
        return ticket(slug, key, userId);
    }

    public TicketDetail ticket(String slug, String key, Long userId) {
        ProjectSummary project = requireMember(slug, userId);
        return loadDetail(project.id(), key);
    }

    private TicketDetail loadDetail(Long projectId, String key) {
        var base = db.sql("""
                select t.id, t.ticket_key, t.type, t.title, t.description, t.priority, t.column_id, c.name as column_name,
                       c.status, t.assignee_id, a.display_name as assignee, u.display_name as creator, t.created_at, t.updated_at
                from ticket t join board_column c on c.id = t.column_id
                left join app_user a on a.id = t.assignee_id left join app_user u on u.id = t.created_by
                where t.project_id = ? and t.ticket_key = ?""")
                .params(projectId, key)
                .query((rs, i) -> new Object[] {rs.getLong("id"), rs.getString("ticket_key"), rs.getString("type"),
                        rs.getString("title"), rs.getString("description"), rs.getString("priority"), rs.getLong("column_id"),
                        rs.getString("column_name"), rs.getString("status"),
                        rs.getObject("assignee_id") == null ? null : rs.getLong("assignee_id"), rs.getString("assignee"),
                        rs.getString("creator"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()})
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Ticket not found."));
        Long id = (Long) base[0];
        List<PrLink> prs = db.sql("select id, repo, pr_number, branch, title, url, state from ticket_pr_link where ticket_id = ? order by pr_number desc")
                .param(id).query((rs, i) -> new PrLink(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7))).list();
        List<Comment> comments = db.sql("""
                select c.id, c.author_id, u.display_name, c.body, c.created_at from ticket_comment c
                join app_user u on u.id = c.author_id where c.ticket_id = ? order by c.created_at, c.id""")
                .param(id).query((rs, i) -> new Comment(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant())).list();
        List<Event> events = db.sql("""
                select e.id, e.actor_type, u.display_name, e.event_type, json_unquote(json_extract(e.payload, '$.detail')), e.created_at from ticket_event e
                left join app_user u on u.id = e.actor_id where e.ticket_id = ? order by e.created_at desc, e.id desc limit 50""")
                .param(id).query((rs, i) -> new Event(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getTimestamp(6).toInstant())).list();
        return new TicketDetail(id, (String) base[1], TicketType.valueOf((String) base[2]), (String) base[3], (String) base[4],
                Priority.valueOf((String) base[5]), (Long) base[6], (String) base[7], (String) base[8], (Long) base[9],
                (String) base[10], (String) base[11], (java.time.Instant) base[12], (java.time.Instant) base[13], prs, comments, events);
    }

    public TicketDetail update(String slug, String key, Long userId, UpdateTicketRequest r) {
        ProjectSummary project = requireMember(slug, userId);
        TicketDetail cur = loadDetail(project.id(), key);
        if (r.title() != null && !r.title().isBlank() && !r.title().equals(cur.title())) {
            db.sql("update ticket set title = ?, updated_at = ? where id = ?").params(r.title().trim(), now(), cur.id()).update();
            event(cur.id(), userId, "USER", "TITLE_CHANGED", null);
        }
        if (r.description() != null && !r.description().equals(cur.description())) {
            db.sql("update ticket set description = ?, updated_at = ? where id = ?").params(blankToNull(r.description()), now(), cur.id()).update();
            event(cur.id(), userId, "USER", "DESCRIPTION_CHANGED", null);
        }
        if (r.priority() != null && r.priority() != cur.priority()) {
            db.sql("update ticket set priority = ?, updated_at = ? where id = ?").params(r.priority().name(), now(), cur.id()).update();
            event(cur.id(), userId, "USER", "PRIORITY_CHANGED", cur.priority() + " to " + r.priority());
        }
        if (Boolean.TRUE.equals(r.clearAssignee()) && cur.assigneeId() != null) {
            db.sql("update ticket set assignee_id = null, updated_at = ? where id = ?").params(now(), cur.id()).update();
            event(cur.id(), userId, "USER", "UNASSIGNED", null);
        } else if (r.assigneeId() != null && !r.assigneeId().equals(cur.assigneeId())) {
            requireProjectMember(project.id(), r.assigneeId());
            db.sql("update ticket set assignee_id = ?, updated_at = ? where id = ?").params(r.assigneeId(), now(), cur.id()).update();
            event(cur.id(), userId, "USER", "ASSIGNED", db.sql("select display_name from app_user where id = ?").param(r.assigneeId()).query(String.class).single());
        }
        return loadDetail(project.id(), key);
    }

    public TicketDetail move(String slug, String key, Long userId, MoveRequest r) {
        ProjectSummary project = requireMember(slug, userId);
        TicketDetail cur = loadDetail(project.id(), key);
        requireColumn(project.id(), r.columnId());
        List<Long> ids = new ArrayList<>(db.sql("select id from ticket where column_id = ? and id <> ? order by position, id")
                .params(r.columnId(), cur.id()).query(Long.class).list());
        ids.add(Math.max(0, Math.min(r.position(), ids.size())), cur.id());
        db.sql("update ticket set column_id = ?, updated_at = ? where id = ?").params(r.columnId(), now(), cur.id()).update();
        for (int i = 0; i < ids.size(); i++) {
            db.sql("update ticket set position = ? where id = ?").params(i, ids.get(i)).update();
        }
        if (!r.columnId().equals(cur.columnId())) {
            String to = db.sql("select name from board_column where id = ?").param(r.columnId()).query(String.class).single();
            event(cur.id(), userId, "USER", "MOVED", cur.columnName() + " to " + to);
        }
        return loadDetail(project.id(), key);
    }

    public TicketDetail comment(String slug, String key, Long userId, String body) {
        ProjectSummary project = requireMember(slug, userId);
        TicketDetail cur = loadDetail(project.id(), key);
        db.sql("insert into ticket_comment (ticket_id, author_id, body, created_at) values (?, ?, ?, ?)")
                .params(cur.id(), userId, body.trim(), now()).update();
        return loadDetail(project.id(), key);
    }

    // ---------- helpers ----------

    private long requireColumn(Long projectId, Long columnId) {
        return db.sql("select id from board_column where id = ? and project_id = ?").params(columnId, projectId)
                .query(Long.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "BAD_COLUMN", "That column is not on this board."));
    }

    private void requireProjectMember(Long projectId, Long userId) {
        Optional<Integer> n = db.sql("select count(*) from project_member where project_id = ? and user_id = ?")
                .params(projectId, userId).query(Integer.class).optional();
        if (n.orElse(0) == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_ASSIGNEE", "Only project members can be assigned.");
        }
    }

    void event(Long ticketId, Long actorId, String actorType, String type, String detail) {
        String payload = detail == null ? null : "{\"detail\":" + jsonString(detail) + "}";
        db.sql("insert into ticket_event (ticket_id, actor_id, actor_type, event_type, payload, created_at) values (?, ?, ?, ?, ?, ?)")
                .params(ticketId, actorId, actorType, type, payload, now()).update();
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
