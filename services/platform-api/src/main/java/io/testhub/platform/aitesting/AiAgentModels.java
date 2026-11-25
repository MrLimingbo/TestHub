package io.testhub.platform.aitesting;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.testhub.platform.model.ModelProviderController.ReasoningLevel;
import io.testhub.platform.model.ModelProviderService.ChatSelection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AiAgentModels {
    private AiAgentModels() { }

    record Scope(String organizationId, String projectId, String userId) { }

    public record Workspace(String id, String name, boolean archived, long version) { }

    public record WorkspaceInput(@NotBlank @Size(max = 120) String name, boolean archived, @Positive Long version) { }

    public record Agent(String id, String workspaceId, String name, String description, String systemPrompt,
                        ChatSelection model, boolean archived, long version) { }

    public record AgentInput(@NotBlank @Size(max = 26) String workspaceId, @NotBlank @Size(max = 120) String name,
                             @NotNull @Size(max = 500) String description, @NotNull @Size(max = 8000) String systemPrompt,
                             @NotNull @Valid ChatSelection model, boolean archived, @Positive Long version) { }

    public record Conversation(String id, String agentId, String title, Instant updatedAt) { }

    public record Turn(String id, int number, String attemptId, ChatSelection model, String userText, String reply,
                       TurnStatus status, String failureMessage, Instant startedAt, String sourceSummary,
                       @JsonIgnore String promptContext) { }

    public enum TurnStatus { RUNNING, COMPLETED, STOPPED, FAILED }

    public record ConversationDetail(Conversation conversation, List<Turn> turns) { }

    public record SendInput(@Size(max = 26) String previousTurnId, @Size(max = 26) String retryTurnId,
                            @Size(max = 12000) String text, @NotNull @Valid ChatSelection model,
                            @Size(max = 3) List<@Valid @NotNull Attachment> attachments,
                            @Size(max = 3) List<@Valid @NotNull AssetRef> assets) {
        public SendInput {
            attachments = attachments == null ? List.of() : attachments;
            assets = assets == null ? List.of() : assets;
        }
    }

    public enum AssetKind { REQUIREMENT, CASE }
    public record AssetRef(@NotNull AssetKind kind, @NotBlank @Size(max = 26) String id) { }
    public record Attachment(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 10000) String text) { }
    public record AttachmentPreview(String name, String text, boolean truncated) { }

    public record StopInput(@NotBlank @Size(max = 26) String attemptId) { }

    static ReasoningLevel reasoningLevel(String value) {
        return value == null ? null : ReasoningLevel.valueOf(value);
    }
}
