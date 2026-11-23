package com.rpgjr.mirana.service.github;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rpgjr.mirana.security.AuthUser;
import com.rpgjr.mirana.service.mail.MailService;
import com.rpgjr.mirana.utils.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.util.List;
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
@SpringBootTest(properties = {
        "mirana.github.app-id=1", "mirana.github.app-slug=mirana-test", "mirana.github.client-id=cid",
        "mirana.github.client-secret=secret", "mirana.github.private-key=unused"})
@AutoConfigureMockMvc
class GithubConnectFlowTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RateLimiter limiter;
    @MockitoBean MailService mail;
    @MockitoBean GithubApi github;

    long ana;
    /** Sessions live in Spring Session JDBC, so the test carries the session cookie like a browser. */
    Cookie session;

    @BeforeEach
    void setUp() {
        com.rpgjr.mirana.utils.TestDb.clean(jdbc);
        limiter.reset();
        session = null;
        jdbc.update("insert into app_user (email, email_verified_at, display_name, created_at, updated_at) values ('ana@example.com', now(), 'Ana', now(), now())");
        ana = jdbc.queryForObject("select id from app_user where email = 'ana@example.com'", Long.class);
        when(github.exchangeCode("good-code")).thenReturn("user-token");
        when(github.userInstallations("user-token")).thenReturn(List.of(new GithubApi.Installation(77, "RPGJR", "User", "all")));
        when(github.installationRepos(eq(77L))).thenReturn(List.of(
                new GithubApi.Repo("rpgjr/hophop", "Backend", true), new GithubApi.Repo("rpgjr/hophop-ui", "", false)));
        when(github.pulls(eq(77L), eq("rpgjr/hophop"))).thenReturn(List.of(
                new GithubApi.Pull(4, "FT-101 add login, fixes BUG-7", "closed", true, false, "rpgjr", "https://github.com/rpgjr/hophop/pull/4", "2026-11-23T01:00:00Z")));
    }

    private RequestPostProcessor asAna() {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(new AuthUser(ana, "ana@example.com", "Ana"), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withSession(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) {
        return session == null ? b : b.cookie(session);
    }

    private String stateOf() throws Exception {
        var result = mvc.perform(withSession(get("/api/github/status")).with(asAna()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true)).andReturn();
        if (result.getResponse().getCookie("MIRANA_SESSION") != null) {
            session = result.getResponse().getCookie("MIRANA_SESSION");
        }
        return result.getResponse().getContentAsString().replaceAll(".*state=([0-9a-f]+)\".*", "$1");
    }

    private void connect() throws Exception {
        String state = stateOf();
        mvc.perform(withSession(post("/api/github/connect")).with(asAna()).with(csrf())
                .contentType("application/json").content("{\"code\":\"good-code\",\"state\":\"" + state + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].login").value("RPGJR"));
    }

    @Test
    void connectingKeepsTheInstallationsTheGitHubUserCanReach() throws Exception {
        connect();
        mvc.perform(withSession(get("/api/github/status")).with(asAna()))
                .andExpect(jsonPath("$.installations[0].installationId").value(77))
                .andExpect(jsonPath("$.installations[0].manageUrl").value("https://github.com/settings/installations/77"));
    }

    @Test
    void aWrongStateIsRefusedAndNothingIsSaved() throws Exception {
        stateOf();
        mvc.perform(withSession(post("/api/github/connect")).with(asAna()).with(csrf())
                .contentType("application/json").content("{\"code\":\"good-code\",\"state\":\"forged\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_STATE"));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject("select count(*) from github_installation", Integer.class));
    }

    @Test
    void listsRepositoriesAndReadsPullRequestsWithTicketKeys() throws Exception {
        connect();
        mvc.perform(get("/api/github/repos").with(asAna()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fullName").value("rpgjr/hophop"));
        mvc.perform(get("/api/github/pulls").param("repo", "rpgjr/hophop").with(asAna()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].merged").value(true))
                .andExpect(jsonPath("$[0].ticketKeys[0]").value("FT-00101")).andExpect(jsonPath("$[0].ticketKeys[1]").value("BUG-00007"));
        mvc.perform(get("/api/github/pulls").param("repo", "someoneelse/secret").with(asAna()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aProjectMayOnlyTrackRepositoriesTheUserConnected() throws Exception {
        connect();
        mvc.perform(post("/api/projects").with(asAna()).with(csrf()).contentType("application/json")
                .content("{\"name\":\"Squat\",\"repos\":[\"victim/private-repo\"]}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REPO_NOT_CONNECTED"));
        mvc.perform(post("/api/projects").with(asAna()).with(csrf()).contentType("application/json")
                .content("{\"name\":\"Hophop\",\"repos\":[\"rpgjr/hophop\",\"rpgjr/hophop-ui\"]}"))
                .andExpect(status().isCreated());
    }
}
