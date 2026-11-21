package com.rpgjr.mirana.model.dto.project;

import com.rpgjr.mirana.enums.Priority;
import com.rpgjr.mirana.enums.TicketType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ProjectDtos {

    public static final String REPO_PATTERN = "^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$";

    private ProjectDtos() {
    }

    public record ProjectSummary(Long id, String name, String slug, List<String> repos, String role, int ticketCount) {
    }

    public record CreateProjectRequest(
            @NotBlank(message = "Enter a project name.") @Size(max = 100, message = "Name is too long.") String name,
            @Size(max = 10, message = "A project can have at most 10 repositories.")
            List<@Pattern(regexp = REPO_PATTERN, message = "Use owner/repo, like rpgjr/hophop.") String> repos) {
    }

    public record AddRepoRequest(
            @NotBlank(message = "Enter a repository.")
            @Pattern(regexp = REPO_PATTERN, message = "Use owner/repo, like rpgjr/hophop.") String repo) {
    }

    public record Member(Long id, String displayName, String email, String role) {
    }

    public record TicketCard(Long id, String key, TicketType type, String title, Priority priority,
            Long assigneeId, String assigneeName, int prCount, String prState, int commentCount) {
    }

    public record Column(Long id, String name, String status, int position, List<TicketCard> tickets) {
    }

    public record Board(ProjectSummary project, List<Column> columns, List<Member> members) {
    }

    public record CreateTicketRequest(
            @NotNull(message = "Pick a type.") TicketType type,
            @NotBlank(message = "Enter a title.") @Size(max = 200, message = "Title is too long.") String title,
            @Size(max = 20000, message = "Description is too long.") String description,
            Priority priority,
            Long assigneeId,
            Long columnId) {
    }

    public record UpdateTicketRequest(
            @Size(min = 1, max = 200, message = "Title must be 1 to 200 characters.") String title,
            @Size(max = 20000, message = "Description is too long.") String description,
            Priority priority,
            Long assigneeId,
            Boolean clearAssignee) {
    }

    public record MoveRequest(@NotNull(message = "Pick a column.") Long columnId, int position) {
    }

    public record CommentRequest(@NotBlank(message = "Write a comment.") @Size(max = 5000, message = "Comment is too long.") String body) {
    }

    public record PrLink(Long id, String repo, int number, String branch, String title, String url, String state) {
    }

    public record Comment(Long id, Long authorId, String authorName, String body, Instant createdAt) {
    }

    public record Event(Long id, String actorType, String actorName, String type, String detail, Instant createdAt) {
    }

    public record TicketDetail(Long id, String key, TicketType type, String title, String description, Priority priority,
            Long columnId, String columnName, String status, Long assigneeId, String assigneeName,
            String createdByName, Instant createdAt, Instant updatedAt,
            List<PrLink> pullRequests, List<Comment> comments, List<Event> events) {
    }
}
