package com.rpgjr.mirana.controller.github;

import com.rpgjr.mirana.model.dto.github.GithubDtos.*;
import com.rpgjr.mirana.security.AuthUser;
import com.rpgjr.mirana.service.github.GithubConnectService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/github")
public class GithubController {

    private final GithubConnectService github;

    public GithubController(GithubConnectService github) {
        this.github = github;
    }

    @GetMapping("/status")
    StatusResponse status(@AuthenticationPrincipal AuthUser user, HttpSession session) {
        return github.status(user.id(), session);
    }

    @PostMapping("/connect")
    List<InstallationView> connect(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody ConnectRequest r, HttpSession session) {
        return github.connect(user.id(), r.code(), r.state(), session);
    }

    @DeleteMapping("/installations/{installationId}")
    ResponseEntity<Void> disconnect(@AuthenticationPrincipal AuthUser user, @PathVariable long installationId) {
        github.disconnect(user.id(), installationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/repos")
    List<RepoView> repos(@AuthenticationPrincipal AuthUser user) {
        return github.repos(user.id());
    }

    @GetMapping("/pulls")
    List<PullView> pulls(@AuthenticationPrincipal AuthUser user, @RequestParam String repo) {
        return github.pulls(user.id(), repo);
    }
}
