package com.hospitalqueue.doctor.dto;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Doctor as returned by the API. Serializable because it is stored in the Redis cache.
 * Hospital and specialty are returned as ids; clients resolve names from the cached
 * hospital and specialty lists.
 *
 * @param id                  doctor id
 * @param hospitalId          hospital the doctor works at
 * @param specialtyId         doctor's specialty
 * @param name                doctor's full name
 * @param registrationCouncil council that issued the registration
 * @param registrationNumber  registration number
 * @param createdAt           creation time (UTC)
 */
public record DoctorResponse(
        UUID id,
        UUID hospitalId,
        UUID specialtyId,
        String name,
        String registrationCouncil,
        String registrationNumber,
        Instant createdAt) implements Serializable {
}
