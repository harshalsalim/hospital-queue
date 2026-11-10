package com.hospitalqueue.admin.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
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

import com.hospitalqueue.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.hospitalqueue.admin.dto.CreateHospitalRequest;
import com.hospitalqueue.admin.dto.HospitalResponse;
import com.hospitalqueue.admin.service.HospitalService;
import com.hospitalqueue.common.dto.PageResponse;
import com.hospitalqueue.common.exception.ApiException;

/**
 * API tests for {@link HospitalController} together with {@link GlobalExceptionHandler}.
 * The service is faked and no Spring context starts, so these run in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class HospitalControllerTest {

    private static final String VALID_BODY = "{\"name\":\"City Hospital\",\"address\":\"MG Road, Kochi\"}";

    @Mock
    private HospitalService hospitalService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new HospitalController(hospitalService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static PageResponse<HospitalResponse> emptyPage() {
        return new PageResponse<>(List.of(), 0, 20, 0, 0);
    }

    // ---- POST /api/admin/hospitals ----

    @Test
    void create_returns201WithTheCreatedHospital() throws Exception {
        UUID id = UUID.randomUUID();
        when(hospitalService.create(any(CreateHospitalRequest.class)))
                .thenReturn(new HospitalResponse(id, "City Hospital", "MG Road, Kochi", Instant.parse("2026-01-01T00:00:00Z")));

        mockMvc.perform(post("/api/admin/hospitals").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("City Hospital"));
    }

    @Test
    void create_withBlankFieldsReturns400WithAMessagePerField() throws Exception {
        mockMvc.perform(post("/api/admin/hospitals").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"address\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.name").exists())
                .andExpect(jsonPath("$.details.address").exists());

        verifyNoInteractions(hospitalService);
    }

    @Test
    void create_withATooLongNameReturns400() throws Exception {
        String body = "{\"name\":\"" + "A".repeat(256) + "\",\"address\":\"MG Road\"}";

        mockMvc.perform(post("/api/admin/hospitals").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.name").exists());

        verifyNoInteractions(hospitalService);
    }

    @Test
    void create_withMalformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/admin/hospitals").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void create_withoutABodyReturns400() throws Exception {
        mockMvc.perform(post("/api/admin/hospitals").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void create_whenTheDatabaseRejectsTheWriteReturns409WithoutLeakingData() throws Exception {
        when(hospitalService.create(any(CreateHospitalRequest.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value (name)=(SecretName)"));

        mockMvc.perform(post("/api/admin/hospitals").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"))
                .andExpect(content().string(not(containsString("SecretName"))));
    }

    // ---- GET /api/hospitals ----

    @Test
    void list_defaultsToPageZeroWithTwentyItems() throws Exception {
        when(hospitalService.list(0, 20)).thenReturn(emptyPage());

        mockMvc.perform(get("/api/hospitals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(hospitalService).list(0, 20);
    }

    @Test
    void list_clampsANegativePageAndAnOversizedPageSize() throws Exception {
        when(hospitalService.list(0, HospitalController.MAX_PAGE_SIZE)).thenReturn(emptyPage());

        mockMvc.perform(get("/api/hospitals").param("page", "-5").param("size", "5000"))
                .andExpect(status().isOk());

        verify(hospitalService).list(0, HospitalController.MAX_PAGE_SIZE);
    }

    @Test
    void list_raisesAPageSizeOfZeroToOne() throws Exception {
        when(hospitalService.list(0, 1)).thenReturn(emptyPage());

        mockMvc.perform(get("/api/hospitals").param("size", "0"))
                .andExpect(status().isOk());

        verify(hospitalService).list(0, 1);
    }

    @Test
    void list_withANonNumericPageReturns400() throws Exception {
        mockMvc.perform(get("/api/hospitals").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(hospitalService);
    }

    // ---- GET /api/hospitals/{id} ----

    @Test
    void get_returns200WithTheHospital() throws Exception {
        UUID id = UUID.randomUUID();
        when(hospitalService.get(id))
                .thenReturn(new HospitalResponse(id, "City Hospital", "MG Road, Kochi", Instant.parse("2026-01-01T00:00:00Z")));

        mockMvc.perform(get("/api/hospitals/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("City Hospital"));
    }

    @Test
    void get_unknownIdReturns404InTheStandardErrorFormat() throws Exception {
        UUID id = UUID.randomUUID();
        when(hospitalService.get(id)).thenThrow(ApiException.notFound("Hospital", id));

        mockMvc.perform(get("/api/hospitals/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void get_withAnIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/hospitals/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(hospitalService);
    }

    @Test
    void get_unexpectedFailureReturns500WithoutLeakingDetails() throws Exception {
        UUID id = UUID.randomUUID();
        when(hospitalService.get(id)).thenThrow(new IllegalStateException("password=topsecret"));

        mockMvc.perform(get("/api/hospitals/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("topsecret"))));
    }
}
