package com.hospitalqueue.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import com.hospitalqueue.billing.entity.FeeLedger;
import com.hospitalqueue.billing.entity.FeeStatus;
import com.hospitalqueue.billing.repository.FeeLedgerRepository;
import com.hospitalqueue.billing.service.FeeLedgerService.FeeLedgerView;
import com.hospitalqueue.common.exception.ApiException;

/** Unit tests for {@link FeeLedgerService} with a fixed clock; the repository is faked. */
@ExtendWith(MockitoExtension.class)
class FeeLedgerServiceTest {

    private static final UUID CHECKOUT_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID SLOT_ID = UUID.randomUUID();
    private static final UUID TOKEN_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T04:30:00Z");

    @Mock
    private FeeLedgerRepository feeLedgerRepository;

    private FeeLedgerService feeLedgerService;

    @BeforeEach
    void setUp() {
        feeLedgerService = new FeeLedgerService(feeLedgerRepository, Clock.fixed(NOW, ZoneId.of("Asia/Kolkata")));
    }

    private static FeeLedger ledger() {
        return new FeeLedger(CHECKOUT_ID, HOSPITAL_ID, PATIENT_ID, SLOT_ID, 1000, "stub_abc");
    }

    @Test
    void createPending_savesAPendingFeeWithTheGivenIdsAmountAndAPlaceholderGatewayReference() {
        when(feeLedgerRepository.saveAndFlush(any(FeeLedger.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FeeLedgerView view = feeLedgerService.createPending(CHECKOUT_ID, HOSPITAL_ID, PATIENT_ID, SLOT_ID, 1000);

        ArgumentCaptor<FeeLedger> saved = ArgumentCaptor.forClass(FeeLedger.class);
        verify(feeLedgerRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(CHECKOUT_ID);
        assertThat(saved.getValue().getHospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(saved.getValue().getPatientId()).isEqualTo(PATIENT_ID);
        assertThat(saved.getValue().getSlotId()).isEqualTo(SLOT_ID);
        assertThat(saved.getValue().getAmountPaise()).isEqualTo(1000);
        assertThat(saved.getValue().getPaymentStatus()).isEqualTo(FeeStatus.PENDING);
        assertThat(saved.getValue().getGatewayReference()).startsWith("stub_");
        assertThat(saved.getValue().isNew()).isTrue();
        assertThat(view.status()).isEqualTo(FeeStatus.PENDING);
        assertThat(view.tokenId()).isNull();
    }

    @Test
    void createPending_givesEveryCheckoutItsOwnGatewayReference() {
        when(feeLedgerRepository.saveAndFlush(any(FeeLedger.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FeeLedgerView first = feeLedgerService.createPending(UUID.randomUUID(), HOSPITAL_ID, PATIENT_ID, SLOT_ID, 1000);
        FeeLedgerView second = feeLedgerService.createPending(UUID.randomUUID(), HOSPITAL_ID, PATIENT_ID, SLOT_ID, 1000);

        assertThat(first.gatewayReference()).isNotEqualTo(second.gatewayReference());
    }

    @Test
    void findPending_returnsTheOpenCheckoutWhenThereIsOne() {
        when(feeLedgerRepository.findFirstByPatientIdAndSlotIdAndPaymentStatusOrderByCreatedAtDesc(
                PATIENT_ID, SLOT_ID, FeeStatus.PENDING)).thenReturn(Optional.of(ledger()));

        Optional<FeeLedgerView> result = feeLedgerService.findPending(PATIENT_ID, SLOT_ID);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(CHECKOUT_ID);
        assertThat(result.get().amountPaise()).isEqualTo(1000);
    }

    @Test
    void findPending_isEmptyWhenThereIsNoOpenCheckout() {
        when(feeLedgerRepository.findFirstByPatientIdAndSlotIdAndPaymentStatusOrderByCreatedAtDesc(
                PATIENT_ID, SLOT_ID, FeeStatus.PENDING)).thenReturn(Optional.empty());

        assertThat(feeLedgerService.findPending(PATIENT_ID, SLOT_ID)).isEmpty();
    }

    @Test
    void get_returnsTheCheckout() {
        when(feeLedgerRepository.findById(CHECKOUT_ID)).thenReturn(Optional.of(ledger()));

        assertThat(feeLedgerService.get(CHECKOUT_ID).slotId()).isEqualTo(SLOT_ID);
    }

    @Test
    void get_unknownIdThrowsNotFound() {
        when(feeLedgerRepository.findById(CHECKOUT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> feeLedgerService.get(CHECKOUT_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void markFailed_isTrueWhenAPendingCheckoutWasClosed() {
        when(feeLedgerRepository.markFailed(CHECKOUT_ID, NOW)).thenReturn(1);

        assertThat(feeLedgerService.markFailed(CHECKOUT_ID)).isTrue();
    }

    @Test
    void markFailed_isFalseWhenTheCheckoutIsNoLongerPending() {
        when(feeLedgerRepository.markFailed(CHECKOUT_ID, NOW)).thenReturn(0);

        assertThat(feeLedgerService.markFailed(CHECKOUT_ID)).isFalse();
    }

    @Test
    void markSuccess_isTrueWhenAPendingCheckoutWasMarkedPaid() {
        when(feeLedgerRepository.markSuccess(CHECKOUT_ID, NOW)).thenReturn(1);

        assertThat(feeLedgerService.markSuccess(CHECKOUT_ID)).isTrue();
    }

    @Test
    void markSuccess_isFalseWhenSomeoneElseAlreadyChangedTheCheckout() {
        when(feeLedgerRepository.markSuccess(CHECKOUT_ID, NOW)).thenReturn(0);

        assertThat(feeLedgerService.markSuccess(CHECKOUT_ID)).isFalse();
    }

    @Test
    void linkToken_isTrueWhenThePaidCheckoutGotItsToken() {
        when(feeLedgerRepository.linkToken(CHECKOUT_ID, TOKEN_ID, NOW)).thenReturn(1);

        assertThat(feeLedgerService.linkToken(CHECKOUT_ID, TOKEN_ID)).isTrue();
    }

    @Test
    void linkToken_isFalseWhenTheCheckoutAlreadyHasATokenOrIsNotPaid() {
        when(feeLedgerRepository.linkToken(CHECKOUT_ID, TOKEN_ID, NOW)).thenReturn(0);

        assertThat(feeLedgerService.linkToken(CHECKOUT_ID, TOKEN_ID)).isFalse();
    }

    @Test
    void linkToken_letsADuplicatePaymentErrorReachTheCaller() {
        when(feeLedgerRepository.linkToken(CHECKOUT_ID, TOKEN_ID, NOW))
                .thenThrow(new DataIntegrityViolationException("duplicate key (token_id)"));

        assertThatThrownBy(() -> feeLedgerService.linkToken(CHECKOUT_ID, TOKEN_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void markRefunded_isTrueWhenAPaidCheckoutWithoutATokenWasRefunded() {
        when(feeLedgerRepository.markRefunded(CHECKOUT_ID, NOW)).thenReturn(1);

        assertThat(feeLedgerService.markRefunded(CHECKOUT_ID)).isTrue();
    }

    @Test
    void markRefunded_isFalseWhenThereIsNothingToRefund() {
        when(feeLedgerRepository.markRefunded(CHECKOUT_ID, NOW)).thenReturn(0);

        assertThat(feeLedgerService.markRefunded(CHECKOUT_ID)).isFalse();
    }
}
