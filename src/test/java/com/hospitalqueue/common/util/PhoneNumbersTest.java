package com.hospitalqueue.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for {@link PhoneNumbers}. */
class PhoneNumbersTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "9876543210",
            "09876543210",
            "919876543210",
            "+919876543210",
            "+91 98765 43210",
            "+91-98765-43210",
            "  98765 43210  ",
            "(98765) 43210",
            "0 98765 43210"
    })
    void normalize_convertsEveryAcceptedFormatToTheSameNumber(String raw) {
        assertThat(PhoneNumbers.normalize(raw)).contains("+919876543210");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "98765",
            "98765432101",
            "5876543210",
            "0123456789",
            "abcdefghij",
            "98765abcde",
            "+12025550123",
            "+91 12345 67890",
            "9876543210 extra"
    })
    void normalize_rejectsNumbersThatAreNotIndianMobiles(String raw) {
        assertThat(PhoneNumbers.normalize(raw)).isEmpty();
    }

    @Test
    void normalize_rejectsNull() {
        assertThat(PhoneNumbers.normalize(null)).isEmpty();
    }

    @Test
    void normalize_acceptsEveryValidFirstDigitFromSixToNine() {
        assertThat(PhoneNumbers.normalize("6123456789")).contains("+916123456789");
        assertThat(PhoneNumbers.normalize("7123456789")).contains("+917123456789");
        assertThat(PhoneNumbers.normalize("8123456789")).contains("+918123456789");
        assertThat(PhoneNumbers.normalize("9123456789")).contains("+919123456789");
    }

    @Test
    void mask_keepsOnlyTheCountryCodeAndTheLastFourDigits() {
        assertThat(PhoneNumbers.mask("+919876543210")).isEqualTo("+91******3210");
    }

    @Test
    void mask_fullyHidesVeryShortOrMissingInput() {
        assertThat(PhoneNumbers.mask(null)).isEqualTo("****");
        assertThat(PhoneNumbers.mask("123")).isEqualTo("****");
    }
}
