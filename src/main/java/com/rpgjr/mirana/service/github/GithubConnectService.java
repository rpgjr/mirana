package com.rpgjr.mirana.service.github;

import com.rpgjr.mirana.config.GithubProperties;
import com.rpgjr.mirana.exception.ApiException;
import com.rpgjr.mirana.model.dto.github.GithubDtos.*;
import jakarta.servlet.http.HttpSession;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Links a Mirana user to the GitHub accounts where they installed the App, and reads their repositories and pull requests. */
@Service
@Transactional
public class GithubConnectService {

    private static final Logger log = LoggerFactory.getLogger(GithubConnectService.class);
    private static final String STATE = "github.state";
    private static final Pattern TICKET_KEY = Pattern.compile("\\b(FT|HF|BUG)-(\\d{1,9})\\b");

    private final JdbcClient db;
    private final GithubApi github;
    private final GithubProperties props;
    private final SecureRandom random = new SecureRandom();

    public GithubConnectService(JdbcClient db, GithubApi github, GithubProperties props) {
        this.db = db;
        this.github = github;
        this.props = props;
    }

    private record Stored(long installationId, String login, String type, String repoSelection) {
    }

    public StatusResponse status(Long userId, HttpSession session) {
        if (!props.connectConfigured()) {
            return new StatusResponse(false, null, null, List.of());
        }
        String state = stateFor(session);
        String connect = "https://github.com/login/oauth/authorize?client_id=" + enc(props.clientId()) + "&state=" + state;
        String install = "https://github.com/apps/" + enc(props.appSlug()) + "/installations/new?state=" + state;
        List<InstallationView> views = stored(userId).stream().map(s -> new InstallationView(s.installationId(), s.login(),
                s.type(), s.repoSelection(), manageUrl(s))).toList();
        return new StatusResponse(true, connect, install, views);
    }

    /** Called with the code GitHub put on the callback URL. Keeps the installations this GitHub user can reach. */
    public List<InstallationView> connect(Long userId, String code, String state, HttpSession session) {
        requireEnabled();
        Object expected = session.getAttribute(STATE);
        session.removeAttribute(STATE);
        if (!(expected instanceof String e) || !MessageDigest.isEqual(e.getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_STATE", "That sign in expired. Start again from Settings, GitHub.");
        }
        List<GithubApi.Installation> found = github.userInstallations(github.exchangeCode(code));
        if (found.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NO_INSTALLATION",
                    "The Mirana GitHub App is not installed on any account you can use. Install it, then connect again.");
        }
        db.sql("delete from github_installation where user_id = ?").param(userId).update();
        for (GithubApi.Installation i : found) {
            db.sql("""
                    insert into github_installation (user_id, installation_id, account_login, account_type, repo_selection)
                    values (?, ?, ?, ?, ?)""")
                    .params(userId, i.id(), i.login(), i.type(), i.repoSelection()).update();
        }
        return stored(userId).stream().map(s -> new InstallationView(s.installationId(), s.login(), s.type(),
                s.repoSelection(), manageUrl(s))).toList();
    }

    public void disconnect(Long userId, long installationId) {
        db.sql("delete from github_installation where user_id = ? and installation_id = ?").params(userId, installationId).update();
    }

    public List<RepoView> repos(Long userId) {
        requireEnabled();
        Map<String, String> slugs = linkedSlugs(userId);
        List<RepoView> out = new ArrayList<>();
        for (Stored s : stored(userId)) {
            try {
                for (GithubApi.Repo r : github.installationRepos(s.installationId())) {
                    out.add(new RepoView(r.fullName(), r.description(), r.isPrivate(), s.installationId(),
                            slugs.get(r.fullName().toLowerCase(Locale.ROOT))));
                }
            } catch (ApiException ex) {
                log.warn("Could not list repositories for installation {}: {}", s.installationId(), ex.getMessage());
            }
        }
        out.sort((a, b) -> a.fullName().compareToIgnoreCase(b.fullName()));
        return out;
    }

    public List<PullView> pulls(Long userId, String repo) {
        requireEnabled();
        Stored s = installationFor(userId, repo).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "REPO_NOT_CONNECTED", "That repository is not under a GitHub account you connected."));
        return github.pulls(s.installationId(), repo).stream().map(p -> new PullView(p.number(), p.title(), p.state(),
                p.merged(), p.draft(), p.author(), p.url(), p.updatedAt(), keys(p.title()))).toList();
    }

    /** Projects may only track repositories the user can reach through their own installation. Off when GitHub is not set up. */
    public void requireAccess(Long userId, String repo) {
        if (!props.connectConfigured()) {
            return;
        }
        String wanted = repo.toLowerCase(Locale.ROOT);
        boolean ok = installationFor(userId, wanted).map(s -> github.installationRepos(s.installationId()).stream()
                .anyMatch(r -> r.fullName().equalsIgnoreCase(wanted))).orElse(false);
        if (!ok) {
            throw new ApiException(HttpStatus.FORBIDDEN, "REPO_NOT_CONNECTED",
                    "Connect " + repo + " under Settings, GitHub first.", Map.of("repo", "Connect this repository under Settings, GitHub first."));
        }
    }

    private java.util.Optional<Stored> installationFor(Long userId, String repo) {
        String owner = repo.contains("/") ? repo.substring(0, repo.indexOf('/')) : repo;
        return stored(userId).stream().filter(s -> s.login().equalsIgnoreCase(owner)).findFirst();
    }

    private List<Stored> stored(Long userId) {
        return db.sql("select installation_id, account_login, account_type, repo_selection from github_installation where user_id = ? order by account_login")
                .param(userId).query((rs, i) -> new Stored(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
    }

    private Map<String, String> linkedSlugs(Long userId) {
        Map<String, String> map = new java.util.HashMap<>();
        db.sql("""
                select pr.repo, p.slug from project_repo pr
                join project p on p.id = pr.project_id
                join project_member m on m.project_id = p.id and m.user_id = ?""")
                .param(userId).query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2))).list()
                .forEach(e -> map.put(e.getKey(), e.getValue()));
        return map;
    }

    private String stateFor(HttpSession session) {
        Object current = session.getAttribute(STATE);
        if (current instanceof String s) {
            return s;
        }
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String s = HexFormat.of().formatHex(bytes);
        session.setAttribute(STATE, s);
        return s;
    }

    private void requireEnabled() {
        if (!props.connectConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_OFF", "GitHub connect is not set up on this server.");
        }
    }

    private static String manageUrl(Stored s) {
        return "Organization".equalsIgnoreCase(s.type())
                ? "https://github.com/organizations/" + s.login() + "/settings/installations/" + s.installationId()
                : "https://github.com/settings/installations/" + s.installationId();
    }

    private static List<String> keys(String title) {
        Set<String> found = new java.util.LinkedHashSet<>();
        Matcher m = TICKET_KEY.matcher(title == null ? "" : title);
        while (m.find()) {
            found.add(m.group(1) + "-" + m.group(2));
        }
        return List.copyOf(found);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
