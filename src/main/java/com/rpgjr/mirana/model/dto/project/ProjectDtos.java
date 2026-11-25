package com.rpgjr.mirana.model.dto.project;


import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ProjectDtos {

    public static final String PREFIX_PATTERN = "^[A-Za-z][A-Za-z0-9]{1,5}$";
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

    public record UpdateProjectRequest(
            @NotBlank(message = "Enter a project name.") @Size(max = 100, message = "Name is too long.") String name) {
    }

    public record SetReposRequest(@Size(max = 10, message = "A project can have at most 10 repositories.") List<@Pattern(regexp = REPO_PATTERN, message = "Use the form owner/repo.") String> repos) {
    }

    public record UpdateTicketFormatRequest(
            @Min(value = 1, message = "Use 1 to 8 digits.") @Max(value = 8, message = "Use 1 to 8 digits.") int digits,
            @NotBlank(message = "Enter a prefix.") @Pattern(regexp = PREFIX_PATTERN, message = "Use 2 to 6 letters or digits, starting with a letter.") String prefix) {
    }

    public static final String COLOR_PATTERN = "^#[0-9a-fA-F]{6}$";

    public record AddColumnRequest(
            @NotBlank(message = "Enter a column name.") @Size(max = 40, message = "Name is too long.") String name,
            @NotBlank(message = "Pick a color.") @Pattern(regexp = COLOR_PATTERN, message = "Use a hex color like #3b82f6.") String color) {
    }

    public record UpdateColumnRequest(
            @Size(min = 1, max = 40, message = "Use 1 to 40 characters.") String name,
            @Pattern(regexp = COLOR_PATTERN, message = "Use a hex color like #3b82f6.") String color) {
    }

    public record ReorderColumnsRequest(@NotNull(message = "Send the column order.") List<Long> ids) {
    }

    public record TagView(Long id, String name, String color) {
    }

    public record PriorityView(Long id, String name, String color) {
    }

    public record AddTagRequest(
            @NotBlank(message = "Enter a tag name.") @Size(max = 30, message = "Name is too long.") String name,
            @NotBlank(message = "Pick a color.") @Pattern(regexp = COLOR_PATTERN, message = "Use a hex color like #3b82f6.") String color) {
    }

    public record UpdateTagRequest(
            @Size(min = 1, max = 30, message = "Use 1 to 30 characters.") String name,
            @Pattern(regexp = COLOR_PATTERN, message = "Use a hex color like #3b82f6.") String color) {
    }

    public record AddPriorityRequest(
            @NotBlank(message = "Enter a name.") @Size(max = 20, message = "Name is too long.") String name,
            @NotBlank(message = "Pick a color.") @Pattern(regexp = COLOR_PATTERN, message = "Use a hex color like #3b82f6.") String color) {
    }

    public record UpdatePriorityRequest(
            @Size(min = 1, max = 20, message = "Use 1 to 20 characters.") String name,
            @Pattern(regexp = COLOR_PATTERN, message = "Use a hex color like #3b82f6.") String color) {
    }

    public record Member(Long id, String displayName, String email, String role) {
    }

    public record Assignee(Long id, String name) {
    }

    public record TicketCard(Long id, String key, String title, Long priorityId,
            List<Assignee> assignees, int prCount, String prState, int commentCount, List<Long> tagIds, Instant createdAt) {
    }

    public record Column(Long id, String name, String status, String color, int position, List<TicketCard> tickets) {
    }

    public record Board(ProjectSummary project, List<Column> columns, List<Member> members, TicketFormat format,
            List<TagView> tags, List<PriorityView> priorities) {
    }

    public record CreateTicketRequest(
            @NotBlank(message = "Enter a title.") @Size(max = 200, message = "Title is too long.") String title,
            @Size(max = 20000, message = "Description is too long.") String description,
            Long priorityId,
            List<Long> assigneeIds,
            Long columnId,
            List<Long> tagIds) {
    }

    public record UpdateTicketRequest(
            @Size(min = 1, max = 200, message = "Title must be 1 to 200 characters.") String title,
            @Size(max = 20000, message = "Description is too long.") String description,
            Long priorityId,
            List<Long> assigneeIds,
            List<Long> tagIds) {
    }

    public record MoveRequest(@NotNull(message = "Pick a column.") Long columnId, int position) {
    }

    public record CommentRequest(@NotBlank(message = "Write a comment.") @Size(max = 5000, message = "Comment is too long.") String body) {
    }

    public record PrLink(Long id, String repo, int number, String branch, String title, String url, String state) {
    }

    public record Comment(Long id, Long authorId, String authorName, String body, Instant createdAt) {
    }

    public record Event(Long id, String actorType, String actorName, String type, String detail, Instant createdAt,
            String repo, String branch, String url) {
    }

    public record TicketDetail(Long id, String key, String title, String description, Long priorityId,
            Long columnId, String columnName, String status, List<Assignee> assignees,
            String createdByName, Instant createdAt, Instant updatedAt,
            List<PrLink> pullRequests, List<Comment> comments, List<Event> events, List<Long> tagIds) {
    }
}
