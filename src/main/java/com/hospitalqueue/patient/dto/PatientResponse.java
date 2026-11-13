package com.hospitalqueue.patient.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Patient as returned by the API. The phone number is always masked, so responses (and anything that
 * logs them) never contain the full number.
 *
 * @param id                patient id; it will be linked to the patient's login in the authentication stage
 * @param maskedPhoneNumber for example {@code +91******3210}
 * @param name              display name, may be null
 * @param createdAt         registration time (UTC)
 */
public record PatientResponse(UUID id, String maskedPhoneNumber, String name, Instant createdAt) {
}
