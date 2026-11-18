package com.hospitalqueue.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link FieldEncryptor}: round trips, randomness, tamper detection and key validation. */
class FieldEncryptorTest {

    private static String keyOf(int seed) {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (seed + i);
        }
        return Base64.getEncoder().encodeToString(raw);
    }

    private final FieldEncryptor encryptor = new FieldEncryptor(keyOf(1));

    // ---- round trips ----

    @Test
    void aValueSurvivesEncryptionAndDecryption() {
        String text = "Patient reports chest pain for two days. Advised ECG.";

        assertThat(encryptor.decrypt(encryptor.encrypt(text))).isEqualTo(text);
    }

    @Test
    void malayalamAndOtherUnicodeTextSurvives() {
        String text = "രോഗിക്ക് പനിയും ചുമയും ഉണ്ട്. Température élevée ✓";

        assertThat(encryptor.decrypt(encryptor.encrypt(text))).isEqualTo(text);
    }

    @Test
    void anEmptyStringAndAVeryLongTextSurvive() {
        String longText = "a clinical sentence. ".repeat(2000);

        assertThat(encryptor.decrypt(encryptor.encrypt(""))).isEmpty();
        assertThat(encryptor.decrypt(encryptor.encrypt(longText))).isEqualTo(longText);
    }

    @Test
    void nullStaysNullInBothDirections() {
        assertThat(encryptor.encrypt(null)).isNull();
        assertThat(encryptor.decrypt(null)).isNull();
    }

    // ---- what is stored ----

    @Test
    void theStoredFormHasTheVersionPrefixAndHidesThePlaintext() {
        String stored = encryptor.encrypt("Diabetes follow-up");

        assertThat(stored).startsWith("v1:");
        assertThat(stored).doesNotContain("Diabetes").doesNotContain("follow-up");
    }

    @Test
    void theSameTextNeverProducesTheSameStoredValue() {
        String first = encryptor.encrypt("same text");
        String second = encryptor.encrypt("same text");

        assertThat(first).isNotEqualTo(second);
        assertThat(encryptor.decrypt(first)).isEqualTo(encryptor.decrypt(second));
    }

    // ---- failures ----

    @Test
    void aTamperedValueIsRejectedAndTheErrorRevealsNothing() {
        String text = "secret clinical text";
        String stored = encryptor.encrypt(text);
        int middle = "v1:".length() + (stored.length() - "v1:".length()) / 2;
        char original = stored.charAt(middle);
        char replacement = original == 'A' ? 'B' : 'A';
        String tampered = stored.substring(0, middle) + replacement + stored.substring(middle + 1);

        assertThatThrownBy(() -> encryptor.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("secret").doesNotContain("clinical"));
    }

    @Test
    void aValueEncryptedWithAnotherKeyCannotBeRead() {
        String stored = new FieldEncryptor(keyOf(50)).encrypt("secret clinical text");

        assertThatThrownBy(() -> encryptor.decrypt(stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aValueWithoutTheVersionPrefixIsRejected() {
        assertThatThrownBy(() -> encryptor.decrypt("plain text that was never encrypted"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aValueThatIsTooShortOrNotBase64IsRejected() {
        assertThatThrownBy(() -> encryptor.decrypt("v1:AAAA")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> encryptor.decrypt("v1:***not-base64***")).isInstanceOf(IllegalStateException.class);
    }

    // ---- key validation ----

    @Test
    void aKeyThatIsNotBase64IsRejectedAtStartup() {
        assertThatThrownBy(() -> new FieldEncryptor("this is not base64 !!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    void aKeyOfTheWrongLengthIsRejectedAtStartup() {
        String sixteenBytes = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new FieldEncryptor(sixteenBytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void aKeyWithSurroundingSpacesIsAccepted() {
        assertThatCode(() -> new FieldEncryptor("  " + keyOf(3) + "  ")).doesNotThrowAnyException();
    }

    @Test
    void theDevelopmentKeyInApplicationYamlIsAValidKey() {
        FieldEncryptor development = new FieldEncryptor(FieldEncryptor.DEVELOPMENT_KEY);

        assertThat(development.decrypt(development.encrypt("works"))).isEqualTo("works");
    }
}
