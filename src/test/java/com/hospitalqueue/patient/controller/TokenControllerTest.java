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
import com.hospitalqueue.patient.service.TokenService.BookingResult;

/**
 * API tests for {@link TokenController} together with {@link GlobalExceptionHandler}.
 * The service is faked and no Spring context starts, so these run in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class TokenControllerTest {

    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID SLOT_ID = UUID.randomUUID();
    private static final String VALID_BODY = "{\"patientId\":\"" + PATIENT_ID + "\",\"slotId\":\"" + SLOT_ID + "\"}";

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

    // ---- POST /api/tokens ----

    @Test
    void book_aNewTokenReturns201() throws Exception {
        UUID id = UUID.randomUUID();
        when(tokenService.book(PATIENT_ID, SLOT_ID)).thenReturn(new BookingResult(sampleToken(id, 7), true));

        mockMvc.perform(post("/api/tokens").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.tokenNumber").value(7))
                .andExpect(jsonPath("$.status").value("BOOKED"));
    }

    @Test
    void book_anExistingTokenReturns200WithTheSameToken() throws Exception {
        UUID id = UUID.randomUUID();
        when(tokenService.book(PATIENT_ID, SLOT_ID)).thenReturn(new BookingResult(sampleToken(id, 3), false));

        mockMvc.perform(post("/api/tokens").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.tokenNumber").value(3));
    }

    @Test
    void book_withMissingIdsReturns400WithAMessagePerField() throws Exception {
        mockMvc.perform(post("/api/tokens").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.patientId").exists())
                .andExpect(jsonPath("$.details.slotId").exists());

        verifyNoInteractions(tokenService);
    }

    @Test
    void book_withAnIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(post("/api/tokens").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"abc\",\"slotId\":\"" + SLOT_ID + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(tokenService);
    }

    @Test
    void book_unknownPatientReturns404() throws Exception {
        when(tokenService.book(PATIENT_ID, SLOT_ID)).thenThrow(ApiException.notFound("Patient", PATIENT_ID));

        mockMvc.perform(post("/api/tokens").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void book_aFullSessionReturns409() throws Exception {
        when(tokenService.book(PATIENT_ID, SLOT_ID))
                .thenThrow(ApiException.conflict("SLOT_FULL", "All tokens for this session are booked"));

        mockMvc.perform(post("/api/tokens").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_FULL"));
    }

    @Test
    void book_aPausedSessionReturns409() throws Exception {
        when(tokenService.book(PATIENT_ID, SLOT_ID))
                .thenThrow(ApiException.conflict("SLOT_PAUSED", "Bookings are paused for this session"));

        mockMvc.perform(post("/api/tokens").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_PAUSED"));
    }

    @Test
    void book_anEndedSessionReturns409() throws Exception {
        when(tokenService.book(PATIENT_ID, SLOT_ID))
                .thenThrow(ApiException.conflict("SLOT_ENDED", "This session has already ended"));

        mockMvc.perform(post("/api/tokens").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_ENDED"));
    }

    // ---- GET /api/tokens/{id} ----

    @Test
    void get_returns200WithTheToken() throws Exception {
        UUID id = UUID.randomUUID();
        when(tokenService.get(id)).thenReturn(sampleToken(id, 5));

        mockMvc.perform(get("/api/tokens/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenNumber").value(5));
    }

    @Test
    void get_unknownIdReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(tokenService.get(id)).thenThrow(ApiException.notFound("Token", id));

        mockMvc.perform(get("/api/tokens/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_withAnIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/tokens/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(tokenService);
    }

    // ---- GET /api/patients/{patientId}/tokens ----

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
