package com.hospitalqueue.patient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import com.hospitalqueue.billing.entity.FeeStatus;
import com.hospitalqueue.billing.service.FeeLedgerService;
import com.hospitalqueue.billing.service.FeeLedgerService.FeeLedgerView;
import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.service.SlotHoldService;
import com.hospitalqueue.doctor.service.SlotHoldService.SlotHold;
import com.hospitalqueue.patient.dto.CheckoutResponse;
import com.hospitalqueue.patient.dto.PaymentOutcome;
import com.hospitalqueue.patient.dto.TokenResponse;
import com.hospitalqueue.patient.entity.TokenStatus;
import com.hospitalqueue.patient.service.CheckoutService.StartResult;
import com.hospitalqueue.patient.service.TokenService.BookingResult;

/** Unit tests for {@link CheckoutService}: every collaborator is faked, no database or Redis is needed. */
@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {

    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID SLOT_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID DOCTOR_ID = UUID.randomUUID();
    private static final Instant EXPIRY = Instant.parse("2026-11-12T04:45:00Z");
    private static final int FEE = 1000;

    @Mock
    private PatientService patientService;

    @Mock
    private TokenService tokenService;

    @Mock
    private SlotHoldService slotHoldService;

    @Mock
    private FeeLedgerService feeLedgerService;

    private CheckoutService checkoutService;

    @BeforeEach
    void setUp() {
        checkoutService = new CheckoutService(patientService, tokenService, slotHoldService, feeLedgerService, FEE);
    }

    private static FeeLedgerView ledgerView(UUID id, FeeStatus status, UUID tokenId) {
        return new FeeLedgerView(id, HOSPITAL_ID, PATIENT_ID, SLOT_ID, tokenId, FEE, status, "stub_ref",
                status == FeeStatus.SUCCESS ? EXPIRY : null, Instant.parse("2026-11-12T04:30:00Z"));
    }

    private void patientCanCheckOut() {
        when(patientService.exists(PATIENT_ID)).thenReturn(true);
        when(tokenService.hasActiveToken(PATIENT_ID, SLOT_ID)).thenReturn(false);
    }

    private void holdsAreGranted() {
        when(slotHoldService.acquire(eq(SLOT_ID), any(UUID.class)))
                .thenAnswer(invocation -> new SlotHold(SLOT_ID, HOSPITAL_ID, DOCTOR_ID, invocation.getArgument(1), EXPIRY));
    }

    private void feeRowsAreCreated() {
        when(feeLedgerService.createPending(any(UUID.class), eq(HOSPITAL_ID), eq(PATIENT_ID), eq(SLOT_ID), eq(FEE)))
                .thenAnswer(invocation -> ledgerView(invocation.getArgument(0), FeeStatus.PENDING, null));
    }

    // ---- start ----

    @Test
    void start_holdsATokenFirstThenRecordsTheFee() {
        patientCanCheckOut();
        when(feeLedgerService.findPending(PATIENT_ID, SLOT_ID)).thenReturn(Optional.empty());
        holdsAreGranted();
        feeRowsAreCreated();

        StartResult result = checkoutService.start(PATIENT_ID, SLOT_ID);

        assertThat(result.created()).isTrue();
        CheckoutResponse checkout = result.checkout();
        assertThat(checkout.amountPaise()).isEqualTo(FEE);
        assertThat(checkout.status()).isEqualTo(FeeStatus.PENDING);
        assertThat(checkout.holdExpiresAt()).isEqualTo(EXPIRY);
        assertThat(checkout.token()).isNull();
        assertThat(checkout.gatewayReference()).isEqualTo("stub_ref");

        InOrder order = inOrder(slotHoldService, feeLedgerService);
        order.verify(slotHoldService).acquire(eq(SLOT_ID), eq(checkout.checkoutId()));
        order.verify(feeLedgerService).createPending(eq(checkout.checkoutId()), eq(HOSPITAL_ID), eq(PATIENT_ID), eq(SLOT_ID), eq(FEE));
    }

    @Test
    void start_anUnknownPatientThrowsNotFoundAndTouchesNothingElse() {
        when(patientService.exists(PATIENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> checkoutService.start(PATIENT_ID, SLOT_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verifyNoInteractions(tokenService, slotHoldService, feeLedgerService);
    }

    @Test
    void start_aPatientWhoAlreadyHasATokenIsRefusedBeforeAnyHoldOrFee() {
        when(patientService.exists(PATIENT_ID)).thenReturn(true);
        when(tokenService.hasActiveToken(PATIENT_ID, SLOT_ID)).thenReturn(true);

        assertThatThrownBy(() -> checkoutService.start(PATIENT_ID, SLOT_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException exception = (ApiException) thrown;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("TOKEN_ALREADY_BOOKED");
                });

        verifyNoInteractions(slotHoldService, feeLedgerService);
    }

    @Test
    void start_anOpenCheckoutWithALiveHoldIsReturnedInsteadOfCreatingAnother() {
        UUID openId = UUID.randomUUID();
        patientCanCheckOut();
        when(feeLedgerService.findPending(PATIENT_ID, SLOT_ID)).thenReturn(Optional.of(ledgerView(openId, FeeStatus.PENDING, null)));
        when(slotHoldService.expiryOf(SLOT_ID, openId)).thenReturn(Optional.of(EXPIRY));

        StartResult result = checkoutService.start(PATIENT_ID, SLOT_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.checkout().checkoutId()).isEqualTo(openId);
        assertThat(result.checkout().holdExpiresAt()).isEqualTo(EXPIRY);
        verify(slotHoldService, never()).acquire(any(UUID.class), any(UUID.class));
        verify(feeLedgerService, never()).createPending(any(UUID.class), any(UUID.class), any(UUID.class), any(UUID.class), eq(FEE));
    }

    @Test
    void start_anOpenCheckoutWhoseHoldLapsedIsClosedAndANewOneStarts() {
        UUID staleId = UUID.randomUUID();
        patientCanCheckOut();
        when(feeLedgerService.findPending(PATIENT_ID, SLOT_ID)).thenReturn(Optional.of(ledgerView(staleId, FeeStatus.PENDING, null)));
        when(slotHoldService.expiryOf(SLOT_ID, staleId)).thenReturn(Optional.empty());
        holdsAreGranted();
        feeRowsAreCreated();

        StartResult result = checkoutService.start(PATIENT_ID, SLOT_ID);

        assertThat(result.created()).isTrue();
        assertThat(result.checkout().checkoutId()).isNotEqualTo(staleId);
        verify(feeLedgerService).markFailed(staleId);
    }

    @Test
    void start_whenTheHoldIsRefusedNoFeeIsRecorded() {
        patientCanCheckOut();
        when(feeLedgerService.findPending(PATIENT_ID, SLOT_ID)).thenReturn(Optional.empty());
        when(slotHoldService.acquire(eq(SLOT_ID), any(UUID.class)))
                .thenThrow(ApiException.conflict("SLOT_FULL", "All tokens for this session are booked"));

        assertThatThrownBy(() -> checkoutService.start(PATIENT_ID, SLOT_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getCode()).isEqualTo("SLOT_FULL"));

        verify(feeLedgerService, never()).createPending(any(UUID.class), any(UUID.class), any(UUID.class), any(UUID.class), eq(FEE));
        verify(slotHoldService, never()).release(any(UUID.class), any(UUID.class));
    }

    @Test
    void start_whenSavingTheFeeFailsTheHoldIsGivenBackAndTheErrorPropagates() {
        patientCanCheckOut();
        when(feeLedgerService.findPending(PATIENT_ID, SLOT_ID)).thenReturn(Optional.empty());
        holdsAreGranted();
        when(feeLedgerService.createPending(any(UUID.class), eq(HOSPITAL_ID), eq(PATIENT_ID), eq(SLOT_ID), eq(FEE)))
                .thenThrow(new IllegalStateException("database is down"));

        assertThatThrownBy(() -> checkoutService.start(PATIENT_ID, SLOT_ID))
                .isInstanceOf(IllegalStateException.class);

        verify(slotHoldService).release(eq(SLOT_ID), any(UUID.class));
    }

    @Test
    void start_aSimultaneousRequestThatLostTheRaceGetsTheWinnersCheckoutAndGivesItsHoldBack() {
        UUID winnerId = UUID.randomUUID();
        patientCanCheckOut();
        when(feeLedgerService.findPending(PATIENT_ID, SLOT_ID))
                .thenReturn(Optional.empty(), Optional.of(ledgerView(winnerId, FeeStatus.PENDING, null)));
        holdsAreGranted();
        when(feeLedgerService.createPending(any(UUID.class), eq(HOSPITAL_ID), eq(PATIENT_ID), eq(SLOT_ID), eq(FEE)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(slotHoldService.expiryOf(SLOT_ID, winnerId)).thenReturn(Optional.of(EXPIRY));

        StartResult result = checkoutService.start(PATIENT_ID, SLOT_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.checkout().checkoutId()).isEqualTo(winnerId);
        verify(slotHoldService).release(eq(SLOT_ID), any(UUID.class));
    }

    @Test
    void start_aConflictWithNoWinnerToHandBackIsRethrown() {
        patientCanCheckOut();
        when(feeLedgerService.findPending(PATIENT_ID, SLOT_ID)).thenReturn(Optional.empty(), Optional.empty());
        holdsAreGranted();
        when(feeLedgerService.createPending(any(UUID.class), eq(HOSPITAL_ID), eq(PATIENT_ID), eq(SLOT_ID), eq(FEE)))
                .thenThrow(new DataIntegrityViolationException("foreign key violation"));

        assertThatThrownBy(() -> checkoutService.start(PATIENT_ID, SLOT_ID))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(slotHoldService).release(eq(SLOT_ID), any(UUID.class));
    }

    // ---- get ----

    @Test
    void get_aPendingCheckoutShowsWhenItsHoldLapses() {
        UUID id = UUID.randomUUID();
        when(feeLedgerService.get(id)).thenReturn(ledgerView(id, FeeStatus.PENDING, null));
        when(slotHoldService.expiryOf(SLOT_ID, id)).thenReturn(Optional.of(EXPIRY));

        CheckoutResponse response = checkoutService.get(id);

        assertThat(response.status()).isEqualTo(FeeStatus.PENDING);
        assertThat(response.holdExpiresAt()).isEqualTo(EXPIRY);
        assertThat(response.token()).isNull();
    }

    @Test
    void get_aPaidCheckoutIncludesItsTokenAndSkipsTheHoldLookup() {
        UUID id = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        TokenResponse token = new TokenResponse(tokenId, HOSPITAL_ID, PATIENT_ID, SLOT_ID, 4, TokenStatus.BOOKED, EXPIRY);
        when(feeLedgerService.get(id)).thenReturn(ledgerView(id, FeeStatus.SUCCESS, tokenId));
        when(tokenService.get(tokenId)).thenReturn(token);

        CheckoutResponse response = checkoutService.get(id);

        assertThat(response.status()).isEqualTo(FeeStatus.SUCCESS);
        assertThat(response.token().tokenNumber()).isEqualTo(4);
        assertThat(response.holdExpiresAt()).isNull();
        verifyNoInteractions(slotHoldService);
    }

    @Test
    void get_stillWorksWhenRedisIsDownAndSimplyHasNoExpiry() {
        UUID id = UUID.randomUUID();
        when(feeLedgerService.get(id)).thenReturn(ledgerView(id, FeeStatus.PENDING, null));
        when(slotHoldService.expiryOf(SLOT_ID, id))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "HOLD_UNAVAILABLE", "down"));

        CheckoutResponse response = checkoutService.get(id);

        assertThat(response.status()).isEqualTo(FeeStatus.PENDING);
        assertThat(response.holdExpiresAt()).isNull();
    }

    @Test
    void get_unknownCheckoutThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(feeLedgerService.get(id)).thenThrow(ApiException.notFound("Checkout", id));

        assertThatThrownBy(() -> checkoutService.get(id))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---- completePayment ----

    private static TokenResponse token(UUID id, int number) {
        return new TokenResponse(id, HOSPITAL_ID, PATIENT_ID, SLOT_ID, number, TokenStatus.BOOKED, EXPIRY);
    }

    private static void assertApiError(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        ApiException exception = (ApiException) thrown;
        assertThat(exception.getStatus()).isEqualTo(status);
        assertThat(exception.getCode()).isEqualTo(code);
    }

    @Test
    void completePayment_aWrongGatewayReferenceIsRejectedBeforeAnyChange() {
        UUID id = UUID.randomUUID();
        when(feeLedgerService.get(id)).thenReturn(ledgerView(id, FeeStatus.PENDING, null));

        assertThatThrownBy(() -> checkoutService.completePayment(id, PaymentOutcome.SUCCESS, "someone_elses_ref"))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.BAD_REQUEST, "GATEWAY_REFERENCE_MISMATCH"));

        verify(feeLedgerService, never()).markSuccess(any(UUID.class));
        verify(feeLedgerService, never()).markFailed(any(UUID.class));
        verifyNoInteractions(tokenService, slotHoldService);
    }

    @Test
    void completePayment_unknownCheckoutThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(feeLedgerService.get(id)).thenThrow(ApiException.notFound("Checkout", id));

        assertThatThrownBy(() -> checkoutService.completePayment(id, PaymentOutcome.SUCCESS, "stub_ref"))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.NOT_FOUND, "NOT_FOUND"));
    }

    @Test
    void completePayment_success_marksPaidIssuesTheTokenLinksItAndReleasesTheHold() {
        UUID id = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        when(feeLedgerService.get(id)).thenReturn(
                ledgerView(id, FeeStatus.PENDING, null),
                ledgerView(id, FeeStatus.SUCCESS, null),
                ledgerView(id, FeeStatus.SUCCESS, tokenId));
        when(feeLedgerService.markSuccess(id)).thenReturn(true);
        when(tokenService.book(PATIENT_ID, SLOT_ID)).thenReturn(new BookingResult(token(tokenId, 4), true));
        when(feeLedgerService.linkToken(id, tokenId)).thenReturn(true);
        when(tokenService.get(tokenId)).thenReturn(token(tokenId, 4));

        CheckoutResponse response = checkoutService.completePayment(id, PaymentOutcome.SUCCESS, "stub_ref");

        assertThat(response.status()).isEqualTo(FeeStatus.SUCCESS);
        assertThat(response.token().tokenNumber()).isEqualTo(4);
        InOrder order = inOrder(feeLedgerService, tokenService, slotHoldService);
        order.verify(feeLedgerService).markSuccess(id);
        order.verify(tokenService).book(PATIENT_ID, SLOT_ID);
        order.verify(feeLedgerService).linkToken(id, tokenId);
        order.verify(slotHoldService).release(SLOT_ID, id);
    }

    @Test
    void completePayment_success_aPaidCheckoutWithoutATokenAfterACrashIsFinishedByTheRetry() {
        UUID id = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        when(feeLedgerService.get(id)).thenReturn(
                ledgerView(id, FeeStatus.SUCCESS, null),
                ledgerView(id, FeeStatus.SUCCESS, tokenId));
        when(tokenService.book(PATIENT_ID, SLOT_ID)).thenReturn(new BookingResult(token(tokenId, 2), true));
        when(feeLedgerService.linkToken(id, tokenId)).thenReturn(true);
        when(tokenService.get(tokenId)).thenReturn(token(tokenId, 2));

        CheckoutResponse response = checkoutService.completePayment(id, PaymentOutcome.SUCCESS, "stub_ref");

        assertThat(response.token().tokenNumber()).isEqualTo(2);
        verify(feeLedgerService, never()).markSuccess(any(UUID.class));
    }

    @Test
    void completePayment_success_anAlreadyCompletedCheckoutReturnsItsTokenWithoutBookingAgain() {
        UUID id = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        when(feeLedgerService.get(id)).thenReturn(ledgerView(id, FeeStatus.SUCCESS, tokenId));
        when(tokenService.get(tokenId)).thenReturn(token(tokenId, 6));

        CheckoutResponse response = checkoutService.completePayment(id, PaymentOutcome.SUCCESS, "stub_ref");

        assertThat(response.token().id()).isEqualTo(tokenId);
        verify(tokenService, never()).book(any(UUID.class), any(UUID.class));
        verify(feeLedgerService, never()).linkToken(any(UUID.class), any(UUID.class));
    }

    @Test
    void completePayment_success_aFailedCheckoutIsClosed() {
        UUID id = UUID.randomUUID();
        when(feeLedgerService.get(id)).thenReturn(ledgerView(id, FeeStatus.FAILED, null));

        assertThatThrownBy(() -> checkoutService.completePayment(id, PaymentOutcome.SUCCESS, "stub_ref"))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "CHECKOUT_CLOSED"));

        verifyNoInteractions(tokenService, slotHoldService);
    }

    @Test
    void completePayment_success_aRefundedCheckoutIsClosed() {
        UUID id = UUID.randomUUID();
        when(feeLedgerService.get(id)).thenReturn(ledgerView(id, FeeStatus.REFUNDED, null));

        assertThatThrownBy(() -> checkoutService.completePayment(id, PaymentOutcome.SUCCESS, "stub_ref"))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "CHECKOUT_CLOSED"));

        verifyNoInteractions(tokenService, slotHoldService);
    }

    @Test
    void completePayment_success_whenTheSessionCannotTakeTheBookingThePaymentIsRefunded() {
        UUID id = UUID.randomUUID();
        when(feeLedgerService.get(id)).thenReturn(ledgerView(id, FeeStatus.SUCCESS, null));
        when(tokenService.book(PATIENT_ID, SLOT_ID))
                .thenThrow(ApiException.conflict("SLOT_FULL", "All tokens for this session are booked"));

        assertThatThrownBy(() -> checkoutService.completePayment(id, PaymentOutcome.SUCCESS, "stub_ref"))
                .satisfies(thrown -> {
                    assertApiError(thrown, HttpStatus.CONFLICT, "SLOT_FULL");
                    assertThat(thrown.getMessage()).containsIgnoringCase("refunded");
                });

        verify(feeLedgerService).markRefunded(id);
        verify(slotHoldService).release(SLOT_ID, id);
        verify(feeLedgerService, never()).linkToken(any(UUID.class), any(UUID.class));
    }

    @Test
    void completePayment_success_aDuplicatePaymentForAnAlreadyPaidForTokenIsRefunded() {
        UUID id = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        when(feeLedgerService.get(id)).thenReturn(ledgerView(id, FeeStatus.SUCCESS, null));
        when(tokenService.book(PATIENT_ID, SLOT_ID)).thenReturn(new BookingResult(token(tokenId, 2), false));
        when(feeLedgerService.linkToken(id, tokenId)).thenThrow(new DataIntegrityViolationException("duplicate key (token_id)"));

        assertThatThrownBy(() -> checkoutService.completePayment(id, PaymentOutcome.SUCCESS, "stub_ref"))
                .satisfies(thrown -> {
                    assertApiError(thrown, HttpStatus.CONFLICT, "TOKEN_ALREADY_BOOKED");
                    assertThat(thrown.getMessage()).containsIgnoringCase("refunded");
                });

        verify(feeLedgerService).markRefunded(id);
        verify(slotHoldService).release(SLOT_ID, id);
    }

    @Test
    void completePayment_success_aSimultaneousCallbackThatLinkedTheSameTokenFirstIsNotAnError() {
        UUID id = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        when(feeLedgerService.get(id)).thenReturn(
                ledgerView(id, FeeStatus.SUCCESS, null),
                ledgerView(id, FeeStatus.SUCCESS, tokenId));
        when(tokenService.book(PATIENT_ID, SLOT_ID)).thenReturn(new BookingResult(token(tokenId, 3), false));
        when(feeLedgerService.linkToken(id, tokenId)).thenReturn(false);
        when(tokenService.get(tokenId)).thenReturn(token(tokenId, 3));

        CheckoutResponse response = checkoutService.completePayment(id, PaymentOutcome.SUCCESS, "stub_ref");

        assertThat(response.status()).isEqualTo(FeeStatus.SUCCESS);
        assertThat(response.token().id()).isEqualTo(tokenId);
        verify(feeLedgerService, never()).markRefunded(any(UUID.class));
    }

    @Test
    void completePayment_success_aCheckoutThatChangedStateDuringTheCallbackIsReportedClosed() {
        UUID id = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        when(feeLedgerService.get(id)).thenReturn(
                ledgerView(id, FeeStatus.SUCCESS, null),
                ledgerView(id, FeeStatus.REFUNDED, null));
        when(tokenService.book(PATIENT_ID, SLOT_ID)).thenReturn(new BookingResult(token(tokenId, 3), true));
        when(feeLedgerService.linkToken(id, tokenId)).thenReturn(false);

        assertThatThrownBy(() -> checkoutService.completePayment(id, PaymentOutcome.SUCCESS, "stub_ref"))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "CHECKOUT_CLOSED"));

        verify(feeLedgerService, never()).markRefunded(any(UUID.class));
    }

    @Test
    void completePayment_failure_closesAPendingCheckoutAndReleasesTheHold() {
        UUID id = UUID.randomUUID();
        when(feeLedgerService.get(id)).thenReturn(
                ledgerView(id, FeeStatus.PENDING, null),
                ledgerView(id, FeeStatus.FAILED, null));
        when(feeLedgerService.markFailed(id)).thenReturn(true);

        CheckoutResponse response = checkoutService.completePayment(id, PaymentOutcome.FAILED, "stub_ref");

        assertThat(response.status()).isEqualTo(FeeStatus.FAILED);
        verify(feeLedgerService).markFailed(id);
        verify(slotHoldService).release(SLOT_ID, id);
    }

    @Test
    void completePayment_failure_afterASuccessIsRefused() {
        UUID id = UUID.randomUUID();
        when(feeLedgerService.get(id)).thenReturn(ledgerView(id, FeeStatus.SUCCESS, null));

        assertThatThrownBy(() -> checkoutService.completePayment(id, PaymentOutcome.FAILED, "stub_ref"))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "PAYMENT_ALREADY_COMPLETED"));

        verify(feeLedgerService, never()).markFailed(any(UUID.class));
    }

    @Test
    void completePayment_failure_repeatedForAnAlreadyFailedCheckoutChangesNothing() {
        UUID id = UUID.randomUUID();
        when(feeLedgerService.get(id)).thenReturn(ledgerView(id, FeeStatus.FAILED, null));

        CheckoutResponse response = checkoutService.completePayment(id, PaymentOutcome.FAILED, "stub_ref");

        assertThat(response.status()).isEqualTo(FeeStatus.FAILED);
        verify(feeLedgerService, never()).markFailed(any(UUID.class));
        verifyNoInteractions(slotHoldService);
    }

    @Test
    void completePayment_failure_whenASuccessWonTheRaceItIsRefusedAndTheHoldIsKept() {
        UUID id = UUID.randomUUID();
        when(feeLedgerService.get(id)).thenReturn(
                ledgerView(id, FeeStatus.PENDING, null),
                ledgerView(id, FeeStatus.SUCCESS, null));
        when(feeLedgerService.markFailed(id)).thenReturn(false);

        assertThatThrownBy(() -> checkoutService.completePayment(id, PaymentOutcome.FAILED, "stub_ref"))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "PAYMENT_ALREADY_COMPLETED"));

        verify(slotHoldService, never()).release(any(UUID.class), any(UUID.class));
    }
}
