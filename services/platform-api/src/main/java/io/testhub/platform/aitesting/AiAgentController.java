package io.testhub.platform.aitesting;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.functional.FunctionalCase;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.multipart.MultipartFile;

import static io.testhub.platform.aitesting.AiAgentModels.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/ai-testing")
public class AiAgentController {
    private final AiAgentService agents;
    private final AiChatStreamService chat;
    private final AiContextService context;
    private final AiCaseDraftService drafts;

    AiAgentController(AiAgentService agents, AiChatStreamService chat, AiContextService context, AiCaseDraftService drafts) {
        this.agents = agents;
        this.chat = chat;
        this.context = context;
        this.drafts = drafts;
    }

    @GetMapping
    ApiResponse<AiAgentService.Overview> overview(@AuthenticationPrincipal AuthenticatedPrincipal actor,
                                                   @PathVariable String projectId) {
        return ApiResponse.of(agents.overview(actor, projectId));
    }

    @PostMapping("/workspaces")
    ApiResponse<Workspace> createWorkspace(@AuthenticationPrincipal AuthenticatedPrincipal actor, @PathVariable String projectId,
                                            @Valid @RequestBody WorkspaceInput input) {
        return ApiResponse.of(agents.saveWorkspace(actor, projectId, null, input));
    }

    @PutMapping("/workspaces/{workspaceId}")
    ApiResponse<Workspace> updateWorkspace(@AuthenticationPrincipal AuthenticatedPrincipal actor, @PathVariable String projectId,
                                            @PathVariable String workspaceId, @Valid @RequestBody WorkspaceInput input) {
        return ApiResponse.of(agents.saveWorkspace(actor, projectId, workspaceId, input));
    }

    @PostMapping("/agents")
    ApiResponse<Agent> createAgent(@AuthenticationPrincipal AuthenticatedPrincipal actor, @PathVariable String projectId,
                                    @Valid @RequestBody AgentInput input) {
        return ApiResponse.of(agents.saveAgent(actor, projectId, null, input));
    }

    @PutMapping("/agents/{agentId}")
    ApiResponse<Agent> updateAgent(@AuthenticationPrincipal AuthenticatedPrincipal actor, @PathVariable String projectId,
                                    @PathVariable String agentId, @Valid @RequestBody AgentInput input) {
        return ApiResponse.of(agents.saveAgent(actor, projectId, agentId, input));
    }

    @GetMapping("/agents/{agentId}/conversations")
    ApiResponse<List<Conversation>> conversations(@AuthenticationPrincipal AuthenticatedPrincipal actor,
                                                   @PathVariable String projectId, @PathVariable String agentId) {
        return ApiResponse.of(agents.conversations(actor, projectId, agentId));
    }

    @PostMapping("/agents/{agentId}/conversations")
    ApiResponse<Conversation> createConversation(@AuthenticationPrincipal AuthenticatedPrincipal actor,
                                                  @PathVariable String projectId, @PathVariable String agentId) {
        return ApiResponse.of(agents.createConversation(actor, projectId, agentId));
    }

    @GetMapping("/conversations/{conversationId}")
    ApiResponse<ConversationDetail> detail(@AuthenticationPrincipal AuthenticatedPrincipal actor,
                                            @PathVariable String projectId, @PathVariable String conversationId) {
        return ApiResponse.of(agents.detail(actor, projectId, conversationId));
    }

    @PostMapping(value = "/attachments/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<AttachmentPreview> attachment(@AuthenticationPrincipal AuthenticatedPrincipal actor,
                                               @PathVariable String projectId, @RequestParam("file") MultipartFile file) {
        agents.writeScope(actor, projectId);
        return ApiResponse.of(context.preview(file));
    }

    @PostMapping("/case-drafts")
    ApiResponse<List<FunctionalCase>> saveDrafts(@AuthenticationPrincipal AuthenticatedPrincipal actor,
                                                  @PathVariable String projectId,
                                                  @Valid @RequestBody AiCaseDraftService.DraftImport request) {
        return ApiResponse.of(drafts.save(actor, projectId, request));
    }

    @PostMapping(value = "/conversations/{conversationId}/turns", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> send(@AuthenticationPrincipal AuthenticatedPrincipal actor, @PathVariable String projectId,
                                    @PathVariable String conversationId, @Valid @RequestBody SendInput input) {
        var scope = agents.authorizeChat(actor, projectId, conversationId);
        var sources = input.retryTurnId() == null ? context.build(actor, projectId, input) : new AiContextService.Context("", "");
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no").body(chat.stream(scope, conversationId, input, sources));
    }

    @PostMapping("/conversations/{conversationId}/turns/{turnId}/stop")
    ResponseEntity<Void> stop(@AuthenticationPrincipal AuthenticatedPrincipal actor, @PathVariable String projectId,
                              @PathVariable String conversationId, @PathVariable String turnId, @Valid @RequestBody StopInput input) {
        chat.stop(agents.authorizeChat(actor, projectId, conversationId), conversationId, turnId, input.attemptId());
        return ResponseEntity.noContent().build();
    }
}
