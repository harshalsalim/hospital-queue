package com.hospitalqueue.consultation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import com.hospitalqueue.consultation.dto.ConsultationResponse;
import com.hospitalqueue.consultation.service.ConsultationService;
import com.hospitalqueue.consultation.service.ConsultationService.StartResult;

/**
 * API tests for {@link ConsultationController} together with {@link GlobalExceptionHandler}.
 * The service is faked and no Spring context starts, so these run in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class ConsultationControllerTest {

    private static final UUID CONSULTATION_ID = UUID.randomUUID();
    private static final UUID TOKEN_ID = UUID.randomUUID();

    @Mock
    private ConsultationService consultationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ConsultationController(consultationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static ConsultationResponse consultation(String notes) {
        return new ConsultationResponse(CONSULTATION_ID, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                TOKEN_ID, Instant.parse("2026-11-12T04:35:00Z"), null, notes);
    }

    private static String startBody(String notes) {
        String notesPart = notes == null ? "" : ",\"notes\":\"" + notes + "\"";
        return "{\"tokenId\":\"" + TOKEN_ID + "\"" + notesPart + "}";
    }

    // ---- POST /api/consultations ----

    @Test
    void start_aNewConsultationReturns201() throws Exception {
        when(consultationService.start(TOKEN_ID, "Fever"))
                .thenReturn(new StartResult(consultation("Fever"), true));

        mockMvc.perform(post("/api/consultations").contentType(MediaType.APPLICATION_JSON).content(startBody("Fever")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(CONSULTATION_ID.toString()))
                .andExpect(jsonPath("$.tokenId").value(TOKEN_ID.toString()))
                .andExpect(jsonPath("$.notes").value("Fever"));
    }

    @Test
    void start_anExistingConsultationReturns200() throws Exception {
        when(consultationService.start(TOKEN_ID, null)).thenReturn(new StartResult(consultation(null), false));

        mockMvc.perform(post("/api/consultations").contentType(MediaType.APPLICATION_JSON).content(startBody(null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CONSULTATION_ID.toString()));
    }

    @Test
    void start_withoutATokenIdReturns400() throws Exception {
        mockMvc.perform(post("/api/consultations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.tokenId").exists());

        verifyNoInteractions(consultationService);
    }

    @Test
    void start_withNotesThatAreTooLongReturns400() throws Exception {
        mockMvc.perform(post("/api/consultations").contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("n".repeat(10001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.notes").exists());

        verifyNoInteractions(consultationService);
    }

    @Test
    void start_withATokenIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(post("/api/consultations").contentType(MediaType.APPLICATION_JSON).content("{\"tokenId\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(consultationService);
    }

    @Test
    void start_unknownTokenReturns404() throws Exception {
        when(consultationService.start(TOKEN_ID, null)).thenThrow(ApiException.notFound("Token", TOKEN_ID));

        mockMvc.perform(post("/api/consultations").contentType(MediaType.APPLICATION_JSON).content(startBody(null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void start_aPatientWhoWasNotCalledReturns409() throws Exception {
        when(consultationService.start(TOKEN_ID, null)).thenThrow(ApiException.conflict(
                "TOKEN_NOT_IN_CONSULTATION", "A consultation can only start for a patient who has been called"));

        mockMvc.perform(post("/api/consultations").contentType(MediaType.APPLICATION_JSON).content(startBody(null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOKEN_NOT_IN_CONSULTATION"));
    }

    // ---- GET /api/consultations/{id} ----

    @Test
    void get_returns200WithTheConsultation() throws Exception {
        when(consultationService.get(CONSULTATION_ID)).thenReturn(consultation("Cough"));

        mockMvc.perform(get("/api/consultations/{id}", CONSULTATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("Cough"));
    }

    @Test
    void get_unknownIdReturns404() throws Exception {
        when(consultationService.get(CONSULTATION_ID)).thenThrow(ApiException.notFound("Consultation", CONSULTATION_ID));

        mockMvc.perform(get("/api/consultations/{id}", CONSULTATION_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_withAnIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/consultations/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(consultationService);
    }

    // ---- PUT /api/consultations/{id}/notes ----

    @Test
    void updateNotes_returns200WithTheUpdatedConsultation() throws Exception {
        when(consultationService.updateNotes(CONSULTATION_ID, "New notes")).thenReturn(consultation("New notes"));

        mockMvc.perform(put("/api/consultations/{id}/notes", CONSULTATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"New notes\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("New notes"));
    }

    @Test
    void updateNotes_anEmptyBodyObjectClearsTheNotes() throws Exception {
        when(consultationService.updateNotes(CONSULTATION_ID, null)).thenReturn(consultation(null));

        mockMvc.perform(put("/api/consultations/{id}/notes", CONSULTATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        verify(consultationService).updateNotes(CONSULTATION_ID, null);
    }

    @Test
    void updateNotes_withNotesThatAreTooLongReturns400() throws Exception {
        mockMvc.perform(put("/api/consultations/{id}/notes", CONSULTATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"" + "n".repeat(10001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.notes").exists());

        verifyNoInteractions(consultationService);
    }

    @Test
    void updateNotes_withoutABodyReturns400() throws Exception {
        mockMvc.perform(put("/api/consultations/{id}/notes", CONSULTATION_ID).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(consultationService);
    }

    @Test
    void updateNotes_unknownConsultationReturns404() throws Exception {
        when(consultationService.updateNotes(CONSULTATION_ID, "x")).thenThrow(ApiException.notFound("Consultation", CONSULTATION_ID));

        mockMvc.perform(put("/api/consultations/{id}/notes", CONSULTATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateNotes_withAnIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(put("/api/consultations/abc/notes")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(consultationService);
    }
}
