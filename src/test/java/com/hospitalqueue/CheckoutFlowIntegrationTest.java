package com.hospitalqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.hospitalqueue.admin.dto.CreateHospitalRequest;
import com.hospitalqueue.admin.dto.CreateSpecialtyRequest;
import com.hospitalqueue.admin.dto.HospitalResponse;
import com.hospitalqueue.admin.dto.SpecialtyResponse;
import com.hospitalqueue.admin.service.HospitalService;
import com.hospitalqueue.admin.service.SpecialtyService;
import com.hospitalqueue.billing.entity.FeeStatus;
import com.hospitalqueue.billing.service.FeeLedgerService;
import com.hospitalqueue.billing.service.FeeLedgerService.FeeLedgerView;
import com.hospitalqueue.common.config.ClockConfig;
import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.dto.CreateDoctorRequest;
import com.hospitalqueue.doctor.dto.CreateSlotRequest;
import com.hospitalqueue.doctor.dto.DoctorResponse;
import com.hospitalqueue.doctor.dto.SlotResponse;
import com.hospitalqueue.doctor.service.DoctorService;
import com.hospitalqueue.doctor.service.SlotHoldService;
import com.hospitalqueue.doctor.service.SlotService;
import com.hospitalqueue.patient.dto.CheckoutResponse;
import com.hospitalqueue.patient.dto.PaymentOutcome;
import com.hospitalqueue.patient.dto.RegisterPatientRequest;
import com.hospitalqueue.patient.service.CheckoutService;
import com.hospitalqueue.patient.service.CheckoutService.StartResult;
import com.hospitalqueue.patient.service.PatientService;
import com.hospitalqueue.patient.service.TokenService;

