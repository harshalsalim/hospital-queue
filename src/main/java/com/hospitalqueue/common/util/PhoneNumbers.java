package com.hospitalqueue.common.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers for Indian mobile numbers, which are the patient's identity in this system.
 *
 * <p>Accepted input examples: {@code 9876543210}, {@code 09876543210}, {@code 919876543210},
 * {@code +91 98765 43210}, {@code +91-98765-43210}. Everything is stored in one normalised form,
 * {@code +91XXXXXXXXXX}, so the same person can never be registered twice with different formatting.
 * Phone numbers are personal data: never log them, and use {@link #mask(String)} in responses.
 */
public final class PhoneNumbers {

    /** Optional country prefix (+91, 91 or 0) followed by a 10-digit number starting with 6 to 9. */
    private static final Pattern INDIAN_MOBILE = Pattern.compile("^(?:\\+91|91|0)?([6-9]\\d{9})$");

    private PhoneNumbers() {
    }

    /**
     * Converts any accepted format to {@code +91XXXXXXXXXX}.
     *
     * @param raw the number as typed by the user; spaces, dashes and brackets are ignored
     * @return the normalised number, or empty when it is not a valid Indian mobile number
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String compact = raw.replaceAll("[\\s\\-()]", "");
        Matcher matcher = INDIAN_MOBILE.matcher(compact);
        return matcher.matches() ? Optional.of("+91" + matcher.group(1)) : Optional.empty();
    }

    /**
     * Hides the middle of a normalised number for use in responses, for example
     * {@code +91******3210}. Only the country code and the last four digits stay visible.
     *
     * @param normalized a number returned by {@link #normalize(String)}
     * @return the masked number; very short input is fully masked
     */
    public static String mask(String normalized) {
        if (normalized == null || normalized.length() < 8) {
            return "****";
        }
        int length = normalized.length();
        return normalized.substring(0, 3) + "*".repeat(length - 7) + normalized.substring(length - 4);
    }
}
