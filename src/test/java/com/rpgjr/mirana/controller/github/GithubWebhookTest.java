package com.rpgjr.mirana.controller.github;

import com.rpgjr.mirana.utils.WebhookSignature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rpgjr.mirana.enums.Priority;
import com.rpgjr.mirana.model.dto.project.ProjectDtos.CreateTicketRequest;
import com.rpgjr.mirana.service.project.ProjectService;
import com.rpgjr.mirana.enums.TicketType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class GithubWebhookTest {

    private static final String SECRET = "test-webhook-secret";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;

    String key;

    @BeforeEach
    void setUp() {
        com.rpgjr.mirana.utils.TestDb.clean(jdbc);
        jdbc.update("insert into app_user (email, email_verified_at, display_name, created_at, updated_at) values ('ana@example.com', now(), 'Ana', now(), now())");
        long ana = jdbc.queryForObject("select id from app_user", Long.class);
        var project = projects.create(ana, "Hophop", java.util.List.of("rpgjr/hophop", "RPGJR/Hophop-UI"));
        key = projects.createTicket(project.slug(), ana,
                new CreateTicketRequest(TicketType.FEATURE, "Login page", null, Priority.MEDIUM, null, null)).key();
    }

    private ResultActions send(String delivery, String event, String body, String signature) throws Exception {
        var req = post("/api/github/webhook").contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", event).header("X-GitHub-Delivery", delivery)
                .content(body);
        if (signature != null) {
            req.header("X-Hub-Signature-256", signature);
        }
        return mvc.perform(req);
    }

    private ResultActions sendSigned(String delivery, String body) throws Exception {
        return send(delivery, "pull_request", body, WebhookSignature.sign(SECRET, body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String pr(String action, String state, boolean merged, String branch, String repo) {
        return pr(action, state, merged, branch, repo, "OWNER");
    }

    private static String pr(String action, String state, boolean merged, String branch, String repo, String association) {
        return """
                {"action":"%s","repository":{"full_name":"%s"},
                 "pull_request":{"number":7,"title":"Add login","state":"%s","merged":%s,"html_url":"https://github.com/%s/pull/7",
                 "author_association":"%s","body":"","head":{"ref":"%s"}}}""".formatted(action, repo, state, merged, repo, association, branch);
    }

    @Test
    void rejectsMissingOrWrongSignature() throws Exception {
        String body = pr("opened", "open", false, "ft-00001-login", "rpgjr/hophop");
        send("d1", "pull_request", body, null).andExpect(status().isUnauthorized());
        send("d2", "pull_request", body, "sha256=deadbeef").andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from ticket_pr_link", Integer.class)).isZero();
    }

    @Test
    void aBranchNamedExactlyLikeTheTicketKeyLinksAndShortFormsToo() throws Exception {
        assertThat(key).isEqualTo("FT-00001");
        sendSigned("d1", pr("opened", "open", false, key, "rpgjr/hophop")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from ticket_pr_link", Integer.class)).isEqualTo(1);
        sendSigned("d2", pr("opened", "open", false, "ft-1", "RpgJr/Hophop-Ui")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from ticket_pr_link", Integer.class)).isEqualTo(2);
    }

    @Test
    void usesTheProjectsOwnKeyFormat() throws Exception {
        jdbc.update("update project set key_digits = 3, prefix_feature = 'MIR'");
        jdbc.update("update ticket set ticket_key = 'MIR-001'");
        sendSigned("d1", pr("opened", "open", false, "MIR-1", "rpgjr/hophop")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from ticket_pr_link", Integer.class)).isEqualTo(1);
        sendSigned("d2", pr("opened", "open", false, "FT-00001", "rpgjr/hophop")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from ticket_pr_link", Integer.class)).isEqualTo(1);
    }

    @Test
    void linksPullRequestByBranchKeyThenTracksMerge() throws Exception {
        sendSigned("d1", pr("opened", "open", false, "feature/FT-00001-login", "rpgjr/hophop")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select state from ticket_pr_link", String.class)).isEqualTo("OPEN");

        sendSigned("d2", pr("closed", "closed", true, "feature/FT-00001-login", "rpgjr/hophop")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from ticket_pr_link", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select state from ticket_pr_link", String.class)).isEqualTo("MERGED");
        assertThat(jdbc.queryForObject("select count(*) from ticket_event where actor_type = 'GITHUB'", Integer.class)).isEqualTo(2);
    }

    @Test
    void oneProjectLinksPullRequestsFromEveryRepoRegardlessOfCase() throws Exception {
        sendSigned("d1", pr("opened", "open", false, "FT-00001", "rpgjr/hophop")).andExpect(status().isOk());
        sendSigned("d2", pr("opened", "open", false, "FT-00001", "RpgJr/Hophop-Ui")).andExpect(status().isOk());
        assertThat(jdbc.queryForList("select repo from ticket_pr_link order by repo", String.class))
                .containsExactly("rpgjr/hophop", "rpgjr/hophop-ui");
    }

    @Test
    void aRepositoryBelongsToOneProjectOnly() {
        long ana = jdbc.queryForObject("select id from app_user", Long.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> projects.create(ana, "Other", java.util.List.of("rpgjr/Hophop-ui")))
                .hasMessageContaining("already belongs");
    }

    @Test
    void ignoresPullRequestsFromOutsideContributors() throws Exception {
        sendSigned("d1", pr("opened", "open", false, "FT-00001", "rpgjr/hophop", "NONE")).andExpect(status().isOk());
        sendSigned("d2", pr("opened", "open", false, "FT-00001", "rpgjr/hophop", "FIRST_TIME_CONTRIBUTOR")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from ticket_pr_link", Integer.class)).isZero();
        sendSigned("d3", pr("opened", "open", false, "FT-00001", "rpgjr/hophop", "COLLABORATOR")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from ticket_pr_link", Integer.class)).isEqualTo(1);
    }

    @Test
    void replayedDeliveryIsIgnored() throws Exception {
        String body = pr("opened", "open", false, "FT-00001", "rpgjr/hophop");
        sendSigned("same", body).andExpect(status().isOk());
        jdbc.update("update ticket_pr_link set state = 'CLOSED'");
        sendSigned("same", body).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select state from ticket_pr_link", String.class)).isEqualTo("CLOSED");
    }

    @Test
    void ignoresOtherReposAndUnknownKeys() throws Exception {
        sendSigned("d1", pr("opened", "open", false, "FT-00001", "someone/else")).andExpect(status().isOk());
        sendSigned("d2", pr("opened", "open", false, "FT-00999", "rpgjr/hophop")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from ticket_pr_link", Integer.class)).isZero();
    }
}
