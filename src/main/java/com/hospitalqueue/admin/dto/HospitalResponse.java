package com.hospitalqueue.admin.dto;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Hospital as returned by the API. Serializable because it is stored in the Redis cache.
 *
 * @param id        hospital id
 * @param name      hospital name
 * @param address   postal address
 * @param createdAt creation time (UTC)
 */
public record HospitalResponse(UUID id, String name, String address, Instant createdAt) implements Serializable {
}
