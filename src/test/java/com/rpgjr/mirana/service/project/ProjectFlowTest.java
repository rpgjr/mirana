package com.rpgjr.mirana.service.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rpgjr.mirana.security.AuthUser;
import com.rpgjr.mirana.service.mail.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class ProjectFlowTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean MailService mail;

    long ana;
    long ben;

    @BeforeEach
    void setUp() {
        com.rpgjr.mirana.utils.TestDb.clean(jdbc);
        ana = user("ana@example.com", "Ana");
        ben = user("ben@example.com", "Ben");
    }

    private long user(String email, String name) {
        jdbc.update("insert into app_user (email, email_verified_at, display_name, created_at, updated_at) values (?, now(), ?, now(), now())", email, name);
        return jdbc.queryForObject("select id from app_user where email = ?", Long.class, email);
    }

    private RequestPostProcessor as(long id, String email, String name) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(new AuthUser(id, email, name), null,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private RequestPostProcessor ana() {
        return as(ana, "ana@example.com", "Ana");
    }

    private RequestPostProcessor ben() {
        return as(ben, "ben@example.com", "Ben");
    }

    private void json(String path, String body, RequestPostProcessor who, int expected) throws Exception {
        mvc.perform(post(path).with(who).with(csrf()).contentType("application/json").content(body)).andExpect(status().is(expected));
    }

    @Test
    void keysCountPerProjectAndPrefixAndStrangersGetNotFound() throws Exception {
        json("/api/projects", "{\"name\":\"hophop\"}", ana(), 201);
        json("/api/projects", "{\"name\":\"mepmep\"}", ana(), 201);
        json("/api/projects/hophop/tickets", "{\"title\":\"One\"}", ana(), 201);
        json("/api/projects/hophop/tickets", "{\"title\":\"Two\"}", ana(), 201);
        json("/api/projects/hophop/tickets", "{\"title\":\"Bug\"}", ana(), 201);
        json("/api/projects/mepmep/tickets", "{\"title\":\"Other project\"}", ana(), 201);

        mvc.perform(get("/api/projects/hophop/board").with(ana())).andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].name").value("Backlog"))
                .andExpect(jsonPath("$.columns[0].tickets[0].key").value("FT-00001"))
                .andExpect(jsonPath("$.columns[0].tickets[1].key").value("FT-00002"))
                .andExpect(jsonPath("$.columns[0].tickets[2].key").value("FT-00003"));
        mvc.perform(get("/api/projects/mepmep/tickets/FT-00001").with(ana())).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Other project"));

        mvc.perform(get("/api/projects/hophop/board").with(ben())).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/hophop/tickets/FT-00001").with(ben())).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects").with(ben())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void moveReordersAndWritesAnEvent() throws Exception {
        json("/api/projects", "{\"name\":\"board\"}", ana(), 201);
        for (String t : new String[] {"A", "B", "C"}) {
            json("/api/projects/board/tickets", "{\"title\":\"" + t + "\"}", ana(), 201);
        }
        long done = jdbc.queryForObject("select id from board_column where name = 'Done'", Long.class);
        json("/api/projects/board/tickets/FT-00001/move", "{\"columnId\":" + done + ",\"position\":0}", ana(), 200);
        json("/api/projects/board/tickets/FT-00003/move", "{\"columnId\":" + done + ",\"position\":0}", ana(), 200);
        mvc.perform(get("/api/projects/board/board").with(ana()))
                .andExpect(jsonPath("$.columns[4].tickets[0].key").value("FT-00003"))
                .andExpect(jsonPath("$.columns[4].tickets[1].key").value("FT-00001"))
                .andExpect(jsonPath("$.columns[0].tickets.length()").value(1));
        mvc.perform(get("/api/projects/board/tickets/FT-00001").with(ana()))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.events[0].type").value("MOVED"));
    }

    @Test
    void updateCommentAndValidation() throws Exception {
        json("/api/projects", "{\"name\":\"upd\"}", ana(), 201);
        json("/api/projects/upd/tickets", "{\"title\":\"Fix it\"}", ana(), 201);
        json("/api/projects/upd/tickets", "{\"title\":\"\"}", ana(), 400);
        json("/api/projects/upd/tickets", "{\"title\":\"x\",\"assigneeIds\":[" + ben + "]}", ana(), 400);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/projects/upd/tickets/FT-00001")
                .with(ana()).with(csrf()).contentType("application/json").content("{\"priorityId\":" + jdbc.queryForObject("select id from ticket_priority where name = 'Urgent' limit 1", Long.class) + ",\"title\":\"Fixed title\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.priorityId").isNumber()).andExpect(jsonPath("$.title").value("Fixed title"));
        json("/api/projects/upd/tickets/FT-00001/comments", "{\"body\":\"Looks good\"}", ana(), 201);
        mvc.perform(get("/api/projects/upd/tickets/FT-00001").with(ana()))
                .andExpect(jsonPath("$.comments[0].body").value("Looks good"))
                .andExpect(jsonPath("$.comments[0].authorName").value("Ana"));
        assertThat(jdbc.queryForObject("select count(*) from ticket_event", Integer.class)).isGreaterThanOrEqualTo(3);
    }

    private void send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, RequestPostProcessor who, String body, int expected) throws Exception {
        mvc.perform(b.with(who).with(csrf()).contentType("application/json").content(body)).andExpect(status().is(expected));
    }

    @Test
    void ownerRenamesAndReplacesReposInOneStepMembersCannot() throws Exception {
        json("/api/projects", "{\"name\":\"Old name\",\"repos\":[\"rpgjr/a\",\"rpgjr/b\"]}", ana(), 201);
        json("/api/projects", "{\"name\":\"Other\",\"repos\":[\"rpgjr/taken\"]}", ana(), 201);
        jdbc.update("insert into project_member (project_id, user_id, role) select id, ?, 'MEMBER' from project where slug = 'old-name'", ben);
        var patch = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/projects/old-name");
        var put = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/projects/old-name/repos");

        send(patch, ana(), "{\"name\":\"New name\"}", 200);
        mvc.perform(get("/api/projects/old-name/board").with(ana())).andExpect(jsonPath("$.project.name").value("New name"));
        send(patch, ana(), "{\"name\":\"  \"}", 400);
        send(patch, ben(), "{\"name\":\"Hijack\"}", 403);

        send(put, ana(), "{\"repos\":[\"rpgjr/b\",\"rpgjr/c\"]}", 200);
        assertThat(jdbc.queryForList("select repo from project_repo pr join project p on p.id = pr.project_id where p.slug = 'old-name' order by repo", String.class))
                .containsExactly("rpgjr/b", "rpgjr/c");
        send(put, ana(), "{\"repos\":[\"rpgjr/b\",\"rpgjr/taken\"]}", 409);
        send(put, ben(), "{\"repos\":[]}", 403);
    }

    @Test
    void ownerChangesTheKeyFormatAndExistingTicketsAreRenamed() throws Exception {
        json("/api/projects", "{\"name\":\"Fmt\"}", ana(), 201);
        json("/api/projects/fmt/tickets", "{\"title\":\"One\"}", ana(), 201);
        json("/api/projects/fmt/tickets", "{\"title\":\"Two\"}", ana(), 201);
        jdbc.update("insert into project_member (project_id, user_id, role) select id, ?, 'MEMBER' from project where slug = 'fmt'", ben);
        var put = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/projects/fmt/ticket-format");

        send(put, ben(), "{\"digits\":3,\"prefix\":\"MIR\"}", 403);
        send(put, ana(), "{\"digits\":9,\"prefix\":\"MIR\"}", 400);
        send(put, ana(), "{\"digits\":3,\"prefix\":\"1X\"}", 400);
        send(put, ana(), "{\"digits\":3,\"prefix\":\"M\"}", 400);
        send(put, ana(), "{\"digits\":3,\"prefix\":\"mir\"}", 200);

        json("/api/projects/fmt/tickets", "{\"title\":\"Three\"}", ana(), 201);
        mvc.perform(get("/api/projects/fmt/board").with(ana()))
                .andExpect(jsonPath("$.format.prefix").value("MIR")).andExpect(jsonPath("$.format.digits").value(3))
                .andExpect(jsonPath("$.columns[0].tickets[0].key").value("MIR-001"))
                .andExpect(jsonPath("$.columns[0].tickets[1].key").value("MIR-002"))
                .andExpect(jsonPath("$.columns[0].tickets[2].key").value("MIR-003"));
    }

    @Test
    void ownerManagesColumnsAndDoneStaysLast() throws Exception {
        json("/api/projects", "{\"name\":\"Cols\"}", ana(), 201);
        mvc.perform(get("/api/projects/cols/board").with(ana()))
                .andExpect(jsonPath("$.columns.length()").value(5))
                .andExpect(jsonPath("$.columns[1].name").value("On hold"))
                .andExpect(jsonPath("$.columns[3].name").value("In review"))
                .andExpect(jsonPath("$.columns[4].name").value("Done"));
        jdbc.update("insert into project_member (project_id, user_id, role) select id, ?, 'MEMBER' from project where slug = 'cols'", ben);
        var cols = "/api/projects/cols/columns";

        json(cols, "{\"name\":\"QA\",\"color\":\"#ec4899\"}", ben(), 403);
        json(cols, "{\"name\":\"QA\",\"color\":\"mauve\"}", ana(), 400);
        json(cols, "{\"name\":\"QA\",\"color\":\"#ec4899\"}", ana(), 201);
        mvc.perform(get("/api/projects/cols/board").with(ana()))
                .andExpect(jsonPath("$.columns[4].name").value("QA")).andExpect(jsonPath("$.columns[4].color").value("#ec4899"))
                .andExpect(jsonPath("$.columns[5].name").value("Done"));

        long qa = jdbc.queryForObject("select id from board_column where name = 'QA'", Long.class);
        long done = jdbc.queryForObject("select id from board_column where name = 'Done'", Long.class);
        long backlog = jdbc.queryForObject("select id from board_column where name = 'Backlog'", Long.class);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(cols + "/" + qa).with(ana()).with(csrf())
                .contentType("application/json").content("{\"name\":\"Testing\",\"color\":\"#ef4444\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.columns[4].name").value("Testing"));

        json("/api/projects/cols/tickets", "{\"title\":\"x\",\"columnId\":" + qa + "}", ana(), 201);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(cols + "/" + qa).with(ana()).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COLUMN_NOT_EMPTY"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(cols + "/" + done).with(ana()).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COLUMN_REQUIRED"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(cols + "/" + backlog).with(ana()).with(csrf()))
                .andExpect(status().isConflict());

        var ids = jdbc.queryForList("select id from board_column order by position", Long.class);
        java.util.Collections.swap(ids, 1, 2);
        send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(cols + "/order"), ana(),
                "{\"ids\":" + ids + "}", 200);
        mvc.perform(get("/api/projects/cols/board").with(ana())).andExpect(jsonPath("$.columns[1].name").value("In progress"));
        send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(cols + "/order"), ana(), "{\"ids\":[1,2]}", 400);
    }

    @Test
    void tagsAndPriorityLabelsAreManagedPerProjectAndTicketsCarryTags() throws Exception {
        json("/api/projects", "{\"name\":\"Tags\"}", ana(), 201);
        jdbc.update("insert into project_member (project_id, user_id, role) select id, ?, 'MEMBER' from project where slug = 'tags'", ben);
        mvc.perform(get("/api/projects/tags/board").with(ana()))
                .andExpect(jsonPath("$.priorities.length()").value(4))
                .andExpect(jsonPath("$.priorities[3].name").value("Urgent")).andExpect(jsonPath("$.priorities[3].color").value("#ef4444"));
        long urgent = jdbc.queryForObject("select tp.id from ticket_priority tp join project p on p.id = tp.project_id where p.slug = 'tags' and tp.name = 'Urgent'", Long.class);
        var patchPriority = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/projects/tags/priorities/" + urgent);
        send(patchPriority, ben(), "{\"name\":\"Drop everything\",\"color\":\"#ec4899\"}", 403);
        send(patchPriority, ana(), "{\"name\":\"Drop everything\",\"color\":\"pink\"}", 400);
        send(patchPriority, ana(), "{\"name\":\"Drop everything\",\"color\":\"#ec4899\"}", 200);
        mvc.perform(get("/api/projects/tags/board").with(ana()))
                .andExpect(jsonPath("$.priorities[3].name").value("Drop everything")).andExpect(jsonPath("$.priorities[3].color").value("#ec4899"));

        json("/api/projects/tags/tags", "{\"name\":\"frontend\",\"color\":\"#3b82f6\"}", ben(), 403);
        json("/api/projects/tags/tags", "{\"name\":\"frontend\",\"color\":\"#3b82f6\"}", ana(), 201);
        json("/api/projects/tags/tags", "{\"name\":\"frontend\",\"color\":\"#ef4444\"}", ana(), 409);
        json("/api/projects/tags/tags", "{\"name\":\"api\",\"color\":\"#22c55e\"}", ana(), 201);
        long fe = jdbc.queryForObject("select id from project_tag where name = 'frontend'", Long.class);
        long api = jdbc.queryForObject("select id from project_tag where name = 'api'", Long.class);

        json("/api/projects/tags/tickets", "{\"title\":\"With tags\",\"tagIds\":[" + fe + "]}", ben(), 201);
        mvc.perform(get("/api/projects/tags/board").with(ana())).andExpect(jsonPath("$.columns[0].tickets[0].tagIds[0]").value(fe));
        var patch = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/projects/tags/tickets/FT-00001");
        send(patch, ben(), "{\"tagIds\":[" + fe + "," + api + "]}", 200);
        mvc.perform(get("/api/projects/tags/tickets/FT-00001").with(ana())).andExpect(jsonPath("$.tagIds.length()").value(2));
        send(patch, ben(), "{\"tagIds\":[999999]}", 404);
        send(patch, ben(), "{\"tagIds\":[]}", 200);
        mvc.perform(get("/api/projects/tags/tickets/FT-00001").with(ana())).andExpect(jsonPath("$.tagIds.length()").value(0));

        send(patch, ben(), "{\"tagIds\":[" + fe + "]}", 200);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/projects/tags/tags/" + fe).with(ana()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tags.length()").value(1));
        mvc.perform(get("/api/projects/tags/tickets/FT-00001").with(ana())).andExpect(jsonPath("$.tagIds.length()").value(0));
    }

    @Test
    void aTicketCanHaveSeveralAssigneesAndOnlyMembersQualify() throws Exception {
        json("/api/projects", "{\"name\":\"Team\"}", ana(), 201);
        jdbc.update("insert into project_member (project_id, user_id, role) select id, ?, 'MEMBER' from project where slug = 'team'", ben);
        json("/api/projects/team/tickets", "{\"title\":\"Pair work\",\"assigneeIds\":[" + ana + "," + ben + "]}", ana(), 201);
        mvc.perform(get("/api/projects/team/board").with(ana()))
                .andExpect(jsonPath("$.columns[0].tickets[0].assignees.length()").value(2))
                .andExpect(jsonPath("$.columns[0].tickets[0].assignees[0].name").value("Ana"));
        var patch = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/projects/team/tickets/FT-00001");
        send(patch, ben(), "{\"assigneeIds\":[" + ben + "]}", 200);
        mvc.perform(get("/api/projects/team/tickets/FT-00001").with(ana()))
                .andExpect(jsonPath("$.assignees.length()").value(1)).andExpect(jsonPath("$.assignees[0].name").value("Ben"))
                .andExpect(jsonPath("$.events[0].type").value("ASSIGNEES_CHANGED")).andExpect(jsonPath("$.events[0].detail").value("Ben"));
        send(patch, ben(), "{\"assigneeIds\":[999999]}", 400);
        send(patch, ben(), "{\"assigneeIds\":[]}", 200);
        mvc.perform(get("/api/projects/team/tickets/FT-00001").with(ana())).andExpect(jsonPath("$.assignees.length()").value(0));
    }

    @Test
    void ownerAddsEditsReordersAndRemovesPriorities() throws Exception {
        json("/api/projects", "{\"name\":\"Prio\"}", ana(), 201);
        jdbc.update("insert into project_member (project_id, user_id, role) select id, ?, 'MEMBER' from project where slug = 'prio'", ben);
        var base = "/api/projects/prio/priorities";
        json(base, "{\"name\":\"Blocker\",\"color\":\"#dc2626\"}", ben(), 403);
        json(base, "{\"name\":\"Blocker\",\"color\":\"red\"}", ana(), 400);
        json(base, "{\"name\":\"Blocker\",\"color\":\"#dc2626\"}", ana(), 201);
        mvc.perform(get("/api/projects/prio/board").with(ana()))
                .andExpect(jsonPath("$.priorities.length()").value(5)).andExpect(jsonPath("$.priorities[4].name").value("Blocker"));

        long blocker = jdbc.queryForObject("select id from ticket_priority where name = 'Blocker'", Long.class);
        json("/api/projects/prio/tickets", "{\"title\":\"Uses it\",\"priorityId\":" + blocker + "}", ana(), 201);
        var delete = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(base + "/" + blocker);
        mvc.perform(delete.with(ana()).with(csrf())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRIORITY_IN_USE"));
        json("/api/projects/prio/tickets", "{\"title\":\"Bad\",\"priorityId\":999999}", ana(), 400);

        var ids = jdbc.queryForList("select tp.id from ticket_priority tp join project p on p.id = tp.project_id where p.slug = 'prio' order by tp.position", Long.class);
        java.util.Collections.reverse(ids);
        send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base + "/order"), ana(), "{\"ids\":" + ids + "}", 200);
        mvc.perform(get("/api/projects/prio/board").with(ana())).andExpect(jsonPath("$.priorities[0].name").value("Blocker"));
        send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base + "/order"), ana(), "{\"ids\":[1]}", 400);

        long low = jdbc.queryForObject("select tp.id from ticket_priority tp join project p on p.id = tp.project_id where p.slug = 'prio' and tp.name = 'Low'", Long.class);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(base + "/" + low).with(ana()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.priorities.length()").value(4));
        mvc.perform(get("/api/projects/prio/tickets/FT-00001").with(ana())).andExpect(jsonPath("$.priorityId").value(blocker));
    }
}
