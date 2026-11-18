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
import com.hospitalqueue.patient.dto.QueueStatusResponse;
import com.hospitalqueue.patient.dto.TokenPositionResponse;
import com.hospitalqueue.patient.entity.TokenStatus;
import com.hospitalqueue.patient.service.QueueStatusService;

/**
 * API tests for {@link QueueStatusController} together with {@link GlobalExceptionHandler}.
 * The service is faked and no Spring context starts, so these run in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class QueueStatusControllerTest {

    private static final UUID SLOT_ID = UUID.randomUUID();
    private static final UUID TOKEN_ID = UUID.randomUUID();
    private static final Instant AS_OF = Instant.parse("2026-11-12T04:30:00Z");

    @Mock
    private QueueStatusService queueStatusService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new QueueStatusController(queueStatusService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getSlotQueue_returns200WithTheLiveQueue() throws Exception {
        when(queueStatusService.getSlotQueue(SLOT_ID))
                .thenReturn(new QueueStatusResponse(SLOT_ID, 4, 5, 3, 2, 10, 1, 0, 10, AS_OF));

        mockMvc.perform(get("/api/slots/{slotId}/queue", SLOT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotId").value(SLOT_ID.toString()))
                .andExpect(jsonPath("$.nowServingTokenNumber").value(4))
                .andExpect(jsonPath("$.nextWaitingTokenNumber").value(5))
                .andExpect(jsonPath("$.waitingCount").value(3))
                .andExpect(jsonPath("$.bookedNotArrivedCount").value(2))
                .andExpect(jsonPath("$.averageConsultationMinutes").value(10));
    }

    @Test
    void getSlotQueue_whenNobodyIsServedShowsNull() throws Exception {
        when(queueStatusService.getSlotQueue(SLOT_ID))
                .thenReturn(new QueueStatusResponse(SLOT_ID, null, null, 0, 0, 0, 0, 0, 10, AS_OF));

        mockMvc.perform(get("/api/slots/{slotId}/queue", SLOT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nowServingTokenNumber").value((Object) null))
                .andExpect(jsonPath("$.nextWaitingTokenNumber").value((Object) null));
    }

    @Test
    void getSlotQueue_unknownSessionReturns404() throws Exception {
        when(queueStatusService.getSlotQueue(SLOT_ID)).thenThrow(ApiException.notFound("Slot", SLOT_ID));

        mockMvc.perform(get("/api/slots/{slotId}/queue", SLOT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void getSlotQueue_withAnIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/slots/abc/queue"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(queueStatusService);
    }

    @Test
    void getTokenPosition_returns200WithTheTokensPlaceInTheQueue() throws Exception {
        when(queueStatusService.getTokenPosition(SLOT_ID, TOKEN_ID))
                .thenReturn(new TokenPositionResponse(TOKEN_ID, 7, TokenStatus.WAITING, 2, 20, 5, AS_OF));

        mockMvc.perform(get("/api/slots/{slotId}/queue/tokens/{tokenId}", SLOT_ID, TOKEN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenNumber").value(7))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.tokensAhead").value(2))
                .andExpect(jsonPath("$.estimatedWaitMinutes").value(20))
                .andExpect(jsonPath("$.nowServingTokenNumber").value(5));
    }

    @Test
    void getTokenPosition_ofAFinishedTokenHasNoEstimate() throws Exception {
        when(queueStatusService.getTokenPosition(SLOT_ID, TOKEN_ID))
                .thenReturn(new TokenPositionResponse(TOKEN_ID, 1, TokenStatus.COMPLETED, 0, null, 5, AS_OF));

        mockMvc.perform(get("/api/slots/{slotId}/queue/tokens/{tokenId}", SLOT_ID, TOKEN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.estimatedWaitMinutes").value((Object) null));
    }

    @Test
    void getTokenPosition_unknownTokenReturns404() throws Exception {
        when(queueStatusService.getTokenPosition(SLOT_ID, TOKEN_ID)).thenThrow(ApiException.notFound("Token", TOKEN_ID));

        mockMvc.perform(get("/api/slots/{slotId}/queue/tokens/{tokenId}", SLOT_ID, TOKEN_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void getTokenPosition_withAnIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/slots/{slotId}/queue/tokens/abc", SLOT_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(queueStatusService);
    }

    @Test
    void theLiveQueueEndpointsOnlyAcceptGet() throws Exception {
        mockMvc.perform(post("/api/slots/{slotId}/queue", SLOT_ID))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("HTTP_405"));

        verifyNoInteractions(queueStatusService);
    }
}
