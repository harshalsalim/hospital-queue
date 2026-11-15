package com.hospitalqueue.patient.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.common.exception.GlobalExceptionHandler;
import com.hospitalqueue.patient.dto.TokenResponse;
import com.hospitalqueue.patient.entity.TokenStatus;
import com.hospitalqueue.patient.service.QueueService;

/**
 * API tests for {@link QueueController} together with {@link GlobalExceptionHandler}.
 * The service is faked and no Spring context starts, so these run in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class QueueControllerTest {

    private static final UUID TOKEN_ID = UUID.randomUUID();
    private static final UUID SLOT_ID = UUID.randomUUID();

    @Mock
    private QueueService queueService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new QueueController(queueService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static TokenResponse token(TokenStatus status) {
        return new TokenResponse(TOKEN_ID, UUID.randomUUID(), UUID.randomUUID(), SLOT_ID, 3, status,
                Instant.parse("2026-11-12T04:30:00Z"));
    }

    // ---- check-in ----

    @Test
    void checkIn_returns200WithTheWaitingToken() throws Exception {
        when(queueService.checkIn(TOKEN_ID)).thenReturn(token(TokenStatus.WAITING));

        mockMvc.perform(post("/api/queue/tokens/{id}/check-in", TOKEN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.tokenNumber").value(3));
    }

    @Test
    void checkIn_unknownTokenReturns404() throws Exception {
        when(queueService.checkIn(TOKEN_ID)).thenThrow(ApiException.notFound("Token", TOKEN_ID));

        mockMvc.perform(post("/api/queue/tokens/{id}/check-in", TOKEN_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void checkIn_aTokenPastThisStepReturns409() throws Exception {
        when(queueService.checkIn(TOKEN_ID))
                .thenThrow(ApiException.conflict("INVALID_TOKEN_STATE", "A token that is COMPLETED cannot be checked in"));

        mockMvc.perform(post("/api/queue/tokens/{id}/check-in", TOKEN_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN_STATE"));
    }

    @Test
    void checkIn_withAnIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(post("/api/queue/tokens/abc/check-in"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(queueService);
    }

    // ---- call next ----

    @Test
    void callNext_returns200WithTheTokenNowInConsultation() throws Exception {
        when(queueService.callNext(SLOT_ID)).thenReturn(token(TokenStatus.IN_CONSULTATION));

        mockMvc.perform(post("/api/queue/slots/{id}/next", SLOT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_CONSULTATION"));
    }

    @Test
    void callNext_unknownSessionReturns404() throws Exception {
        when(queueService.callNext(SLOT_ID)).thenThrow(ApiException.notFound("Slot", SLOT_ID));

        mockMvc.perform(post("/api/queue/slots/{id}/next", SLOT_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void callNext_whileAConsultationIsInProgressReturns409() throws Exception {
        when(queueService.callNext(SLOT_ID)).thenThrow(
                ApiException.conflict("CONSULTATION_IN_PROGRESS", "Finish the current consultation before calling the next patient"));

        mockMvc.perform(post("/api/queue/slots/{id}/next", SLOT_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSULTATION_IN_PROGRESS"));
    }

    @Test
    void callNext_withNobodyWaitingReturns409() throws Exception {
        when(queueService.callNext(SLOT_ID)).thenThrow(ApiException.conflict("QUEUE_EMPTY", "No patient is waiting"));

        mockMvc.perform(post("/api/queue/slots/{id}/next", SLOT_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUEUE_EMPTY"));
    }

    @Test
    void callNext_withAnIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(post("/api/queue/slots/abc/next"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(queueService);
    }

    // ---- complete ----

    @Test
    void complete_returns200WithTheCompletedToken() throws Exception {
        when(queueService.complete(TOKEN_ID)).thenReturn(token(TokenStatus.COMPLETED));

        mockMvc.perform(post("/api/queue/tokens/{id}/complete", TOKEN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void complete_aPatientWhoWasNeverCalledReturns409() throws Exception {
        when(queueService.complete(TOKEN_ID))
                .thenThrow(ApiException.conflict("INVALID_TOKEN_STATE", "A token that is BOOKED cannot be completed"));

        mockMvc.perform(post("/api/queue/tokens/{id}/complete", TOKEN_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN_STATE"));
    }

    @Test
    void complete_unknownTokenReturns404() throws Exception {
        when(queueService.complete(TOKEN_ID)).thenThrow(ApiException.notFound("Token", TOKEN_ID));

        mockMvc.perform(post("/api/queue/tokens/{id}/complete", TOKEN_ID))
                .andExpect(status().isNotFound());
    }

    // ---- no-show ----

    @Test
    void noShow_returns200WithTheNoShowToken() throws Exception {
        when(queueService.markNoShow(TOKEN_ID)).thenReturn(token(TokenStatus.NO_SHOW));

        mockMvc.perform(post("/api/queue/tokens/{id}/no-show", TOKEN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_SHOW"));
    }

    @Test
    void noShow_anAlreadyFinishedTokenReturns409() throws Exception {
        when(queueService.markNoShow(TOKEN_ID))
                .thenThrow(ApiException.conflict("INVALID_TOKEN_STATE", "A token that is COMPLETED cannot be marked as no-show"));

        mockMvc.perform(post("/api/queue/tokens/{id}/no-show", TOKEN_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN_STATE"));
    }

    @Test
    void noShow_unknownTokenReturns404() throws Exception {
        when(queueService.markNoShow(TOKEN_ID)).thenThrow(ApiException.notFound("Token", TOKEN_ID));

        mockMvc.perform(post("/api/queue/tokens/{id}/no-show", TOKEN_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void theQueueEndpointsOnlyAcceptPost() throws Exception {
        mockMvc.perform(get("/api/queue/slots/{id}/next", SLOT_ID))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("HTTP_405"));

        verifyNoInteractions(queueService);
    }
}
