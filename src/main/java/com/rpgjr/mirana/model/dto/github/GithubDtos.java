package com.rpgjr.mirana.model.dto.github;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public final class GithubDtos {

    private GithubDtos() {
    }

    public record InstallationView(long installationId, String login, String type, String repoSelection, String manageUrl) {
    }

    /** connectUrl signs the user in with GitHub. installUrl adds the App to an account. Both null when GitHub is off. */
    public record StatusResponse(boolean enabled, String connectUrl, String installUrl, List<InstallationView> installations) {
    }

    public record RepoView(String fullName, String description, boolean isPrivate, long installationId, String projectSlug) {
    }

    public record PullView(int number, String title, String state, boolean merged, boolean draft, String author,
                           String url, String updatedAt, List<String> ticketKeys) {
    }

    public record ConnectRequest(@NotBlank String code, @NotBlank String state) {
    }
}
