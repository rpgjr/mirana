package com.rpgjr.mirana.service.github;

import java.util.List;

/** What Mirana asks of GitHub. One implementation talks to api.github.com, tests swap in a fake. */
public interface GithubApi {

    record Installation(long id, String login, String type, String repoSelection) {
    }

    record Repo(String fullName, String description, boolean isPrivate) {
    }

    record Pull(int number, String title, String state, boolean merged, boolean draft, String author, String url,
                String updatedAt) {
    }

    /** Trades the code GitHub sent to the browser for the signed in user's access token. */
    String exchangeCode(String code);

    /** Installations of this App that the token's user can use. This is the proof of ownership. */
    List<Installation> userInstallations(String userToken);

    List<Repo> installationRepos(long installationId);

    List<Pull> pulls(long installationId, String repoFullName);
}
