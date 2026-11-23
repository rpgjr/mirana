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
        json("/api/projects/hophop/tickets", "{\"type\":\"FEATURE\",\"title\":\"One\"}", ana(), 201);
        json("/api/projects/hophop/tickets", "{\"type\":\"FEATURE\",\"title\":\"Two\"}", ana(), 201);
        json("/api/projects/hophop/tickets", "{\"type\":\"BUGFIX\",\"title\":\"Bug\"}", ana(), 201);
        json("/api/projects/mepmep/tickets", "{\"type\":\"FEATURE\",\"title\":\"Other project\"}", ana(), 201);

        mvc.perform(get("/api/projects/hophop/board").with(ana())).andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].name").value("Backlog"))
                .andExpect(jsonPath("$.columns[0].tickets[0].key").value("FT-00001"))
                .andExpect(jsonPath("$.columns[0].tickets[1].key").value("FT-00002"))
                .andExpect(jsonPath("$.columns[0].tickets[2].key").value("BUG-00001"));
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
            json("/api/projects/board/tickets", "{\"type\":\"FEATURE\",\"title\":\"" + t + "\"}", ana(), 201);
        }
        long done = jdbc.queryForObject("select id from board_column where name = 'Done'", Long.class);
        json("/api/projects/board/tickets/FT-00001/move", "{\"columnId\":" + done + ",\"position\":0}", ana(), 200);
        json("/api/projects/board/tickets/FT-00003/move", "{\"columnId\":" + done + ",\"position\":0}", ana(), 200);
        mvc.perform(get("/api/projects/board/board").with(ana()))
                .andExpect(jsonPath("$.columns[3].tickets[0].key").value("FT-00003"))
                .andExpect(jsonPath("$.columns[3].tickets[1].key").value("FT-00001"))
                .andExpect(jsonPath("$.columns[0].tickets.length()").value(1));
        mvc.perform(get("/api/projects/board/tickets/FT-00001").with(ana()))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.events[0].type").value("MOVED"));
    }

    @Test
    void updateCommentAndValidation() throws Exception {
        json("/api/projects", "{\"name\":\"upd\"}", ana(), 201);
        json("/api/projects/upd/tickets", "{\"type\":\"HOTFIX\",\"title\":\"Fix it\"}", ana(), 201);
        json("/api/projects/upd/tickets", "{\"type\":\"HOTFIX\",\"title\":\"\"}", ana(), 400);
        json("/api/projects/upd/tickets", "{\"type\":\"HOTFIX\",\"title\":\"x\",\"assigneeId\":" + ben + "}", ana(), 400);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/projects/upd/tickets/HF-00001")
                .with(ana()).with(csrf()).contentType("application/json").content("{\"priority\":\"URGENT\",\"title\":\"Fixed title\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.priority").value("URGENT")).andExpect(jsonPath("$.title").value("Fixed title"));
        json("/api/projects/upd/tickets/HF-00001/comments", "{\"body\":\"Looks good\"}", ana(), 201);
        mvc.perform(get("/api/projects/upd/tickets/HF-00001").with(ana()))
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
        json("/api/projects/fmt/tickets", "{\"type\":\"FEATURE\",\"title\":\"One\"}", ana(), 201);
        json("/api/projects/fmt/tickets", "{\"type\":\"HOTFIX\",\"title\":\"Two\"}", ana(), 201);
        jdbc.update("insert into project_member (project_id, user_id, role) select id, ?, 'MEMBER' from project where slug = 'fmt'", ben);
        var put = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/projects/fmt/ticket-format");

        send(put, ben(), "{\"digits\":3,\"featurePrefix\":\"MIR\",\"hotfixPrefix\":\"FIX\",\"bugfixPrefix\":\"BUG\"}", 403);
        send(put, ana(), "{\"digits\":3,\"featurePrefix\":\"MIR\",\"hotfixPrefix\":\"MIR\",\"bugfixPrefix\":\"BUG\"}", 400);
        send(put, ana(), "{\"digits\":9,\"featurePrefix\":\"MIR\",\"hotfixPrefix\":\"FIX\",\"bugfixPrefix\":\"BUG\"}", 400);
        send(put, ana(), "{\"digits\":3,\"featurePrefix\":\"1X\",\"hotfixPrefix\":\"FIX\",\"bugfixPrefix\":\"BUG\"}", 400);
        send(put, ana(), "{\"digits\":3,\"featurePrefix\":\"mir\",\"hotfixPrefix\":\"FIX\",\"bugfixPrefix\":\"BUG\"}", 200);

        json("/api/projects/fmt/tickets", "{\"type\":\"FEATURE\",\"title\":\"Three\"}", ana(), 201);
        mvc.perform(get("/api/projects/fmt/board").with(ana()))
                .andExpect(jsonPath("$.format.featurePrefix").value("MIR"))
                .andExpect(jsonPath("$.columns[0].tickets[0].key").value("MIR-001"))
                .andExpect(jsonPath("$.columns[0].tickets[1].key").value("FIX-001"))
                .andExpect(jsonPath("$.columns[0].tickets[2].key").value("MIR-002"));

        // Swapping two prefixes must not collide on the unique key.
        send(put, ana(), "{\"digits\":5,\"featurePrefix\":\"FIX\",\"hotfixPrefix\":\"MIR\",\"bugfixPrefix\":\"BUG\"}", 200);
        mvc.perform(get("/api/projects/fmt/board").with(ana()))
                .andExpect(jsonPath("$.columns[0].tickets[0].key").value("FIX-00001"))
                .andExpect(jsonPath("$.columns[0].tickets[1].key").value("MIR-00001"));
    }
}
