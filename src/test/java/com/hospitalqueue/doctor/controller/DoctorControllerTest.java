package com.hospitalqueue.doctor.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.hospitalqueue.common.dto.PageResponse;
import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.common.exception.GlobalExceptionHandler;
import com.hospitalqueue.doctor.dto.CreateDoctorRequest;
import com.hospitalqueue.doctor.dto.DoctorResponse;
import com.hospitalqueue.doctor.service.DoctorService;

/**
 * API tests for {@link DoctorController} together with {@link GlobalExceptionHandler}.
 * The service is faked and no Spring context starts, so these run in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class DoctorControllerTest {

    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID SPECIALTY_ID = UUID.randomUUID();

    @Mock
    private DoctorService doctorService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DoctorController(doctorService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static String validBody() {
        return "{\"specialtyId\":\"" + SPECIALTY_ID + "\",\"name\":\"Dr. Asha Nair\","
                + "\"registrationCouncil\":\"Kerala Medical Council\",\"registrationNumber\":\"KMC12345\"}";
    }

    private static DoctorResponse sampleDoctor(UUID id) {
        return new DoctorResponse(id, HOSPITAL_ID, SPECIALTY_ID, "Dr. Asha Nair", "Kerala Medical Council",
                "KMC12345", Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static PageResponse<DoctorResponse> emptyPage() {
        return new PageResponse<>(List.of(), 0, 20, 0, 0);
    }

    // ---- POST /api/admin/hospitals/{hospitalId}/doctors ----

    @Test
    void create_returns201WithTheCreatedDoctor() throws Exception {
        UUID id = UUID.randomUUID();
        when(doctorService.create(eq(HOSPITAL_ID), any(CreateDoctorRequest.class))).thenReturn(sampleDoctor(id));

        mockMvc.perform(post("/api/admin/hospitals/{hospitalId}/doctors", HOSPITAL_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Dr. Asha Nair"))
                .andExpect(jsonPath("$.hospitalId").value(HOSPITAL_ID.toString()));
    }

    @Test
    void create_withBlankFieldsAndMissingSpecialtyReturns400WithAMessagePerField() throws Exception {
        mockMvc.perform(post("/api/admin/hospitals/{hospitalId}/doctors", HOSPITAL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"registrationCouncil\":\"\",\"registrationNumber\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.specialtyId").exists())
                .andExpect(jsonPath("$.details.name").exists())
                .andExpect(jsonPath("$.details.registrationCouncil").exists())
                .andExpect(jsonPath("$.details.registrationNumber").exists());

        verifyNoInteractions(doctorService);
    }

    @Test
    void create_withASpecialtyIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(post("/api/admin/hospitals/{hospitalId}/doctors", HOSPITAL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"specialtyId\":\"abc\",\"name\":\"Dr. A\",\"registrationCouncil\":\"C\",\"registrationNumber\":\"1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(doctorService);
    }

    @Test
    void create_withAHospitalIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(post("/api/admin/hospitals/abc/doctors")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(doctorService);
    }

    @Test
    void create_unknownHospitalReturns404() throws Exception {
        when(doctorService.create(eq(HOSPITAL_ID), any(CreateDoctorRequest.class)))
                .thenThrow(ApiException.notFound("Hospital", HOSPITAL_ID));

        mockMvc.perform(post("/api/admin/hospitals/{hospitalId}/doctors", HOSPITAL_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void create_unknownSpecialtyReturns400() throws Exception {
        when(doctorService.create(eq(HOSPITAL_ID), any(CreateDoctorRequest.class)))
                .thenThrow(ApiException.badRequest("INVALID_SPECIALTY", "Unknown specialty: " + SPECIALTY_ID));

        mockMvc.perform(post("/api/admin/hospitals/{hospitalId}/doctors", HOSPITAL_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SPECIALTY"));
    }

    @Test
    void create_duplicateRegistrationReturns409() throws Exception {
        when(doctorService.create(eq(HOSPITAL_ID), any(CreateDoctorRequest.class)))
                .thenThrow(ApiException.conflict("DOCTOR_REGISTRATION_EXISTS",
                        "A doctor with this registration already exists"));

        mockMvc.perform(post("/api/admin/hospitals/{hospitalId}/doctors", HOSPITAL_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCTOR_REGISTRATION_EXISTS"));
    }

    @Test
    void create_whenTheDatabaseRaceRejectsTheWriteReturns409WithoutLeakingData() throws Exception {
        when(doctorService.create(eq(HOSPITAL_ID), any(CreateDoctorRequest.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key (registration_number)=(KMC12345)"));

        mockMvc.perform(post("/api/admin/hospitals/{hospitalId}/doctors", HOSPITAL_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"))
                .andExpect(content().string(not(containsString("KMC12345"))));
    }

    // ---- GET /api/doctors ----

    @Test
    void list_withoutFiltersUsesPageZeroWithTwentyItems() throws Exception {
        when(doctorService.list(null, null, 0, 20)).thenReturn(emptyPage());

        mockMvc.perform(get("/api/doctors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        verify(doctorService).list(null, null, 0, 20);
    }

    @Test
    void list_passesTheHospitalAndSpecialtyFilters() throws Exception {
        when(doctorService.list(HOSPITAL_ID, SPECIALTY_ID, 0, 20)).thenReturn(emptyPage());

        mockMvc.perform(get("/api/doctors")
                        .param("hospitalId", HOSPITAL_ID.toString())
                        .param("specialtyId", SPECIALTY_ID.toString()))
                .andExpect(status().isOk());

        verify(doctorService).list(HOSPITAL_ID, SPECIALTY_ID, 0, 20);
    }

    @Test
    void list_clampsANegativePageAndAnOversizedPageSize() throws Exception {
        when(doctorService.list(null, null, 0, DoctorController.MAX_PAGE_SIZE)).thenReturn(emptyPage());

        mockMvc.perform(get("/api/doctors").param("page", "-3").param("size", "9999"))
                .andExpect(status().isOk());

        verify(doctorService).list(null, null, 0, DoctorController.MAX_PAGE_SIZE);
    }

    @Test
    void list_withAFilterThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/doctors").param("hospitalId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(doctorService);
    }

    // ---- GET /api/doctors/{id} ----

    @Test
    void get_returns200WithTheDoctor() throws Exception {
        UUID id = UUID.randomUUID();
        when(doctorService.get(id)).thenReturn(sampleDoctor(id));

        mockMvc.perform(get("/api/doctors/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Dr. Asha Nair"));
    }

    @Test
    void get_unknownIdReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(doctorService.get(id)).thenThrow(ApiException.notFound("Doctor", id));

        mockMvc.perform(get("/api/doctors/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void get_withAnIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/doctors/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(doctorService);
    }
}
