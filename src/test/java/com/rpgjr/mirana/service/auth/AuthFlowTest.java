package com.rpgjr.mirana.service.auth;

import com.rpgjr.mirana.utils.RateLimiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rpgjr.mirana.service.mail.MailService;
import com.rpgjr.mirana.enums.OtpPurpose;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RateLimiter limiter;
    @MockitoBean MailService mail;

    @BeforeEach
    void clean() {
        com.rpgjr.mirana.utils.TestDb.clean(jdbc);
        limiter.reset();
    }

    private MvcResult json(String path, String body, Cookie... cookies) throws Exception {
        var req = post(path).with(csrf()).contentType("application/json").content(body);
        if (cookies.length > 0) {
            req = req.cookie(cookies);
        }
        return mvc.perform(req).andReturn();
    }

    private String lastCode(OtpPurpose purpose) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mail, atLeastOnce()).sendOtp(any(), eq(purpose), code.capture());
        return code.getAllValues().getLast();
    }

    private Cookie[] register(String email) throws Exception {
        assertThat(json("/api/auth/register", """
                {"email":"%s","password":"%s","displayName":"Test User"}""".formatted(email, PASSWORD)).getResponse().getStatus()).isEqualTo(202);
        MvcResult res = json("/api/auth/verify", """
                {"email":"%s","code":"%s"}""".formatted(email, lastCode(OtpPurpose.VERIFY_EMAIL)));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return res.getResponse().getCookies();
    }

    @Test
    void registerVerifyThenMeWorks() throws Exception {
        Cookie[] cookies = register("ana@example.com");
        Cookie session = java.util.Arrays.stream(cookies).filter(c -> c.getName().equals("MIRANA_SESSION")).findFirst().orElseThrow();
        Cookie device = java.util.Arrays.stream(cookies).filter(c -> c.getName().equals("mirana_device")).findFirst().orElseThrow();
        assertThat(device.isHttpOnly()).isTrue();
        assertThat(session.isHttpOnly()).isTrue();
        mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ana@example.com"));
    }

    @Test
    void weakPasswordAndBadEmailRejected() throws Exception {
        assertThat(json("/api/auth/register", """
                {"email":"a@example.com","password":"short","displayName":"A"}""").getResponse().getStatus()).isEqualTo(400);
        assertThat(json("/api/auth/register", """
                {"email":"nope","password":"%s","displayName":"A"}""".formatted(PASSWORD)).getResponse().getStatus()).isEqualTo(400);
        assertThat(json("/api/auth/register", """
                {"email":"x@mailinator.com","password":"%s","displayName":"A"}""".formatted(PASSWORD)).getResponse().getStatus()).isEqualTo(400);
        assertThat(json("/api/auth/register", """
                {"email":"carlos@example.com","password":"carlos-is-great-1","displayName":"A"}""").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void wrongCodeBurnsAfterFiveAttempts() throws Exception {
        json("/api/auth/register", """
                {"email":"b@example.com","password":"%s","displayName":"B"}""".formatted(PASSWORD));
        String real = lastCode(OtpPurpose.VERIFY_EMAIL);
        String wrong = real.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            assertThat(json("/api/auth/verify", """
                    {"email":"b@example.com","code":"%s"}""".formatted(wrong)).getResponse().getStatus()).isEqualTo(400);
        }
        assertThat(json("/api/auth/verify", """
                {"email":"b@example.com","code":"%s"}""".formatted(real)).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void newDeviceNeedsOtpThenTrustedDeviceSkipsIt() throws Exception {
        register("c@example.com");
        String login = """
                {"email":"c@example.com","password":"%s"}""".formatted(PASSWORD);
        MvcResult first = json("/api/auth/login", login);
        assertThat(first.getResponse().getStatus()).isEqualTo(202);
        MvcResult verified = json("/api/auth/login/verify", """
                {"email":"c@example.com","code":"%s"}""".formatted(lastCode(OtpPurpose.NEW_DEVICE)));
        assertThat(verified.getResponse().getStatus()).isEqualTo(200);
        Cookie device = verified.getResponse().getCookie("mirana_device");
        assertThat(json("/api/auth/login", login, device).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void wrongPasswordIs401AndTenFailuresLock() throws Exception {
        register("d@example.com");
        String bad = """
                {"email":"d@example.com","password":"definitely-wrong-pw"}""";
        for (int i = 0; i < 10; i++) {
            assertThat(json("/api/auth/login", bad).getResponse().getStatus()).isEqualTo(401);
        }
        limiter.reset();
        assertThat(json("/api/auth/login", """
                {"email":"d@example.com","password":"%s"}""".formatted(PASSWORD)).getResponse().getStatus()).isEqualTo(429);
        assertThat(json("/api/auth/login", """
                {"email":"ghost@example.com","password":"%s"}""".formatted(PASSWORD)).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void resetPasswordChangesPasswordAndRevokesSessions() throws Exception {
        Cookie[] cookies = register("e@example.com");
        Cookie session = java.util.Arrays.stream(cookies).filter(c -> c.getName().equals("MIRANA_SESSION")).findFirst().orElseThrow();
        assertThat(json("/api/auth/forgot", """
                {"email":"e@example.com"}""").getResponse().getStatus()).isEqualTo(202);
        assertThat(json("/api/auth/forgot", """
                {"email":"nobody@example.com"}""").getResponse().getStatus()).isEqualTo(202);
        String newPw = "a-brand-new-passphrase";
        assertThat(json("/api/auth/reset", """
                {"email":"e@example.com","code":"%s","newPassword":"%s"}""".formatted(lastCode(OtpPurpose.RESET_PASSWORD), newPw))
                .getResponse().getStatus()).isEqualTo(200);
        mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isUnauthorized());
        Cookie device = java.util.Arrays.stream(cookies).filter(c -> c.getName().equals("mirana_device")).findFirst().orElseThrow();
        assertThat(json("/api/auth/login", """
                {"email":"e@example.com","password":"%s"}""".formatted(PASSWORD), device).getResponse().getStatus()).isEqualTo(401);
        assertThat(json("/api/auth/login", """
                {"email":"e@example.com","password":"%s"}""".formatted(newPw), device).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void protectedRoutesNeedLoginAndCsrf() throws Exception {
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
}
