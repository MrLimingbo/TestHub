package io.testhub.platform.apidebug;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.parameter.ProjectEnvironmentRepository;
import io.testhub.platform.parameter.ProjectParameterRepository;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.security.SecretCipher;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

class P1ReplayFailureTests {
    final ApiDebugHistoryRepository history = mock(ApiDebugHistoryRepository.class);
    final AccessService access = mock(AccessService.class);
    final SecretCipher cipher = mock(SecretCipher.class);
    final AuthenticatedPrincipal actor = mock(AuthenticatedPrincipal.class);
    final AuditService audit = mock(AuditService.class);
    final ObjectMapper json = new ObjectMapper();
    P1ReplayFailureTests() {
        when(access.requireProjectParameterWrite(eq(actor), eq("p"), anySet())).thenReturn(new ProjectSummary("p", "o", "QA", "QA", "ACTIVE", "", null, null, null));
        when(actor.userId()).thenReturn("u");
    }
    @Test void unknownCreationTimeFailsClosedWithoutDeletingOrInventingTime() {
        var policy = new ApiDebugReplayPolicy(Clock.systemUTC());
        var row = new ApiDebugHistory("h", "o", "p", "GET", "HTTP", "https://example.test", 200, 1, 0, "COMPLETED", null, "ciphertext", "u", null);
        var result = policy.forRead(row);
        assertEquals("UNKNOWN_CREATED_AT", result.replayStatus()); assertNull(result.replayDraft()); assertNull(result.createdAt());
        assertEquals("ciphertext", row.replayDraft());
    }
    @Test void failedCleanupRetainsDataAndRetriesOnTheNextInvocation() {
        when(history.clearExpiredManagedReplays(any())).thenThrow(new DataAccessResourceFailureException("private-fixture")).thenReturn(1);
        var cleanup = new ApiDebugReplayRetention(history, new ApiDebugReplayPolicy(Clock.systemUTC()));
        assertDoesNotThrow(cleanup::run); assertDoesNotThrow(cleanup::run);
        verify(history, times(2)).clearExpiredManagedReplays(any()); verifyNoMoreInteractions(history);
    }
    @Test void encryptionFailureCannotPersistPlaintextOrReportDraftSuccess() {
        when(cipher.encrypt(anyString())).thenThrow(new IllegalStateException("fixture-encryption-failure"));
        var target = mock(ApiDebugTargetPolicy.class); when(target.normalizeAndAssertAllowed(anyString())).thenReturn(URI.create("https://8.8.8.8"));
        var service = new ApiDebugService(history, mock(ApiDebugTransport.class), access, audit, mock(ProjectParameterRepository.class), cipher, json, mock(ProjectEnvironmentRepository.class), target);
        assertThrows(IllegalStateException.class, () -> service.saveDraft(actor, "p", new ApiDebugService.DebugRequest("POST", "https://8.8.8.8", List.of(), "private-fixture", 1000, null, null, null, "QA")));
        verifyNoInteractions(history, audit);
    }
    @ParameterizedTest @ValueSource(strings = {"NONE", "BEARER", "BASIC", "API_KEY"})
    void grpcUnaryRetainsOnlyActiveAuthenticationFields(String mode) throws Exception {
        var transport = mock(GrpcDebugTransport.class);
        when(transport.unary(any())).thenReturn(new GrpcDebugTransport.Response("{}"));
        when(cipher.encrypt(anyString())).thenAnswer(call -> call.getArgument(0));
        var grpc = new GrpcDebugService(transport, history, access, audit, mock(ProjectParameterRepository.class), mock(ProjectEnvironmentRepository.class), cipher, json, mock(GrpcDescriptorService.class), mock(DesktopApiDebugTargetPolicy.class));
        try {
            grpc.unary(actor, "p", new GrpcDebugService.UnaryRequest("8.8.8.8:443", true, "qa.Echo", "Echo", "{}", 1000, null, List.of(), new GrpcDebugService.AuthInput(mode, "token-marker", "user-marker", "password-marker", "key-marker", "value-marker"), null));
            var captured = org.mockito.ArgumentCaptor.forClass(ApiDebugHistory.class); verify(history).insert(captured.capture());
            String draft = captured.getValue().replayDraft();
            assertEquals(mode.equals("BEARER"), draft.contains("token-marker"));
            assertEquals(mode.equals("BASIC"), draft.contains("password-marker"));
            assertEquals(mode.equals("API_KEY"), draft.contains("value-marker"));
        } finally { grpc.stopServerStreams(); }
    }
}
