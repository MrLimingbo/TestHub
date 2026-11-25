package io.testhub.platform.aitesting;

import io.testhub.platform.model.ModelChatCancellation;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static io.testhub.platform.aitesting.AiAgentModels.*;

@Service
class AiChatStreamService {
    private static final Logger log = LoggerFactory.getLogger(AiChatStreamService.class);
    private final AiAgentService agents;
    private final Map<String, ModelChatCancellation> active = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, 12, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().name("ai-chat-", 0).factory());

    AiChatStreamService(AiAgentService agents) { this.agents = agents; }

    SseEmitter stream(Scope scope, String conversationId, SendInput input, AiContextService.Context context) {
        var emitter = new SseEmitter(240_000L);
        var cancellation = new ModelChatCancellation();
        emitter.onCompletion(cancellation::cancel);
        emitter.onTimeout(cancellation::cancel);
        emitter.onError(error -> cancellation.cancel());
        try {
            workers.execute(() -> run(scope, conversationId, input, context, emitter, cancellation));
        } catch (RejectedExecutionException exception) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "智能体当前繁忙，请稍后重试");
        }
        return emitter;
    }

    void stop(Scope scope, String conversationId, String turnId, String attemptId) {
        if (agents.finish(scope, conversationId, turnId, attemptId, TurnStatus.STOPPED, null, null)) {
            var cancellation = active.get(attemptId);
            if (cancellation != null) cancellation.cancel();
        }
    }

    private void run(Scope scope, String conversationId, SendInput input, AiContextService.Context context, SseEmitter emitter,
                     ModelChatCancellation cancellation) {
        Turn turn = null;
        try {
            cancellation.check();
            var inference = agents.begin(scope, conversationId, input, context);
            turn = inference.turn();
            active.put(turn.attemptId(), cancellation);
            emit(emitter, "turn", turn);
            StringBuilder reply = new StringBuilder();
            inference.model().stream(inference.context(), cancellation, delta -> {
                cancellation.check();
                emit(emitter, "delta", Map.of("text", delta));
                reply.append(delta);
            });
            cancellation.check();
            boolean saved = agents.finish(scope, conversationId, turn.id(), turn.attemptId(), TurnStatus.COMPLETED,
                    reply.toString(), null);
            emit(emitter, "done", Map.of("status", saved ? "COMPLETED" : "STOPPED"));
        } catch (CancellationException exception) {
            finish(scope, conversationId, turn, TurnStatus.STOPPED, null);
            sendTerminalEvent(emitter, "done", Map.of("status", "STOPPED"));
        } catch (UncheckedIOException exception) {
            finish(scope, conversationId, turn, TurnStatus.STOPPED, null);
        } catch (ResponseStatusException exception) {
            String reason = exception.getReason() == null ? "智能体回复失败，请重试" : exception.getReason();
            finish(scope, conversationId, turn, TurnStatus.FAILED, reason);
            sendTerminalEvent(emitter, "failure", Map.of("message", reason));
        } catch (RuntimeException exception) {
            log.error("AI conversation failed (exception type {})", exception.getClass().getSimpleName());
            String reason = "智能体回复失败，请稍后重试";
            finish(scope, conversationId, turn, TurnStatus.FAILED, reason);
            sendTerminalEvent(emitter, "failure", Map.of("message", reason));
        } finally {
            if (turn != null) active.remove(turn.attemptId());
            emitter.complete();
        }
    }

    private void finish(Scope scope, String conversationId, Turn turn, TurnStatus status, String reason) {
        if (turn == null) return;
        try {
            agents.finish(scope, conversationId, turn.id(), turn.attemptId(), status, null, reason);
        } catch (RuntimeException exception) {
            log.error("Could not update AI turn status (exception type {})", exception.getClass().getSimpleName());
        }
    }

    private void sendTerminalEvent(SseEmitter emitter, String event, Object payload) {
        try {
            emit(emitter, event, payload);
        } catch (UncheckedIOException | CancellationException ignored) {
            // The browser already disconnected. The persisted turn carries the outcome.
        }
    }

    private void emit(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (IllegalStateException exception) {
            throw new CancellationException();
        }
    }

    @PreDestroy
    void shutdown() {
        active.values().forEach(ModelChatCancellation::cancel);
        workers.shutdownNow();
    }
}
