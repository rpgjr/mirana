package com.rpgjr.mirana.service.account;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rpgjr.mirana.security.AuthUser;
import com.rpgjr.mirana.utils.RateLimiter;
import com.rpgjr.mirana.service.mail.MailService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AccountFlowTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired RateLimiter limiter;
    @MockitoBean MailService mail;

    long ana;
    long ben;

    @BeforeEach
    void setUp() {
        com.rpgjr.mirana.utils.TestDb.clean(jdbc);
        limiter.reset();
        ana = user("ana@example.com", "Ana");
        ben = user("ben@example.com", "Ben");
        jdbc.update("insert into user_credential (user_id, password_hash, password_changed_at) values (?, ?, now())", ana, encoder.encode("old-password-12345"));
    }

    private long user(String email, String name) {
        jdbc.update("insert into app_user (email, email_verified_at, display_name, created_at, updated_at) values (?, now(), ?, now(), now())", email, name);
        return jdbc.queryForObject("select id from app_user where email = ?", Long.class, email);
    }

    private RequestPostProcessor as(long id, String email, String name) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(new AuthUser(id, email, name), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void renameShowsInAccountAndMe() throws Exception {
        mvc.perform(patch("/api/account").with(as(ana, "ana@example.com", "Ana")).with(csrf()).contentType("application/json")
                .content("{\"displayName\":\"Ana Maria\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("Ana Maria"));
        mvc.perform(get("/api/auth/me").with(as(ana, "ana@example.com", "Ana"))).andExpect(jsonPath("$.displayName").value("Ana Maria"));
        mvc.perform(patch("/api/account").with(as(ana, "ana@example.com", "Ana")).with(csrf()).contentType("application/json")
                .content("{\"displayName\":\"\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void changePasswordChecksCurrentAndPolicy() throws Exception {
        var who = as(ana, "ana@example.com", "Ana");
        mvc.perform(post("/api/account/password").with(who).with(csrf()).contentType("application/json")
                .content("{\"currentPassword\":\"wrong-password-1\",\"newPassword\":\"a-brand-new-passphrase\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.currentPassword").exists());
        mvc.perform(post("/api/account/password").with(who).with(csrf()).contentType("application/json")
                .content("{\"currentPassword\":\"old-password-12345\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.newPassword").exists());
        mvc.perform(post("/api/account/password").with(who).with(csrf()).contentType("application/json")
                .content("{\"currentPassword\":\"old-password-12345\",\"newPassword\":\"old-password-12345\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/account/password").with(who).with(csrf()).contentType("application/json")
                .content("{\"currentPassword\":\"old-password-12345\",\"newPassword\":\"a-brand-new-passphrase\"}"))
                .andExpect(status().isOk());
        String hash = jdbc.queryForObject("select password_hash from user_credential where user_id = ?", String.class, ana);
        org.assertj.core.api.Assertions.assertThat(encoder.matches("a-brand-new-passphrase", hash)).isTrue();
    }

    @Test
    void devicesAreListedAndOnlyOwnerCanRemove() throws Exception {
        jdbc.update("insert into trusted_device (user_id, token_hash, user_agent, created_at, last_seen_at) values (?, ?, 'Chrome on Mac', now(), now())", ana, "a".repeat(64));
        long id = jdbc.queryForObject("select id from trusted_device where user_id = ?", Long.class, ana);
        mvc.perform(get("/api/account/devices").with(as(ana, "ana@example.com", "Ana"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userAgent").value("Chrome on Mac")).andExpect(jsonPath("$[0].current").value(false));
        mvc.perform(delete("/api/account/devices/" + id).with(as(ben, "ben@example.com", "Ben")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/account/devices/" + id).with(as(ana, "ana@example.com", "Ana")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/account/devices").with(as(ana, "ana@example.com", "Ana"))).andExpect(jsonPath("$.length()").value(0));
    }
}
