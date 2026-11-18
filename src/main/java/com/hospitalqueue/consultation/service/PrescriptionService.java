package com.hospitalqueue.consultation.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.consultation.dto.IssuePrescriptionRequest;
import com.hospitalqueue.consultation.dto.PrescriptionItemRequest;
import com.hospitalqueue.consultation.dto.PrescriptionItemResponse;
import com.hospitalqueue.consultation.dto.PrescriptionResponse;
import com.hospitalqueue.consultation.entity.Consultation;
import com.hospitalqueue.consultation.entity.Prescription;
import com.hospitalqueue.consultation.entity.PrescriptionItem;
import com.hospitalqueue.consultation.repository.ConsultationRepository;
import com.hospitalqueue.consultation.repository.PrescriptionItemRepository;
import com.hospitalqueue.consultation.repository.PrescriptionRepository;
import com.hospitalqueue.doctor.dto.DoctorResponse;
import com.hospitalqueue.doctor.service.DoctorService;
import com.hospitalqueue.patient.service.PatientService;

/**
 * Business logic for prescriptions.
 *
 * <p>Issuing saves the prescription and all its medicines in ONE transaction, so there is never a prescription with
 * missing medicines. A consultation gets at most one prescription (enforced by the database), and a prescription can
 * never be changed afterwards. The doctor's registration is copied onto it at that moment.
 *
 * <p>Prescriptions are health information: nothing here is cached, and no medicine, advice or lab test is ever written
 * to a log or an error message. Listing many prescriptions loads their medicines with one query, not one per prescription.
 */
@Service
public class PrescriptionService {

    private final ConsultationRepository consultationRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final PrescriptionItemRepository prescriptionItemRepository;
    private final DoctorService doctorService;
    private final PatientService patientService;
    private final TransactionTemplate transactionTemplate;

