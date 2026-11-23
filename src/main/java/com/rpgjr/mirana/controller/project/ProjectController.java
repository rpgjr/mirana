package com.rpgjr.mirana.controller.project;

import com.rpgjr.mirana.service.project.ProjectService;

import com.rpgjr.mirana.security.AuthUser;
import com.rpgjr.mirana.model.dto.project.ProjectDtos.*;
import com.rpgjr.mirana.model.dto.project.TicketFormat;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projects;

    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @GetMapping
    List<ProjectSummary> list(@AuthenticationPrincipal AuthUser user) {
        return projects.list(user.id());
    }

    @PostMapping
    ResponseEntity<ProjectSummary> create(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreateProjectRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projects.create(user.id(), r.name(), r.repos()));
    }

    @PatchMapping("/{slug}")
    ProjectSummary rename(@AuthenticationPrincipal AuthUser user, @PathVariable String slug, @Valid @RequestBody UpdateProjectRequest r) {
        return projects.rename(slug, user.id(), r.name());
    }

    @PutMapping("/{slug}/ticket-format")
    TicketFormat setTicketFormat(@AuthenticationPrincipal AuthUser user, @PathVariable String slug, @Valid @RequestBody UpdateTicketFormatRequest r) {
        return projects.setTicketFormat(slug, user.id(), r);
    }

    @PutMapping("/{slug}/repos")
    List<String> setRepos(@AuthenticationPrincipal AuthUser user, @PathVariable String slug, @Valid @RequestBody SetReposRequest r) {
        return projects.setRepos(slug, user.id(), r.repos());
    }

    @PostMapping("/{slug}/repos")
    List<String> addRepo(@AuthenticationPrincipal AuthUser user, @PathVariable String slug, @Valid @RequestBody AddRepoRequest r) {
        return projects.addRepo(slug, user.id(), r.repo());
    }

    @DeleteMapping("/{slug}/repos")
    List<String> removeRepo(@AuthenticationPrincipal AuthUser user, @PathVariable String slug, @RequestParam String repo) {
        return projects.removeRepo(slug, user.id(), repo);
    }

    @GetMapping("/{slug}/board")
    Board board(@AuthenticationPrincipal AuthUser user, @PathVariable String slug) {
        return projects.board(slug, user.id());
    }

    @PostMapping("/{slug}/tickets")
    ResponseEntity<TicketDetail> createTicket(@AuthenticationPrincipal AuthUser user, @PathVariable String slug,
            @Valid @RequestBody CreateTicketRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projects.createTicket(slug, user.id(), r));
    }

    @GetMapping("/{slug}/tickets/{key}")
    TicketDetail ticket(@AuthenticationPrincipal AuthUser user, @PathVariable String slug, @PathVariable String key) {
        return projects.ticket(slug, key, user.id());
    }

    @PatchMapping("/{slug}/tickets/{key}")
    TicketDetail update(@AuthenticationPrincipal AuthUser user, @PathVariable String slug, @PathVariable String key,
            @Valid @RequestBody UpdateTicketRequest r) {
        return projects.update(slug, key, user.id(), r);
    }

    @PostMapping("/{slug}/tickets/{key}/move")
    TicketDetail move(@AuthenticationPrincipal AuthUser user, @PathVariable String slug, @PathVariable String key,
            @Valid @RequestBody MoveRequest r) {
        return projects.move(slug, key, user.id(), r);
    }

    @PostMapping("/{slug}/tickets/{key}/comments")
    ResponseEntity<TicketDetail> comment(@AuthenticationPrincipal AuthUser user, @PathVariable String slug,
            @PathVariable String key, @Valid @RequestBody CommentRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projects.comment(slug, key, user.id(), r.body()));
    }
}
