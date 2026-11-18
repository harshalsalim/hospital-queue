package com.hospitalqueue.consultation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.hospitalqueue.consultation.dto.IssuePrescriptionRequest;
import com.hospitalqueue.consultation.dto.PrescriptionItemResponse;
import com.hospitalqueue.consultation.dto.PrescriptionResponse;
import com.hospitalqueue.consultation.service.PrescriptionService;

/**
 * API tests for {@link PrescriptionController} together with {@link GlobalExceptionHandler}.
 * The service is faked and no Spring context starts, so these run in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class PrescriptionControllerTest {

    private static final UUID CONSULTATION_ID = UUID.randomUUID();
    private static final UUID PRESCRIPTION_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final String VALID_BODY = "{\"therapyAdvice\":\"Rest\",\"labTests\":\"CBC\",\"items\":["
            + "{\"medicineName\":\"Paracetamol 500mg\",\"dosage\":\"1 tablet twice a day\",\"durationDays\":5,\"quantity\":10}]}";

    @Mock
    private PrescriptionService prescriptionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PrescriptionController(prescriptionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static PrescriptionResponse prescription() {
        return new PrescriptionResponse(PRESCRIPTION_ID, CONSULTATION_ID, UUID.randomUUID(), PATIENT_ID, UUID.randomUUID(),
                "Kerala Medical Council", "KMC12345", "Rest", "CBC",
                List.of(new PrescriptionItemResponse(UUID.randomUUID(), "Paracetamol 500mg", "1 tablet twice a day", 5, 10)),
                Instant.parse("2026-11-12T05:00:00Z"));
    }

    private static String bodyWithItem(String item) {
        return "{\"items\":[" + item + "]}";
    }

    // ---- POST /api/consultations/{id}/prescription ----

    @Test
    void issue_returns201WithThePrescription() throws Exception {
        when(prescriptionService.issue(eq(CONSULTATION_ID), any(IssuePrescriptionRequest.class))).thenReturn(prescription());

        mockMvc.perform(post("/api/consultations/{id}/prescription", CONSULTATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(PRESCRIPTION_ID.toString()))
                .andExpect(jsonPath("$.doctorRegistrationNumber").value("KMC12345"))
                .andExpect(jsonPath("$.items[0].medicineName").value("Paracetamol 500mg"))
                .andExpect(jsonPath("$.items[0].durationDays").value(5));
    }

    @Test
    void issue_aMedicineWithMissingFieldsReturns400WithAMessagePerField() throws Exception {
        mockMvc.perform(post("/api/consultations/{id}/prescription", CONSULTATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(bodyWithItem("{}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details['items[0].medicineName']").exists())
                .andExpect(jsonPath("$.details['items[0].dosage']").exists())
                .andExpect(jsonPath("$.details['items[0].durationDays']").exists())
                .andExpect(jsonPath("$.details['items[0].quantity']").exists());

        verifyNoInteractions(prescriptionService);
    }

    @Test
    void issue_aDurationOrQuantityOutOfRangeReturns400() throws Exception {
        String item = "{\"medicineName\":\"X\",\"dosage\":\"1\",\"durationDays\":366,\"quantity\":0}";

        mockMvc.perform(post("/api/consultations/{id}/prescription", CONSULTATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(bodyWithItem(item)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details['items[0].durationDays']").exists())
                .andExpect(jsonPath("$.details['items[0].quantity']").exists());

        verifyNoInteractions(prescriptionService);
    }

    @Test
    void issue_aQuantityAboveTheMaximumReturns400() throws Exception {
        String item = "{\"medicineName\":\"X\",\"dosage\":\"1\",\"durationDays\":1,\"quantity\":1001}";

        mockMvc.perform(post("/api/consultations/{id}/prescription", CONSULTATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(bodyWithItem(item)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details['items[0].quantity']").exists());

        verifyNoInteractions(prescriptionService);
    }

    @Test
    void issue_moreThanTwentyMedicinesReturns400() throws Exception {
        String item = "{\"medicineName\":\"X\",\"dosage\":\"1\",\"durationDays\":1,\"quantity\":1}";
        String items = String.join(",", java.util.Collections.nCopies(21, item));

        mockMvc.perform(post("/api/consultations/{id}/prescription", CONSULTATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(bodyWithItem(items)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.items").exists());

        verifyNoInteractions(prescriptionService);
    }

    @Test
    void issue_adviceOrLabTestsThatAreTooLongReturn400() throws Exception {
        String body = "{\"therapyAdvice\":\"" + "a".repeat(4001) + "\",\"labTests\":\"" + "b".repeat(2001) + "\"}";

        mockMvc.perform(post("/api/consultations/{id}/prescription", CONSULTATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.therapyAdvice").exists())
                .andExpect(jsonPath("$.details.labTests").exists());

        verifyNoInteractions(prescriptionService);
    }

    @Test
    void issue_withMalformedJsonOrNoBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/consultations/{id}/prescription", CONSULTATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(post("/api/consultations/{id}/prescription", CONSULTATION_ID)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(prescriptionService);
    }

    @Test
    void issue_withAConsultationIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(post("/api/consultations/abc/prescription")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(prescriptionService);
    }

    @Test
    void issue_unknownConsultationReturns404() throws Exception {
        when(prescriptionService.issue(eq(CONSULTATION_ID), any(IssuePrescriptionRequest.class)))
                .thenThrow(ApiException.notFound("Consultation", CONSULTATION_ID));

        mockMvc.perform(post("/api/consultations/{id}/prescription", CONSULTATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void issue_anEmptyPrescriptionReturns400() throws Exception {
        when(prescriptionService.issue(eq(CONSULTATION_ID), any(IssuePrescriptionRequest.class)))
                .thenThrow(ApiException.badRequest("EMPTY_PRESCRIPTION", "A prescription needs at least one medicine, therapy advice or lab tests"));

        mockMvc.perform(post("/api/consultations/{id}/prescription", CONSULTATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMPTY_PRESCRIPTION"));
    }

    @Test
    void issue_aSecondPrescriptionReturns409() throws Exception {
        when(prescriptionService.issue(eq(CONSULTATION_ID), any(IssuePrescriptionRequest.class)))
                .thenThrow(ApiException.conflict("PRESCRIPTION_EXISTS", "A prescription was already issued for this consultation"));

        mockMvc.perform(post("/api/consultations/{id}/prescription", CONSULTATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRESCRIPTION_EXISTS"));
    }

    // ---- GET endpoints ----

    @Test
    void getByConsultation_returns200() throws Exception {
        when(prescriptionService.getByConsultation(CONSULTATION_ID)).thenReturn(prescription());

        mockMvc.perform(get("/api/consultations/{id}/prescription", CONSULTATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultationId").value(CONSULTATION_ID.toString()));
    }

    @Test
    void getByConsultation_withNoPrescriptionReturns404() throws Exception {
        when(prescriptionService.getByConsultation(CONSULTATION_ID))
                .thenThrow(ApiException.notFound("Prescription for consultation", CONSULTATION_ID));

        mockMvc.perform(get("/api/consultations/{id}/prescription", CONSULTATION_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_returns200WithThePrescription() throws Exception {
        when(prescriptionService.get(PRESCRIPTION_ID)).thenReturn(prescription());

        mockMvc.perform(get("/api/prescriptions/{id}", PRESCRIPTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.therapyAdvice").value("Rest"))
                .andExpect(jsonPath("$.labTests").value("CBC"));
    }

    @Test
    void get_unknownIdReturns404() throws Exception {
        when(prescriptionService.get(PRESCRIPTION_ID)).thenThrow(ApiException.notFound("Prescription", PRESCRIPTION_ID));

        mockMvc.perform(get("/api/prescriptions/{id}", PRESCRIPTION_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_withAnIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/prescriptions/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(prescriptionService);
    }

    @Test
    void listForPatient_returnsThePrescriptionsAsAnArray() throws Exception {
        when(prescriptionService.listForPatient(PATIENT_ID)).thenReturn(List.of(prescription(), prescription()));

        mockMvc.perform(get("/api/patients/{id}/prescriptions", PATIENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].items[0].medicineName").value("Paracetamol 500mg"))
                .andExpect(jsonPath("$[1].id").value(PRESCRIPTION_ID.toString()));
    }

    @Test
    void listForPatient_unknownPatientReturns404() throws Exception {
        when(prescriptionService.listForPatient(PATIENT_ID)).thenThrow(ApiException.notFound("Patient", PATIENT_ID));

        mockMvc.perform(get("/api/patients/{id}/prescriptions", PATIENT_ID))
                .andExpect(status().isNotFound());
    }
}
