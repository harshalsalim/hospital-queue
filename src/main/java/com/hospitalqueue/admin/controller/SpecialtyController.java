package com.hospitalqueue.admin.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hospitalqueue.admin.dto.CreateSpecialtyRequest;
import com.hospitalqueue.admin.dto.SpecialtyResponse;
import com.hospitalqueue.admin.service.SpecialtyService;

import jakarta.validation.Valid;

/**
 * Specialty endpoints: creation under {@code /api/admin/specialties} and the public list under
 * {@code /api/specialties}. In the authentication stage the admin endpoint will require SYSTEM_ADMIN.
 */
@RestController
@RequestMapping("/api")
public class SpecialtyController {

    private final SpecialtyService specialtyService;

    public SpecialtyController(SpecialtyService specialtyService) {
        this.specialtyService = specialtyService;
    }

    /** Creates a specialty. Returns 201, 400 on validation errors, 409 when the name exists. */
    @PostMapping("/admin/specialties")
    @ResponseStatus(HttpStatus.CREATED)
    public SpecialtyResponse create(@Valid @RequestBody CreateSpecialtyRequest request) {
        return specialtyService.create(request);
    }

    /** Lists all specialties sorted by name (cached). */
    @GetMapping("/specialties")
    public List<SpecialtyResponse> list() {
        return specialtyService.list();
    }
}
