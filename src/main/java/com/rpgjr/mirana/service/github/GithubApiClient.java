package com.rpgjr.mirana.service.github;

import com.rpgjr.mirana.config.GithubProperties;
import com.rpgjr.mirana.exception.ApiException;
import com.rpgjr.mirana.utils.GithubAppJwt;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

@Component
public class GithubApiClient implements GithubApi {

    private record CachedToken(String value, Instant expires) {
    }

    private final GithubProperties props;
    private final RestClient api = RestClient.builder().baseUrl("https://api.github.com")
            .defaultHeader("Accept", "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28").build();
    private final Map<Long, CachedToken> tokens = new ConcurrentHashMap<>();

    public GithubApiClient(GithubProperties props) {
        this.props = props;
    }

    @Override
    public String exchangeCode(String code) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", props.clientId());
        form.add("client_secret", props.clientSecret());
        form.add("code", code);
        JsonNode out = call(() -> RestClient.create().post().uri("https://github.com/login/oauth/access_token")
                .accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(JsonNode.class));
        if (out == null || !out.hasNonNull("access_token")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GITHUB_CODE", "GitHub did not accept that sign in. Try connecting again.");
        }
        return out.get("access_token").asString();
    }

    @Override
    public List<Installation> userInstallations(String userToken) {
        JsonNode out = call(() -> api.get().uri("/user/installations?per_page=100")
                .header("Authorization", "Bearer " + userToken).retrieve().body(JsonNode.class));
        List<Installation> list = new ArrayList<>();
        if (out != null) {
            for (JsonNode n : out.path("installations")) {
                list.add(new Installation(n.path("id").asLong(), n.path("account").path("login").asString(),
                        n.path("account").path("type").asString("User"), n.path("repository_selection").asString("selected")));
            }
        }
        return list;
    }

    @Override
    public List<Repo> installationRepos(long installationId) {
        String token = installationToken(installationId);
        List<Repo> repos = new ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            int p = page;
            JsonNode out = call(() -> api.get().uri("/installation/repositories?per_page=100&page=" + p)
                    .header("Authorization", "Bearer " + token).retrieve().body(JsonNode.class));
            JsonNode batch = out == null ? null : out.path("repositories");
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (JsonNode n : batch) {
                repos.add(new Repo(n.path("full_name").asString(), n.path("description").asString(""), n.path("private").asBoolean()));
            }
            if (batch.size() < 100) {
                break;
            }
        }
        return repos;
    }

    @Override
    public List<Pull> pulls(long installationId, String repoFullName) {
        String token = installationToken(installationId);
        JsonNode out = call(() -> api.get().uri("/repos/" + repoFullName + "/pulls?state=all&sort=updated&direction=desc&per_page=30")
                .header("Authorization", "Bearer " + token).retrieve().body(JsonNode.class));
        List<Pull> list = new ArrayList<>();
        if (out != null) {
            for (JsonNode n : out) {
                list.add(new Pull(n.path("number").asInt(), n.path("title").asString(), n.path("state").asString(),
                        n.hasNonNull("merged_at"), n.path("draft").asBoolean(), n.path("user").path("login").asString(""),
                        n.path("html_url").asString(), n.path("updated_at").asString("")));
            }
        }
        return list;
    }

    private String installationToken(long installationId) {
        CachedToken cached = tokens.get(installationId);
        if (cached != null && cached.expires().isAfter(Instant.now().plusSeconds(120))) {
            return cached.value();
        }
        String jwt = GithubAppJwt.create(props.appId(), props.privateKey(), Instant.now());
        JsonNode out = call(() -> api.post().uri("/app/installations/" + installationId + "/access_tokens")
                .header("Authorization", "Bearer " + jwt).retrieve().body(JsonNode.class));
        if (out == null || !out.hasNonNull("token")) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_ERROR", "GitHub did not return an access token.");
        }
        String token = out.get("token").asString();
        tokens.put(installationId, new CachedToken(token, Instant.now().plusSeconds(55 * 60)));
        return token;
    }

    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_ERROR", "GitHub could not be reached or refused the request.");
        }
    }
}
