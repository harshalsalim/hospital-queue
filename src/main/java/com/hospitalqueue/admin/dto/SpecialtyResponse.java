package com.hospitalqueue.admin.dto;

import java.io.Serializable;
import java.util.UUID;

/**
 * Specialty as returned by the API. Serializable because it is stored in the Redis cache.
 *
 * @param id   specialty id
 * @param name specialty name
 */
public record SpecialtyResponse(UUID id, String name) implements Serializable {
}