    public PrescriptionService(ConsultationRepository consultationRepository,
                               PrescriptionRepository prescriptionRepository,
                               PrescriptionItemRepository prescriptionItemRepository,
                               DoctorService doctorService,
                               PatientService patientService,
                               TransactionTemplate transactionTemplate) {
        this.consultationRepository = consultationRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.prescriptionItemRepository = prescriptionItemRepository;
        this.doctorService = doctorService;
        this.patientService = patientService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Issues the prescription of a consultation. Text is trimmed, and blank advice or lab tests count as none.
     *
     * @param consultationId the consultation
     * @param request        validated request body
     * @return the issued prescription with its medicines
     * @throws ApiException 404 unknown consultation; 400 {@code EMPTY_PRESCRIPTION} when there is no medicine, advice or
     *                      lab test; 409 {@code PRESCRIPTION_EXISTS} when one was already issued (also when two requests race)
     */
    public PrescriptionResponse issue(UUID consultationId, IssuePrescriptionRequest request) {
        Consultation consultation = consultationRepository.findById(consultationId)
                .orElseThrow(() -> ApiException.notFound("Consultation", consultationId));

        String advice = blankToNull(request.therapyAdvice());
        String labTests = blankToNull(request.labTests());
        List<PrescriptionItemRequest> items = request.items() == null ? List.of() : request.items();
        if (items.isEmpty() && advice == null && labTests == null) {
            throw ApiException.badRequest("EMPTY_PRESCRIPTION",
                    "A prescription needs at least one medicine, therapy advice or lab tests");
        }
        if (prescriptionRepository.existsByConsultationId(consultationId)) {
            throw prescriptionExists();
        }

        DoctorResponse doctor = doctorService.get(consultation.getDoctorId());
        try {
            return transactionTemplate.execute(status -> save(consultation, doctor, advice, labTests, items));
        } catch (DataIntegrityViolationException failure) {
            if (prescriptionRepository.existsByConsultationId(consultationId)) {
                throw prescriptionExists();
            }
            throw failure;
        }
    }

    /**
     * Finds one prescription.
     *
     * @param id prescription id
     * @return the prescription with its medicines
     * @throws ApiException 404 when no prescription has this id
     */
    @Transactional(readOnly = true)
    public PrescriptionResponse get(UUID id) {
        Prescription prescription = prescriptionRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Prescription", id));
        return toResponse(prescription, prescriptionItemRepository.findByPrescriptionIdIn(List.of(id)));
    }

    /**
     * Finds the prescription issued for a consultation.
     *
     * @param consultationId the consultation
     * @return the prescription with its medicines
     * @throws ApiException 404 when none was issued for this consultation
     */
    @Transactional(readOnly = true)
    public PrescriptionResponse getByConsultation(UUID consultationId) {
        Prescription prescription = prescriptionRepository.findByConsultationId(consultationId)
                .orElseThrow(() -> ApiException.notFound("Prescription for consultation", consultationId));
        return toResponse(prescription, prescriptionItemRepository.findByPrescriptionIdIn(List.of(prescription.getId())));
    }

    /**
     * Lists a patient's 50 most recent prescriptions, newest first, each with its medicines (two database queries in
     * total, whatever the number of prescriptions).
     *
     * @param patientId the patient
     * @return the prescriptions, possibly empty
     * @throws ApiException 404 when the patient does not exist
     */
    @Transactional(readOnly = true)
    public List<PrescriptionResponse> listForPatient(UUID patientId) {
        if (!patientService.exists(patientId)) {
            throw ApiException.notFound("Patient", patientId);
        }
        List<Prescription> prescriptions = prescriptionRepository.findTop50ByPatientIdOrderByCreatedAtDesc(patientId);
        if (prescriptions.isEmpty()) {
            return List.of();
        }

        List<UUID> ids = prescriptions.stream().map(Prescription::getId).toList();
        Map<UUID, List<PrescriptionItem>> itemsByPrescription = new HashMap<>();
        for (PrescriptionItem item : prescriptionItemRepository.findByPrescriptionIdIn(ids)) {
            itemsByPrescription.computeIfAbsent(item.getPrescriptionId(), key -> new java.util.ArrayList<>()).add(item);
        }
        return prescriptions.stream()
                .map(prescription -> toResponse(prescription, itemsByPrescription.getOrDefault(prescription.getId(), List.of())))
                .toList();
    }

    private PrescriptionResponse save(Consultation consultation, DoctorResponse doctor, String advice, String labTests,
                                      List<PrescriptionItemRequest> items) {
        Prescription prescription = prescriptionRepository.saveAndFlush(new Prescription(
                consultation.getHospitalId(), consultation.getPatientId(), consultation.getDoctorId(), consultation.getId(),
                doctor.registrationCouncil(), doctor.registrationNumber(), advice, labTests));

        List<PrescriptionItem> lines = IntStream.range(0, items.size())
                .mapToObj(index -> {
                    PrescriptionItemRequest item = items.get(index);
                    return new PrescriptionItem(prescription.getId(), index, item.medicineName().trim(),
                            item.dosage().trim(), item.durationDays(), item.quantity());
                })
                .toList();
        List<PrescriptionItem> saved = prescriptionItemRepository.saveAll(lines);
        return toResponse(prescription, saved);
    }

    private PrescriptionResponse toResponse(Prescription prescription, List<PrescriptionItem> items) {
        List<PrescriptionItemResponse> lines = items.stream()
                .sorted(Comparator.comparingInt(PrescriptionItem::getSortOrder))
                .map(item -> new PrescriptionItemResponse(item.getId(), item.getMedicineName(), item.getDosage(),
                        item.getDurationDays(), item.getQuantity()))
                .toList();
        return new PrescriptionResponse(prescription.getId(), prescription.getConsultationId(), prescription.getHospitalId(),
                prescription.getPatientId(), prescription.getDoctorId(), prescription.getDoctorRegistrationCouncil(),
                prescription.getDoctorRegistrationNumber(), prescription.getTherapyAdvice(), prescription.getLabTests(),
                lines, prescription.getCreatedAt());
    }

    private static ApiException prescriptionExists() {
        return ApiException.conflict("PRESCRIPTION_EXISTS", "A prescription was already issued for this consultation");
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
