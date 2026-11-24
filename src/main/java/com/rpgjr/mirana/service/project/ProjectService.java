package com.rpgjr.mirana.service.project;


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
            {"Backlog", "BACKLOG", "#64748b"}, {"On hold", "HOLD", "#f59e0b"}, {"In progress", "IN_PROGRESS", "#3b82f6"},
            {"In review", "REVIEW", "#a855f7"}, {"Done", "DONE", "#22c55e"}};
    private static final String[][] DEFAULT_PRIORITIES = {
            {"Low", "#64748b"}, {"Medium", "#3b82f6"}, {"High", "#f97316"}, {"Urgent", "#ef4444"}};
    private static final int MAX_PRIORITIES = 8;
    /** The one number sequence of a project lives in this counter row. The key prefix the user sees is separate. */
    private static final String COUNTER = "FT";
    private static final int MAX_COLUMNS = 12;
    private static final int MAX_TAGS = 30;
    private static final int MAX_TICKET_TAGS = 8;

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
            db.sql("insert into board_column (project_id, name, position, status, color) values (?, ?, ?, ?, ?)")
                    .params(projectId, DEFAULT_COLUMNS[i][0], i, DEFAULT_COLUMNS[i][1], DEFAULT_COLUMNS[i][2]).update();
        }
        for (int i = 0; i < DEFAULT_PRIORITIES.length; i++) {
            db.sql("insert into ticket_priority (project_id, name, color, position) values (?, ?, ?, ?)")
                    .params(projectId, DEFAULT_PRIORITIES[i][0], DEFAULT_PRIORITIES[i][1], i).update();
        }
        db.sql("insert into ticket_counter (project_id, prefix, last_number) values (?, ?, 0)").params(projectId, COUNTER).update();
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

    // ---------- tags and priorities ----------

    List<TagView> tags(Long projectId) {
        return db.sql("select id, name, color from project_tag where project_id = ? order by name")
                .param(projectId).query((rs, i) -> new TagView(rs.getLong(1), rs.getString(2), rs.getString(3))).list();
    }

    List<PriorityView> priorities(Long projectId) {
        return db.sql("select id, name, color from ticket_priority where project_id = ? order by position")
                .param(projectId).query((rs, i) -> new PriorityView(rs.getLong(1), rs.getString(2), rs.getString(3))).list();
    }

    public Board addTag(String slug, Long userId, String name, String color) {
        ProjectSummary project = requireOwner(slug, userId);
        if (db.sql("select count(*) from project_tag where project_id = ?").param(project.id()).query(Integer.class).single() >= MAX_TAGS) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_TAGS", "A project can have at most " + MAX_TAGS + " tags.");
        }
        try {
            db.sql("insert into project_tag (project_id, name, color) values (?, ?, ?)").params(project.id(), name.trim(), color).update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "TAG_EXISTS", "That tag already exists.", Map.of("name", "That tag already exists."));
        }
        return board(slug, userId);
    }

    public Board updateTag(String slug, Long userId, Long tagId, String name, String color) {
        ProjectSummary project = requireOwner(slug, userId);
        requireTag(project.id(), tagId);
        try {
            if (name != null && !name.isBlank()) {
                db.sql("update project_tag set name = ? where id = ?").params(name.trim(), tagId).update();
            }
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "TAG_EXISTS", "That tag already exists.", Map.of("name", "That tag already exists."));
        }
        if (color != null) {
            db.sql("update project_tag set color = ? where id = ?").params(color, tagId).update();
        }
        return board(slug, userId);
    }

    public Board deleteTag(String slug, Long userId, Long tagId) {
        ProjectSummary project = requireOwner(slug, userId);
        requireTag(project.id(), tagId);
        db.sql("delete from project_tag where id = ?").param(tagId).update();
        return board(slug, userId);
    }

    public Board addPriority(String slug, Long userId, String name, String color) {
        ProjectSummary project = requireOwner(slug, userId);
        int count = db.sql("select count(*) from ticket_priority where project_id = ?").param(project.id()).query(Integer.class).single();
        if (count >= MAX_PRIORITIES) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_PRIORITIES", "A project can have at most " + MAX_PRIORITIES + " priorities.");
        }
        db.sql("insert into ticket_priority (project_id, name, color, position) values (?, ?, ?, ?)")
                .params(project.id(), name.trim(), color, count).update();
        return board(slug, userId);
    }

    public Board updatePriority(String slug, Long userId, Long priorityId, String name, String color) {
        ProjectSummary project = requireOwner(slug, userId);
        requirePriority(project.id(), priorityId);
        if (name != null && !name.isBlank()) {
            db.sql("update ticket_priority set name = ? where id = ?").params(name.trim(), priorityId).update();
        }
        if (color != null) {
            db.sql("update ticket_priority set color = ? where id = ?").params(color, priorityId).update();
        }
        return board(slug, userId);
    }

    /** A priority that tickets still use cannot go, and at least one always stays. */
    public Board deletePriority(String slug, Long userId, Long priorityId) {
        ProjectSummary project = requireOwner(slug, userId);
        requirePriority(project.id(), priorityId);
        if (db.sql("select count(*) from ticket_priority where project_id = ?").param(project.id()).query(Integer.class).single() <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "PRIORITY_REQUIRED", "A project needs at least one priority.");
        }
        if (db.sql("select count(*) from ticket where priority_id = ?").param(priorityId).query(Integer.class).single() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "PRIORITY_IN_USE", "Change those tickets to another priority first.");
        }
        db.sql("delete from ticket_priority where id = ?").param(priorityId).update();
        applyPriorityOrder(project.id(), db.sql("select id from ticket_priority where project_id = ? order by position").param(project.id()).query(Long.class).list());
        return board(slug, userId);
    }

    public Board reorderPriorities(String slug, Long userId, List<Long> ids) {
        ProjectSummary project = requireOwner(slug, userId);
        List<Long> current = db.sql("select id from ticket_priority where project_id = ?").param(project.id()).query(Long.class).list();
        if (ids.size() != current.size() || !current.containsAll(ids) || ids.stream().distinct().count() != ids.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_ORDER", "Send every priority exactly once.");
        }
        applyPriorityOrder(project.id(), ids);
        return board(slug, userId);
    }

    private void applyPriorityOrder(Long projectId, List<Long> ids) {
        db.sql("update ticket_priority set position = position + 1000 where project_id = ?").param(projectId).update();
        for (int i = 0; i < ids.size(); i++) {
            db.sql("update ticket_priority set position = ? where id = ? and project_id = ?").params(i, ids.get(i), projectId).update();
        }
    }

    private void requirePriority(Long projectId, Long priorityId) {
        if (priorityId == null || db.sql("select count(*) from ticket_priority where id = ? and project_id = ?").params(priorityId, projectId).query(Integer.class).single() == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_PRIORITY", "Pick one of this project's priorities.");
        }
    }

    /** The middle priority, which is Medium by default, is what a new ticket gets when none is chosen. */
    private long defaultPriority(Long projectId) {
        List<Long> ids = db.sql("select id from ticket_priority where project_id = ? order by position").param(projectId).query(Long.class).list();
        return ids.get(Math.min(1, ids.size() - 1));
    }

    private void requireTag(Long projectId, Long tagId) {
        if (db.sql("select count(*) from project_tag where id = ? and project_id = ?").params(tagId, projectId).query(Integer.class).single() == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Tag not found.");
        }
    }

    /** Replaces a ticket's tags. Every tag must belong to the same project. */
    private void setTicketTags(Long projectId, long ticketId, List<Long> tagIds) {
        List<Long> wanted = tagIds == null ? List.of() : tagIds.stream().distinct().toList();
        if (wanted.size() > MAX_TICKET_TAGS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOO_MANY_TAGS", "A ticket can have at most " + MAX_TICKET_TAGS + " tags.");
        }
        wanted.forEach(t -> requireTag(projectId, t));
        db.sql("delete from ticket_tag where ticket_id = ?").param(ticketId).update();
        wanted.forEach(t -> db.sql("insert into ticket_tag (ticket_id, tag_id) values (?, ?)").params(ticketId, t).update());
    }

    // ---------- columns ----------

    /** Adds a column just before Done, so Done stays last. */
    public Board addColumn(String slug, Long userId, String name, String color) {
        ProjectSummary project = requireOwner(slug, userId);
        if (db.sql("select count(*) from board_column where project_id = ?").param(project.id()).query(Integer.class).single() >= MAX_COLUMNS) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_COLUMNS", "A board can have at most " + MAX_COLUMNS + " columns.");
        }
        int at = db.sql("select coalesce(min(position), -1) from board_column where project_id = ? and status = 'DONE'")
                .param(project.id()).query(Integer.class).single();
        if (at < 0) {
            at = db.sql("select coalesce(max(position) + 1, 0) from board_column where project_id = ?").param(project.id()).query(Integer.class).single();
        }
        // Shift in two steps because (project_id, position) is unique.
        db.sql("update board_column set position = position + 1000 where project_id = ? and position >= ?").params(project.id(), at).update();
        db.sql("update board_column set position = position - 999 where project_id = ? and position >= 1000").param(project.id()).update();
        db.sql("insert into board_column (project_id, name, position, status, color) values (?, ?, ?, 'CUSTOM', ?)")
                .params(project.id(), name.trim(), at, color).update();
        return board(slug, userId);
    }

    public Board updateColumn(String slug, Long userId, Long columnId, String name, String color) {
        ProjectSummary project = requireOwner(slug, userId);
        columnStatus(project.id(), columnId);
        if (name != null && !name.isBlank()) {
            db.sql("update board_column set name = ? where id = ?").params(name.trim(), columnId).update();
        }
        if (color != null) {
            db.sql("update board_column set color = ? where id = ?").params(color, columnId).update();
        }
        return board(slug, userId);
    }

    /** Backlog and Done are required: new tickets land in Backlog and Done marks finished work. */
    public Board deleteColumn(String slug, Long userId, Long columnId) {
        ProjectSummary project = requireOwner(slug, userId);
        String status = columnStatus(project.id(), columnId);
        if ("BACKLOG".equals(status) || "DONE".equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "COLUMN_REQUIRED", "Backlog and Done cannot be removed.");
        }
        if (db.sql("select count(*) from ticket where column_id = ?").param(columnId).query(Integer.class).single() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "COLUMN_NOT_EMPTY", "Move its tickets to another column first.");
        }
        db.sql("delete from board_column where id = ?").param(columnId).update();
        List<Long> rest = db.sql("select id from board_column where project_id = ? order by position").param(project.id()).query(Long.class).list();
        applyOrder(project.id(), rest);
        return board(slug, userId);
    }

    public Board reorderColumns(String slug, Long userId, List<Long> ids) {
        ProjectSummary project = requireOwner(slug, userId);
        List<Long> current = db.sql("select id from board_column where project_id = ?").param(project.id()).query(Long.class).list();
        if (ids.size() != current.size() || !current.containsAll(ids) || ids.stream().distinct().count() != ids.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_ORDER", "Send every column exactly once.");
        }
        applyOrder(project.id(), ids);
        return board(slug, userId);
    }

    private void applyOrder(Long projectId, List<Long> ids) {
        db.sql("update board_column set position = position + 1000 where project_id = ?").param(projectId).update();
        for (int i = 0; i < ids.size(); i++) {
            db.sql("update board_column set position = ? where id = ? and project_id = ?").params(i, ids.get(i), projectId).update();
        }
    }

    private String columnStatus(Long projectId, Long columnId) {
        return db.sql("select status from board_column where id = ? and project_id = ?").params(columnId, projectId)
                .query(String.class).optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Column not found."));
    }

    /** Changes the key prefix and digits and renames every existing ticket to match. Numbers never change. */
    public TicketFormat setTicketFormat(String slug, Long userId, UpdateTicketFormatRequest r) {
        ProjectSummary project = requireOwner(slug, userId);
        TicketFormat f = new TicketFormat(r.digits(), TicketFormat.normalize(r.prefix()));
        db.sql("update project set key_digits = ?, key_prefix = ? where id = ?").params(f.digits(), f.prefix(), project.id()).update();
        db.sql("update ticket set ticket_key = concat(?, '-', lpad(number, ?, '0')) where project_id = ?")
                .params(f.prefix(), f.digits(), project.id()).update();
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
        Map<Long, TicketCard> cards = new java.util.HashMap<>();
        db.sql("select id, name, status, color, position from board_column where project_id = ? order by position")
                .param(project.id())
                .query((rs, i) -> new Column(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), new ArrayList<>()))
                .list().forEach(c -> columns.put(c.id(), c));
        db.sql("""
                select t.id, t.ticket_key, t.title, t.priority_id, t.column_id,
                       (select count(*) from ticket_pr_link l where l.ticket_id = t.id) as prs,
                       (select l.state from ticket_pr_link l where l.ticket_id = t.id order by l.updated_at desc, l.id desc limit 1) as pr_state,
                       (select count(*) from ticket_comment c where c.ticket_id = t.id) as comments,
                       t.created_at
                from ticket t
                where t.project_id = ? order by t.column_id, t.position, t.id""")
                .param(project.id())
                .query((rs, i) -> {
                    TicketCard card = new TicketCard(rs.getLong("id"), rs.getString("ticket_key"),
                            rs.getString("title"),
                            rs.getLong("priority_id"), new ArrayList<>(),
                            rs.getInt("prs"), rs.getString("pr_state"), rs.getInt("comments"), new ArrayList<>(),
                            rs.getTimestamp("created_at").toInstant());
                    Column col = columns.get(rs.getLong("column_id"));
                    if (col != null) {
                        col.tickets().add(card);
                    }
                    cards.put(card.id(), card);
                    return card;
                }).list();
        db.sql("""
                select ta.ticket_id, u.id, u.display_name from ticket_assignee ta
                join ticket t on t.id = ta.ticket_id join app_user u on u.id = ta.user_id
                where t.project_id = ? order by u.display_name""")
                .param(project.id()).query((rs, i) -> {
                    TicketCard card = cards.get(rs.getLong(1));
                    if (card != null) {
                        card.assignees().add(new Assignee(rs.getLong(2), rs.getString(3)));
                    }
                    return 0;
                }).list();
        db.sql("select tt.ticket_id, tt.tag_id from ticket_tag tt join ticket t on t.id = tt.ticket_id where t.project_id = ? order by tt.tag_id")
                .param(project.id()).query((rs, i) -> {
                    TicketCard card = cards.get(rs.getLong(1));
                    if (card != null) {
                        card.tagIds().add(rs.getLong(2));
                    }
                    return 0;
                }).list();
        return new Board(project, new ArrayList<>(columns.values()), members(project.id()), formats.load(project.id()),
                tags(project.id()), priorities(project.id()));
    }

    // ---------- tickets ----------

    public TicketDetail createTicket(String slug, Long userId, CreateTicketRequest r) {
        ProjectSummary project = requireMember(slug, userId);
        long columnId = r.columnId() != null ? requireColumn(project.id(), r.columnId())
                : db.sql("select id from board_column where project_id = ? and status = 'BACKLOG' order by position limit 1")
                        .param(project.id()).query(Long.class).single();
        if (r.priorityId() != null) {
            requirePriority(project.id(), r.priorityId());
        }
        int number = db.sql("select last_number from ticket_counter where project_id = ? and prefix = ? for update")
                .params(project.id(), COUNTER).query(Integer.class).optional().map(n -> n + 1).orElse(1);
        db.sql("""
                insert into ticket_counter (project_id, prefix, last_number) values (?, ?, ?)
                on duplicate key update last_number = values(last_number)""")
                .params(project.id(), COUNTER, number).update();
        int position = db.sql("select coalesce(max(position) + 1, 0) from ticket where column_id = ?")
                .param(columnId).query(Integer.class).single();
        String key = formats.load(project.id()).key(number);
        Timestamp ts = now();
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        db.sql("""
                insert into ticket (project_id, number, ticket_key, title, description, column_id, position,
                                    priority_id, created_by, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(project.id(), number, key, r.title().trim(), blankToNull(r.description()), columnId, position,
                        r.priorityId() == null ? defaultPriority(project.id()) : r.priorityId(), userId, ts, ts)
                .update(keys);
        setTicketAssignees(project.id(), keys.getKey().longValue(), r.assigneeIds());
        setTicketTags(project.id(), keys.getKey().longValue(), r.tagIds());
        event(keys.getKey().longValue(), userId, "USER", "CREATED", null);
        return ticket(slug, key, userId);
    }

    public TicketDetail ticket(String slug, String key, Long userId) {
        ProjectSummary project = requireMember(slug, userId);
        return loadDetail(project.id(), key);
    }

    private TicketDetail loadDetail(Long projectId, String key) {
        var base = db.sql("""
                select t.id, t.ticket_key, t.title, t.description, t.priority_id, t.column_id, c.name as column_name,
                       c.status, u.display_name as creator, t.created_at, t.updated_at
                from ticket t join board_column c on c.id = t.column_id
                left join app_user u on u.id = t.created_by
                where t.project_id = ? and t.ticket_key = ?""")
                .params(projectId, key)
                .query((rs, i) -> new Object[] {rs.getLong("id"), rs.getString("ticket_key"),
                        rs.getString("title"), rs.getString("description"), rs.getLong("priority_id"), rs.getLong("column_id"),
                        rs.getString("column_name"), rs.getString("status"),
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
        return new TicketDetail(id, (String) base[1], (String) base[2], (String) base[3],
                (Long) base[4], (Long) base[5], (String) base[6], (String) base[7],
                db.sql("select u.id, u.display_name from ticket_assignee ta join app_user u on u.id = ta.user_id where ta.ticket_id = ? order by u.display_name")
                        .param(id).query((rs, i) -> new Assignee(rs.getLong(1), rs.getString(2))).list(),
                (String) base[8], (java.time.Instant) base[9], (java.time.Instant) base[10], prs, comments, events,
                db.sql("select tag_id from ticket_tag where ticket_id = ? order by tag_id").param(id).query(Long.class).list());
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
        if (r.priorityId() != null && !r.priorityId().equals(cur.priorityId())) {
            requirePriority(project.id(), r.priorityId());
            String from = db.sql("select name from ticket_priority where id = ?").param(cur.priorityId()).query(String.class).optional().orElse("none");
            String to = db.sql("select name from ticket_priority where id = ?").param(r.priorityId()).query(String.class).single();
            db.sql("update ticket set priority_id = ?, updated_at = ? where id = ?").params(r.priorityId(), now(), cur.id()).update();
            event(cur.id(), userId, "USER", "PRIORITY_CHANGED", from + " to " + to);
        }
        if (r.assigneeIds() != null && !new java.util.HashSet<>(r.assigneeIds()).equals(
                cur.assignees().stream().map(Assignee::id).collect(java.util.stream.Collectors.toSet()))) {
            setTicketAssignees(project.id(), cur.id(), r.assigneeIds());
            db.sql("update ticket set updated_at = ? where id = ?").params(now(), cur.id()).update();
            List<String> names = db.sql("select u.display_name from ticket_assignee ta join app_user u on u.id = ta.user_id where ta.ticket_id = ? order by u.display_name")
                    .param(cur.id()).query(String.class).list();
            event(cur.id(), userId, "USER", "ASSIGNEES_CHANGED", names.isEmpty() ? "nobody" : String.join(", ", names));
        }
        if (r.tagIds() != null && !new java.util.HashSet<>(r.tagIds()).equals(new java.util.HashSet<>(cur.tagIds()))) {
            setTicketTags(project.id(), cur.id(), r.tagIds());
            event(cur.id(), userId, "USER", "TAGS_CHANGED", null);
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

    /** Replaces a ticket's assignees. Every one must be a member of the project. */
    private void setTicketAssignees(Long projectId, long ticketId, List<Long> userIds) {
        List<Long> wanted = userIds == null ? List.of() : userIds.stream().distinct().toList();
        if (wanted.size() > 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOO_MANY_ASSIGNEES", "A ticket can have at most 10 assignees.");
        }
        wanted.forEach(u -> requireProjectMember(projectId, u));
        db.sql("delete from ticket_assignee where ticket_id = ?").param(ticketId).update();
        wanted.forEach(u -> db.sql("insert into ticket_assignee (ticket_id, user_id) values (?, ?)").params(ticketId, u).update());
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
