package com.hospitalqueue.doctor.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;
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
import com.hospitalqueue.doctor.dto.CreateSlotRequest;
import com.hospitalqueue.doctor.dto.SlotResponse;
import com.hospitalqueue.doctor.entity.SlotStatus;
import com.hospitalqueue.doctor.service.SlotService;

/**
 * API tests for {@link SlotController} together with {@link GlobalExceptionHandler}.
 * The service is faked and no Spring context starts, so these run in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class SlotControllerTest {

    private static final UUID DOCTOR_ID = UUID.randomUUID();
    private static final UUID SLOT_ID = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 11, 13);
    private static final String VALID_BODY =
            "{\"date\":\"2026-11-13\",\"startTime\":\"09:00\",\"endTime\":\"12:00\",\"maxTokens\":30}";

    @Mock
    private SlotService slotService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SlotController(slotService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static SlotResponse sampleSlot(SlotStatus status, int maxTokens) {
        return new SlotResponse(SLOT_ID, UUID.randomUUID(), DOCTOR_ID, DATE, LocalTime.of(9, 0), LocalTime.of(12, 0),
                maxTokens, 0, maxTokens, status);
    }

    // ---- POST /api/doctors/{doctorId}/slots ----

    @Test
    void create_returns201WithTheSession() throws Exception {
        when(slotService.create(eq(DOCTOR_ID), any(CreateSlotRequest.class)))
                .thenReturn(sampleSlot(SlotStatus.AVAILABLE, 30));

        mockMvc.perform(post("/api/doctors/{doctorId}/slots", DOCTOR_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(SLOT_ID.toString()))
                .andExpect(jsonPath("$.maxTokens").value(30))
                .andExpect(jsonPath("$.remainingTokens").value(30))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void create_withMissingFieldsReturns400WithAMessagePerField() throws Exception {
        mockMvc.perform(post("/api/doctors/{doctorId}/slots", DOCTOR_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.date").exists())
                .andExpect(jsonPath("$.details.startTime").exists())
                .andExpect(jsonPath("$.details.endTime").exists())
                .andExpect(jsonPath("$.details.maxTokens").exists());

        verifyNoInteractions(slotService);
    }

    @Test
    void create_withATokenLimitOfZeroReturns400() throws Exception {
        mockMvc.perform(post("/api/doctors/{doctorId}/slots", DOCTOR_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-11-13\",\"startTime\":\"09:00\",\"endTime\":\"12:00\",\"maxTokens\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.maxTokens").exists());

        verifyNoInteractions(slotService);
    }

    @Test
    void create_withATokenLimitAboveTheMaximumReturns400() throws Exception {
        mockMvc.perform(post("/api/doctors/{doctorId}/slots", DOCTOR_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-11-13\",\"startTime\":\"09:00\",\"endTime\":\"12:00\",\"maxTokens\":501}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.maxTokens").exists());

        verifyNoInteractions(slotService);
    }

    @Test
    void create_withAnInvalidTimeReturns400() throws Exception {
        mockMvc.perform(post("/api/doctors/{doctorId}/slots", DOCTOR_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-11-13\",\"startTime\":\"25:61\",\"endTime\":\"12:00\",\"maxTokens\":30}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(slotService);
    }

    @Test
    void create_withADoctorIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(post("/api/doctors/abc/slots")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(slotService);
    }

    @Test
    void create_unknownDoctorReturns404() throws Exception {
        when(slotService.create(eq(DOCTOR_ID), any(CreateSlotRequest.class)))
                .thenThrow(ApiException.notFound("Doctor", DOCTOR_ID));

        mockMvc.perform(post("/api/doctors/{doctorId}/slots", DOCTOR_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void create_businessRuleFailuresKeepTheirStatusAndCode() throws Exception {
        when(slotService.create(eq(DOCTOR_ID), any(CreateSlotRequest.class)))
                .thenThrow(ApiException.badRequest("SLOT_IN_PAST", "The session has already ended"));

        mockMvc.perform(post("/api/doctors/{doctorId}/slots", DOCTOR_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SLOT_IN_PAST"));
    }

    @Test
    void create_anOverlapReturns409() throws Exception {
        when(slotService.create(eq(DOCTOR_ID), any(CreateSlotRequest.class)))
                .thenThrow(ApiException.conflict("SLOT_OVERLAP", "The doctor already has an overlapping session on this date"));

        mockMvc.perform(post("/api/doctors/{doctorId}/slots", DOCTOR_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_OVERLAP"));
    }

    // ---- GET /api/doctors/{doctorId}/slots ----

    @Test
    void list_withADatePassesItToTheService() throws Exception {
        when(slotService.list(DOCTOR_ID, DATE)).thenReturn(List.of(sampleSlot(SlotStatus.AVAILABLE, 30)));

        mockMvc.perform(get("/api/doctors/{doctorId}/slots", DOCTOR_ID).param("date", "2026-11-13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].maxTokens").value(30))
                .andExpect(jsonPath("$[0].startTime").exists());

        verify(slotService).list(DOCTOR_ID, DATE);
    }

    @Test
    void list_withoutADatePassesNullSoTheServiceUsesToday() throws Exception {
        when(slotService.list(DOCTOR_ID, null)).thenReturn(List.of());

        mockMvc.perform(get("/api/doctors/{doctorId}/slots", DOCTOR_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(slotService).list(DOCTOR_ID, null);
    }

    @Test
    void list_withAnInvalidDateReturns400() throws Exception {
        mockMvc.perform(get("/api/doctors/{doctorId}/slots", DOCTOR_ID).param("date", "13-11-2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(slotService);
    }

    @Test
    void list_unknownDoctorReturns404() throws Exception {
        when(slotService.list(DOCTOR_ID, null)).thenThrow(ApiException.notFound("Doctor", DOCTOR_ID));

        mockMvc.perform(get("/api/doctors/{doctorId}/slots", DOCTOR_ID))
                .andExpect(status().isNotFound());
    }

    // ---- PATCH /api/slots/{slotId}/status ----

    @Test
    void updateStatus_returns200WithTheUpdatedSession() throws Exception {
        when(slotService.updateStatus(SLOT_ID, SlotStatus.PAUSED)).thenReturn(sampleSlot(SlotStatus.PAUSED, 30));

        mockMvc.perform(patch("/api/slots/{slotId}/status", SLOT_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAUSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
    }

    @Test
    void updateStatus_withAnUnknownStatusValueReturns400() throws Exception {
        mockMvc.perform(patch("/api/slots/{slotId}/status", SLOT_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(slotService);
    }

    @Test
    void updateStatus_withoutAStatusReturns400() throws Exception {
        mockMvc.perform(patch("/api/slots/{slotId}/status", SLOT_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.status").exists());

        verifyNoInteractions(slotService);
    }

    @Test
    void updateStatus_unknownSlotReturns404() throws Exception {
        when(slotService.updateStatus(SLOT_ID, SlotStatus.AVAILABLE)).thenThrow(ApiException.notFound("Slot", SLOT_ID));

        mockMvc.perform(patch("/api/slots/{slotId}/status", SLOT_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"AVAILABLE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---- PATCH /api/slots/{slotId}/capacity ----

    @Test
    void updateCapacity_returns200WithTheUpdatedSession() throws Exception {
        when(slotService.updateCapacity(SLOT_ID, 40)).thenReturn(sampleSlot(SlotStatus.AVAILABLE, 40));

        mockMvc.perform(patch("/api/slots/{slotId}/capacity", SLOT_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maxTokens\":40}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxTokens").value(40));
    }

    @Test
    void updateCapacity_withZeroReturns400() throws Exception {
        mockMvc.perform(patch("/api/slots/{slotId}/capacity", SLOT_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maxTokens\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.maxTokens").exists());

        verifyNoInteractions(slotService);
    }

    @Test
    void updateCapacity_belowTheBookedCountReturns409() throws Exception {
        when(slotService.updateCapacity(SLOT_ID, 5))
                .thenThrow(ApiException.conflict("CAPACITY_BELOW_BOOKED", "The limit cannot be lower than the tokens already booked"));

        mockMvc.perform(patch("/api/slots/{slotId}/capacity", SLOT_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maxTokens\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPACITY_BELOW_BOOKED"));
    }

    @Test
    void updateCapacity_withASlotIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(patch("/api/slots/abc/capacity")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maxTokens\":40}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(slotService);
    }
}
