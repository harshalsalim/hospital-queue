package com.hospitalqueue.doctor.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hospitalqueue.common.dto.PageResponse;
import com.hospitalqueue.doctor.dto.CreateDoctorRequest;
import com.hospitalqueue.doctor.dto.DoctorResponse;
import com.hospitalqueue.doctor.service.DoctorService;

import jakarta.validation.Valid;

/**
 * Doctor endpoints: onboarding under {@code /api/admin/hospitals/{hospitalId}/doctors} and public
 * discovery under {@code /api/doctors}. In the authentication stage the admin endpoint will be
 * restricted to hospital admins of that hospital.
 */
@RestController
@RequestMapping("/api")
public class DoctorController {

    /** Largest page size a caller can request. */
    static final int MAX_PAGE_SIZE = 100;

    private final DoctorService doctorService;

    public DoctorController(DoctorService doctorService) {
        this.doctorService = doctorService;
    }

    /**
     * Adds a doctor to the hospital in the URL. Returns 201, 400 for validation errors or an unknown
     * specialty, 404 for an unknown hospital, 409 for a duplicate registration.
     */
    @PostMapping("/admin/hospitals/{hospitalId}/doctors")
    @ResponseStatus(HttpStatus.CREATED)
    public DoctorResponse create(@PathVariable UUID hospitalId, @Valid @RequestBody CreateDoctorRequest request) {
        return doctorService.create(hospitalId, request);
    }

    /**
     * Doctor discovery. Optional filters: {@code hospitalId} and {@code specialtyId}. {@code page} below 0
     * becomes 0, and {@code size} is kept between 1 and {@value #MAX_PAGE_SIZE}.
     */
    @GetMapping("/doctors")
    public PageResponse<DoctorResponse> list(@RequestParam(required = false) UUID hospitalId,
                                             @RequestParam(required = false) UUID specialtyId,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return doctorService.list(hospitalId, specialtyId, safePage, safeSize);
    }

    /** Returns one doctor, 404 when not found, 400 when the id is not a UUID. */
    @GetMapping("/doctors/{id}")
    public DoctorResponse get(@PathVariable UUID id) {
        return doctorService.get(id);
    }
}
