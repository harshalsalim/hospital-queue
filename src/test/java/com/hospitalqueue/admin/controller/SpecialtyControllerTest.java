package com.hospitalqueue.admin.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.hospitalqueue.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.hospitalqueue.admin.dto.CreateSpecialtyRequest;
import com.hospitalqueue.admin.dto.SpecialtyResponse;
import com.hospitalqueue.admin.service.SpecialtyService;
import com.hospitalqueue.common.exception.ApiException;

/**
 * API tests for {@link SpecialtyController} together with {@link GlobalExceptionHandler}.
 * The service is faked and no Spring context starts, so these run in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class SpecialtyControllerTest {

    @Mock
    private SpecialtyService specialtyService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SpecialtyController(specialtyService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void create_returns201WithTheCreatedSpecialty() throws Exception {
        UUID id = UUID.randomUUID();
        when(specialtyService.create(any(CreateSpecialtyRequest.class)))
                .thenReturn(new SpecialtyResponse(id, "Cardiology"));

        mockMvc.perform(post("/api/admin/specialties").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cardiology\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Cardiology"));
    }

    @Test
    void create_withABlankNameReturns400() throws Exception {
        mockMvc.perform(post("/api/admin/specialties").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.name").exists());

        verifyNoInteractions(specialtyService);
    }

    @Test
    void create_aDuplicateNameReturns409() throws Exception {
        when(specialtyService.create(any(CreateSpecialtyRequest.class)))
                .thenThrow(ApiException.conflict("SPECIALTY_EXISTS", "Specialty already exists: Cardiology"));

        mockMvc.perform(post("/api/admin/specialties").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cardiology\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SPECIALTY_EXISTS"));
    }

    @Test
    void list_returns200WithAnArrayOfSpecialties() throws Exception {
        when(specialtyService.list()).thenReturn(List.of(
                new SpecialtyResponse(UUID.randomUUID(), "Cardiology"),
                new SpecialtyResponse(UUID.randomUUID(), "Neurology")));

        mockMvc.perform(get("/api/specialties"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Cardiology"))
                .andExpect(jsonPath("$[1].name").value("Neurology"));
    }

    @Test
    void usingTheWrongHttpMethodReturns405InTheStandardFormatWithAnAllowHeader() throws Exception {
        mockMvc.perform(post("/api/specialties"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("HTTP_405"))
                .andExpect(header().string("Allow", containsString("GET")));
    }
}
