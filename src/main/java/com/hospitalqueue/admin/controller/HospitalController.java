package com.hospitalqueue.admin.controller;

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

import com.hospitalqueue.admin.dto.CreateHospitalRequest;
import com.hospitalqueue.admin.dto.HospitalResponse;
import com.hospitalqueue.admin.service.HospitalService;
import com.hospitalqueue.common.dto.PageResponse;

import jakarta.validation.Valid;

/**
 * Hospital endpoints: onboarding under {@code /api/admin/hospitals} and public reads under
 * {@code /api/hospitals}. In the authentication stage the admin endpoint will require SYSTEM_ADMIN.
 */
@RestController
@RequestMapping("/api")
public class HospitalController {

    /** Largest page size a caller can request. */
    static final int MAX_PAGE_SIZE = 100;

    private final HospitalService hospitalService;

    public HospitalController(HospitalService hospitalService) {
        this.hospitalService = hospitalService;
    }

    /** Creates a hospital. Returns 201, or 400 when validation fails. */
    @PostMapping("/admin/hospitals")
    @ResponseStatus(HttpStatus.CREATED)
    public HospitalResponse create(@Valid @RequestBody CreateHospitalRequest request) {
        return hospitalService.create(request);
    }

    /**
     * Lists hospitals sorted by name. {@code page} below 0 becomes 0, and {@code size} is kept
     * between 1 and {@value #MAX_PAGE_SIZE}, so a caller can never request an unbounded list.
     */
    @GetMapping("/hospitals")
    public PageResponse<HospitalResponse> list(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return hospitalService.list(safePage, safeSize);
    }

    /** Returns one hospital, 404 when it does not exist, 400 when the id is not a UUID. */
    @GetMapping("/hospitals/{id}")
    public HospitalResponse get(@PathVariable UUID id) {
        return hospitalService.get(id);
    }
}
