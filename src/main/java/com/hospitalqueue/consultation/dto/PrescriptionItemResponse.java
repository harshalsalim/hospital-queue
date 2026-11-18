package com.hospitalqueue.consultation.dto;

import java.util.UUID;

/**
 * One medicine line of a prescription as returned by the API. Health information: never log it.
 *
 * @param id           line id
 * @param medicineName medicine name
 * @param dosage       how to take it
 * @param durationDays how many days
 * @param quantity     how many units to supply
 */
public record PrescriptionItemResponse(UUID id, String medicineName, String dosage, int durationDays, int quantity) {
}
