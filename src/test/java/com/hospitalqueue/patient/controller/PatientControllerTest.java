package com.hospitalqueue.patient.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.common.exception.GlobalExceptionHandler;
import com.hospitalqueue.patient.dto.PatientResponse;
import com.hospitalqueue.patient.dto.RegisterPatientRequest;
import com.hospitalqueue.patient.service.PatientService;

/**
 * API tests for {@link PatientController} together with {@link GlobalExceptionHandler}.
 * The service is faked and no Spring context starts, so these run in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class PatientControllerTest {

    private static final String VALID_BODY = "{\"phoneNumber\":\"9876543210\",\"name\":\"Anil Kumar\"}";

    @Mock
    private PatientService patientService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PatientController(patientService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static PatientResponse samplePatient(UUID id) {
        return new PatientResponse(id, "+91******3210", "Anil Kumar", Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void register_returns201WithAMaskedNumber() throws Exception {
        UUID id = UUID.randomUUID();
        when(patientService.register(any(RegisterPatientRequest.class))).thenReturn(samplePatient(id));

        mockMvc.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.maskedPhoneNumber").value("+91******3210"))
                .andExpect(content().string(not(containsString("9876543210"))));
    }

    @Test
    void register_withoutAPhoneNumberReturns400() throws Exception {
        mockMvc.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Anil\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.phoneNumber").exists());

        verifyNoInteractions(patientService);
    }

    @Test
    void register_withATooLongPhoneNumberOrNameReturns400() throws Exception {
        String body = "{\"phoneNumber\":\"" + "9".repeat(21) + "\",\"name\":\"" + "A".repeat(256) + "\"}";

        mockMvc.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.phoneNumber").exists())
                .andExpect(jsonPath("$.details.name").exists());

        verifyNoInteractions(patientService);
    }

    @Test
    void register_withMalformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void register_anInvalidNumberFromTheServiceReturns400() throws Exception {
        when(patientService.register(any(RegisterPatientRequest.class)))
                .thenThrow(ApiException.badRequest("INVALID_PHONE_NUMBER", "Enter a valid 10-digit Indian mobile number"));

        mockMvc.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PHONE_NUMBER"));
    }

    @Test
    void register_anAlreadyRegisteredNumberReturns409() throws Exception {
        when(patientService.register(any(RegisterPatientRequest.class)))
                .thenThrow(ApiException.conflict("PATIENT_EXISTS", "A patient with this phone number is already registered"));

        mockMvc.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PATIENT_EXISTS"));
    }

    @Test
    void register_whenTheDatabaseRaceRejectsTheWriteReturns409WithoutLeakingTheNumber() throws Exception {
        when(patientService.register(any(RegisterPatientRequest.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key (phone_number)=(+919876543210)"));

        mockMvc.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"))
                .andExpect(content().string(not(containsString("9876543210"))));
    }

    @Test
    void get_returns200WithTheMaskedPatient() throws Exception {
        UUID id = UUID.randomUUID();
        when(patientService.get(id)).thenReturn(samplePatient(id));

        mockMvc.perform(get("/api/patients/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Anil Kumar"))
                .andExpect(jsonPath("$.maskedPhoneNumber").value("+91******3210"));
    }

    @Test
    void get_unknownIdReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(patientService.get(id)).thenThrow(ApiException.notFound("Patient", id));

        mockMvc.perform(get("/api/patients/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void get_withAnIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/patients/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(patientService);
    }
}