/**
 * The whole paid-booking flow against a REAL PostgreSQL and Redis (Docker must be running): start checkout, pay,
 * token issued, hold released; plus the awkward cases: the same payment arriving many times at once, a payment for
 * a session that filled up meanwhile (refund), a failed payment, a duplicate payment, and simultaneous checkout starts.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CheckoutFlowIntegrationTest {

    @Autowired
    private HospitalService hospitalService;

    @Autowired
    private SpecialtyService specialtyService;

    @Autowired
    private DoctorService doctorService;

    @Autowired
    private SlotService slotService;

    @Autowired
    private PatientService patientService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private SlotHoldService slotHoldService;

    @Autowired
    private FeeLedgerService feeLedgerService;

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private record Session(UUID doctorId, UUID slotId, LocalDate date) {
    }

    private Session createSession(int capacity) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        HospitalResponse hospital = hospitalService.create(
                new CreateHospitalRequest("Checkout Hospital " + suffix, "Test address " + suffix));
        SpecialtyResponse specialty = specialtyService.create(new CreateSpecialtyRequest("Checkout Specialty " + suffix));
        DoctorResponse doctor = doctorService.create(hospital.id(),
                new CreateDoctorRequest(specialty.id(), "Dr. Checkout " + suffix, "Test Council", "CHK-" + suffix));
        LocalDate date = LocalDate.now(ClockConfig.BUSINESS_ZONE).plusDays(1);
        SlotResponse slot = slotService.create(doctor.id(),
                new CreateSlotRequest(date, LocalTime.of(9, 0), LocalTime.of(12, 0), capacity));
        return new Session(doctor.id(), slot.id(), date);
    }

    private UUID registerPatient() {
        long number = ThreadLocalRandom.current().nextLong(0, 900_000_000L);
        return patientService.register(new RegisterPatientRequest(String.format("9%09d", number), null)).id();
    }

    private int bookedCount(Session session) {
        return slotService.list(session.doctorId(), session.date()).get(0).bookedCount();
    }

    private CheckoutResponse startCheckout(UUID patientId, Session session) {
        return checkoutService.start(patientId, session.slotId()).checkout();
    }

    private CheckoutResponse pay(CheckoutResponse checkout) {
        return checkoutService.completePayment(checkout.checkoutId(), PaymentOutcome.SUCCESS, checkout.gatewayReference());
    }

    private static String codeOf(Throwable failure) {
        return failure instanceof ApiException apiException ? apiException.getCode() : failure.getClass().getSimpleName();
    }

    @Test
    void aPaidCheckoutIssuesTheTokenReleasesTheHoldAndIsSafeToRepeat() {
        Session session = createSession(2);
        UUID patientId = registerPatient();

        CheckoutResponse started = startCheckout(patientId, session);
        assertThat(started.status()).isEqualTo(FeeStatus.PENDING);
        assertThat(started.amountPaise()).isEqualTo(1000);
        assertThat(slotHoldService.expiryOf(session.slotId(), started.checkoutId())).isPresent();

        CheckoutResponse paid = pay(started);

        assertThat(paid.status()).isEqualTo(FeeStatus.SUCCESS);
        assertThat(paid.token()).isNotNull();
        assertThat(paid.token().tokenNumber()).isEqualTo(1);
        assertThat(slotHoldService.expiryOf(session.slotId(), started.checkoutId())).isEmpty();
        assertThat(bookedCount(session)).isEqualTo(1);

        CheckoutResponse repeated = pay(started);

        assertThat(repeated.token().id()).isEqualTo(paid.token().id());
        assertThat(bookedCount(session)).isEqualTo(1);
        assertThat(checkoutService.get(started.checkoutId()).token().id()).isEqualTo(paid.token().id());
        assertThat(tokenService.listForPatient(patientId)).hasSize(1);
    }

    @Test
    void theSamePaymentArrivingManyTimesAtOnceCreatesOneTokenAndNoRefund() throws Exception {
        Session session = createSession(5);
        UUID patientId = registerPatient();
        CheckoutResponse started = startCheckout(patientId, session);
        int callbacks = 8;

        ExecutorService pool = Executors.newFixedThreadPool(callbacks);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<CheckoutResponse>> futures = new ArrayList<>();
        for (int i = 0; i < callbacks; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return pay(started);
            }));
        }
        go.countDown();

        Set<UUID> tokenIds = new HashSet<>();
        for (Future<CheckoutResponse> future : futures) {
            tokenIds.add(future.get(60, TimeUnit.SECONDS).token().id());
        }
        pool.shutdown();

        assertThat(tokenIds).hasSize(1);
        assertThat(checkoutService.get(started.checkoutId()).status()).isEqualTo(FeeStatus.SUCCESS);
        assertThat(bookedCount(session)).isEqualTo(1);
        assertThat(tokenService.listForPatient(patientId)).hasSize(1);
    }

    @Test
    void aPaymentForASessionThatFilledUpMeanwhileIsRefunded() {
        Session session = createSession(1);
        UUID patientA = registerPatient();
        UUID patientB = registerPatient();

        CheckoutResponse started = startCheckout(patientA, session);
        tokenService.book(patientB, session.slotId());

        assertThatThrownBy(() -> pay(started))
                .isInstanceOf(ApiException.class)
                .satisfies(failure -> {
                    assertThat(codeOf(failure)).isEqualTo("SLOT_FULL");
                    assertThat(failure.getMessage()).containsIgnoringCase("refunded");
                });

        assertThat(checkoutService.get(started.checkoutId()).status()).isEqualTo(FeeStatus.REFUNDED);
        assertThat(bookedCount(session)).isEqualTo(1);
        assertThat(tokenService.listForPatient(patientA)).isEmpty();
        assertThat(slotHoldService.expiryOf(session.slotId(), started.checkoutId())).isEmpty();
    }

    @Test
    void aFailedPaymentClosesTheCheckoutReleasesTheHoldAndAllowsANewCheckout() {
        Session session = createSession(2);
        UUID patientId = registerPatient();
        CheckoutResponse started = startCheckout(patientId, session);

        CheckoutResponse failed = checkoutService.completePayment(
                started.checkoutId(), PaymentOutcome.FAILED, started.gatewayReference());

        assertThat(failed.status()).isEqualTo(FeeStatus.FAILED);
        assertThat(slotHoldService.expiryOf(session.slotId(), started.checkoutId())).isEmpty();
        assertThatThrownBy(() -> pay(started))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("CHECKOUT_CLOSED"));

        StartResult second = checkoutService.start(patientId, session.slotId());
        assertThat(second.created()).isTrue();
        assertThat(second.checkout().checkoutId()).isNotEqualTo(started.checkoutId());
    }

    @Test
    void aWrongGatewayReferenceChangesNothing() {
        Session session = createSession(2);
        CheckoutResponse started = startCheckout(registerPatient(), session);

        assertThatThrownBy(() -> checkoutService.completePayment(
                started.checkoutId(), PaymentOutcome.SUCCESS, "stub_not_the_right_one"))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("GATEWAY_REFERENCE_MISMATCH"));

        assertThat(checkoutService.get(started.checkoutId()).status()).isEqualTo(FeeStatus.PENDING);
        assertThat(bookedCount(session)).isZero();
    }

    @Test
    void aSecondPaymentForATokenThatIsAlreadyPaidForIsRefunded() {
        Session session = createSession(5);
        UUID patientId = registerPatient();
        CheckoutResponse first = startCheckout(patientId, session);
        CheckoutResponse paid = pay(first);
        FeeLedgerView firstLedger = feeLedgerService.get(first.checkoutId());

        // A second payment for the same patient and session, created behind the checkout's back.
        FeeLedgerView second = feeLedgerService.createPending(
                UUID.randomUUID(), firstLedger.hospitalId(), patientId, session.slotId(), 1000);

        assertThatThrownBy(() -> checkoutService.completePayment(
                second.id(), PaymentOutcome.SUCCESS, second.gatewayReference()))
                .satisfies(failure -> {
                    assertThat(codeOf(failure)).isEqualTo("TOKEN_ALREADY_BOOKED");
                    assertThat(failure.getMessage()).containsIgnoringCase("refunded");
                });

        assertThat(checkoutService.get(second.id()).status()).isEqualTo(FeeStatus.REFUNDED);
        assertThat(checkoutService.get(first.checkoutId()).token().id()).isEqualTo(paid.token().id());
        assertThat(bookedCount(session)).isEqualTo(1);
    }

    @Test
    void simultaneousCheckoutStartsForTheSamePatientCreateOneCheckoutAndKeepOneHold() throws Exception {
        Session session = createSession(30);
        UUID patientId = registerPatient();
        int attempts = 6;

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<StartResult>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return checkoutService.start(patientId, session.slotId());
            }));
        }
        go.countDown();

        Set<UUID> checkoutIds = new HashSet<>();
        long created = 0;
        for (Future<StartResult> future : futures) {
            StartResult result = future.get(60, TimeUnit.SECONDS);
            checkoutIds.add(result.checkout().checkoutId());
            if (result.created()) {
                created++;
            }
        }
        pool.shutdown();

        assertThat(checkoutIds).hasSize(1);
        assertThat(created).isEqualTo(1);
        Long liveHolds = redisTemplate.opsForZSet().size("hq:hold:" + session.slotId());
        assertThat(liveHolds).isEqualTo(1L);
    }
}
