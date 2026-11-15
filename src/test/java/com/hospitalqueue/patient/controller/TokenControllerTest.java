package com.hospitalqueue.patient.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.common.exception.GlobalExceptionHandler;
import com.hospitalqueue.patient.dto.TokenResponse;
import com.hospitalqueue.patient.entity.TokenStatus;
import com.hospitalqueue.patient.service.TokenService;

/**
 * API tests for {@link TokenController} together with {@link GlobalExceptionHandler}.
 * The service is faked and no Spring context starts, so these run in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class TokenControllerTest {

    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID SLOT_ID = UUID.randomUUID();

    @Mock
    private TokenService tokenService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TokenController(tokenService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static TokenResponse sampleToken(UUID id, int number) {
        return new TokenResponse(id, UUID.randomUUID(), PATIENT_ID, SLOT_ID, number, TokenStatus.BOOKED,
                Instant.parse("2026-11-12T04:30:00Z"));
    }

    @Test
    void directBookingWithoutPaymentIsNoLongerPossible() throws Exception {
        mockMvc.perform(post("/api/tokens").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"" + PATIENT_ID + "\",\"slotId\":\"" + SLOT_ID + "\"}"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(tokenService);
    }

    @Test
    void get_returns200WithTheToken() throws Exception {
        UUID id = UUID.randomUUID();
        when(tokenService.get(id)).thenReturn(sampleToken(id, 5));

        mockMvc.perform(get("/api/tokens/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenNumber").value(5))
                .andExpect(jsonPath("$.status").value("BOOKED"));
    }

    @Test
    void get_unknownIdReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(tokenService.get(id)).thenThrow(ApiException.notFound("Token", id));

        mockMvc.perform(get("/api/tokens/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void get_withAnIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/tokens/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(tokenService);
    }

    @Test
    void listForPatient_returnsTheTokensAsAnArray() throws Exception {
        when(tokenService.listForPatient(PATIENT_ID))
                .thenReturn(List.of(sampleToken(UUID.randomUUID(), 9), sampleToken(UUID.randomUUID(), 4)));

        mockMvc.perform(get("/api/patients/{patientId}/tokens", PATIENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tokenNumber").value(9))
                .andExpect(jsonPath("$[1].tokenNumber").value(4));
    }

    @Test
    void listForPatient_unknownPatientReturns404() throws Exception {
        when(tokenService.listForPatient(PATIENT_ID)).thenThrow(ApiException.notFound("Patient", PATIENT_ID));

        mockMvc.perform(get("/api/patients/{patientId}/tokens", PATIENT_ID))
                .andExpect(status().isNotFound());
    }
}
