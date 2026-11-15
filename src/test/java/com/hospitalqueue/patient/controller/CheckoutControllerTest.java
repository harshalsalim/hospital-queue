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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.hospitalqueue.billing.entity.FeeStatus;
import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.common.exception.GlobalExceptionHandler;
import com.hospitalqueue.patient.dto.CheckoutResponse;
import com.hospitalqueue.patient.service.CheckoutService;
import com.hospitalqueue.patient.service.CheckoutService.StartResult;

/**
 * API tests for {@link CheckoutController} together with {@link GlobalExceptionHandler}.
 * The service is faked and no Spring context starts, so these run in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class CheckoutControllerTest {

    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID SLOT_ID = UUID.randomUUID();
    private static final String VALID_BODY = "{\"patientId\":\"" + PATIENT_ID + "\",\"slotId\":\"" + SLOT_ID + "\"}";

    @Mock
    private CheckoutService checkoutService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new CheckoutController(checkoutService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static CheckoutResponse sampleCheckout(UUID id) {
        return new CheckoutResponse(id, PATIENT_ID, SLOT_ID, 1000, FeeStatus.PENDING, "stub_ref",
                Instant.parse("2026-11-12T04:45:00Z"), null);
    }

    // ---- POST /api/checkouts ----

    @Test
    void start_aNewCheckoutReturns201() throws Exception {
        UUID id = UUID.randomUUID();
        when(checkoutService.start(PATIENT_ID, SLOT_ID)).thenReturn(new StartResult(sampleCheckout(id), true));

        mockMvc.perform(post("/api/checkouts").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkoutId").value(id.toString()))
                .andExpect(jsonPath("$.amountPaise").value(1000))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.gatewayReference").value("stub_ref"));
    }

    @Test
    void start_anExistingOpenCheckoutReturns200WithTheSameCheckout() throws Exception {
        UUID id = UUID.randomUUID();
        when(checkoutService.start(PATIENT_ID, SLOT_ID)).thenReturn(new StartResult(sampleCheckout(id), false));

        mockMvc.perform(post("/api/checkouts").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutId").value(id.toString()));
    }

    @Test
    void start_withMissingIdsReturns400WithAMessagePerField() throws Exception {
        mockMvc.perform(post("/api/checkouts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.patientId").exists())
                .andExpect(jsonPath("$.details.slotId").exists());

        verifyNoInteractions(checkoutService);
    }

    @Test
    void start_withAnIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(post("/api/checkouts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"abc\",\"slotId\":\"" + SLOT_ID + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(checkoutService);
    }

    @Test
    void start_unknownPatientReturns404() throws Exception {
        when(checkoutService.start(PATIENT_ID, SLOT_ID)).thenThrow(ApiException.notFound("Patient", PATIENT_ID));

        mockMvc.perform(post("/api/checkouts").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void start_aPatientWhoAlreadyHasATokenReturns409() throws Exception {
        when(checkoutService.start(PATIENT_ID, SLOT_ID))
                .thenThrow(ApiException.conflict("TOKEN_ALREADY_BOOKED", "The patient already has a token in this session"));

        mockMvc.perform(post("/api/checkouts").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOKEN_ALREADY_BOOKED"));
    }

    @Test
    void start_aFullSessionReturns409() throws Exception {
        when(checkoutService.start(PATIENT_ID, SLOT_ID))
                .thenThrow(ApiException.conflict("SLOT_FULL", "All tokens for this session are booked"));

        mockMvc.perform(post("/api/checkouts").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_FULL"));
    }

    @Test
    void start_whenEveryFreeTokenIsHeldReturns409() throws Exception {
        when(checkoutService.start(PATIENT_ID, SLOT_ID))
                .thenThrow(ApiException.conflict("SLOT_TEMPORARILY_FULL", "try again in a few minutes"));

        mockMvc.perform(post("/api/checkouts").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_TEMPORARILY_FULL"));
    }

    @Test
    void start_whenHoldsCannotBeTakenReturns503() throws Exception {
        when(checkoutService.start(PATIENT_ID, SLOT_ID))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "HOLD_UNAVAILABLE", "try again shortly"));

        mockMvc.perform(post("/api/checkouts").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("HOLD_UNAVAILABLE"));
    }

    // ---- GET /api/checkouts/{id} ----

    @Test
    void get_returns200WithTheCheckout() throws Exception {
        UUID id = UUID.randomUUID();
        when(checkoutService.get(id)).thenReturn(sampleCheckout(id));

        mockMvc.perform(get("/api/checkouts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutId").value(id.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void get_unknownIdReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(checkoutService.get(id)).thenThrow(ApiException.notFound("Checkout", id));

        mockMvc.perform(get("/api/checkouts/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_withAnIdThatIsNotAUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/checkouts/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));

        verifyNoInteractions(checkoutService);
    }
}
