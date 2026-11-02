package io.testhub.platform;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.testhub.platform.storage.ObjectStorage;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationOperationsFixesFlowTests {
    @Autowired private MockMvc mvc;
    @MockitoBean private ObjectStorage storage;

    @BeforeEach void healthyStorage() throws Exception {
        doReturn(false).when(storage).bucketExists(anyString());
        when(storage.label()).thenReturn("QA storage");
    }

    @Test void unavailableStorageFailsReadinessWithoutExposingDetailsOrFailingLiveness() throws Exception {
        when(storage.bucketExists(anyString())).thenThrow(new IOException("QA private storage path"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"status\":\"DOWN\"}"))
                .andExpect(jsonPath("$.components").doesNotExist()).andExpect(jsonPath("$.details").doesNotExist());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
    }

    @Test void aMissingLazyBucketRemainsReady() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
    }

    @Test void anExistingBucketRemainsReady() throws Exception {
        when(storage.bucketExists(anyString())).thenReturn(true);
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
    }

    @Test void readinessRecoversWhenStorageReturns() throws Exception {
        when(storage.bucketExists(anyString())).thenThrow(new IOException("QA transient storage fault"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
        doReturn(false).when(storage).bucketExists(anyString());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
    }
}
