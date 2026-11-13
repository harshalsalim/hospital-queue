package com.hospitalqueue.doctor.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hospitalqueue.doctor.dto.CreateSlotRequest;
import com.hospitalqueue.doctor.dto.SlotResponse;
import com.hospitalqueue.doctor.dto.UpdateSlotCapacityRequest;
import com.hospitalqueue.doctor.dto.UpdateSlotStatusRequest;
import com.hospitalqueue.doctor.service.SlotService;

import jakarta.validation.Valid;

/**
 * Endpoints for a doctor's daily sessions. Reading is public (patients look up availability);
 * creating, pausing and changing limits will require the doctor's own login in the authentication stage.
 */
@RestController
@RequestMapping("/api")
public class SlotController {

    private final SlotService slotService;

    public SlotController(SlotService slotService) {
        this.slotService = slotService;
    }

    /** Creates a session. Returns 201; 400 for invalid input; 404 unknown doctor; 409 on overlap. */
    @PostMapping("/doctors/{doctorId}/slots")
    @ResponseStatus(HttpStatus.CREATED)
    public SlotResponse create(@PathVariable UUID doctorId, @Valid @RequestBody CreateSlotRequest request) {
        return slotService.create(doctorId, request);
    }

    /**
     * Lists a doctor's sessions for one day. The optional {@code date} looks like {@code 2026-11-12};
     * without it, today's sessions are returned.
     */
    @GetMapping("/doctors/{doctorId}/slots")
    public List<SlotResponse> list(@PathVariable UUID doctorId,
                                   @RequestParam(required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return slotService.list(doctorId, date);
    }

    /** Pauses or resumes a session. Returns 404 for an unknown slot. */
    @PatchMapping("/slots/{slotId}/status")
    public SlotResponse updateStatus(@PathVariable UUID slotId, @Valid @RequestBody UpdateSlotStatusRequest request) {
        return slotService.updateStatus(slotId, request.status());
    }

    /** Changes the daily token limit. Returns 404 for an unknown slot, 409 when below the booked count. */
    @PatchMapping("/slots/{slotId}/capacity")
    public SlotResponse updateCapacity(@PathVariable UUID slotId, @Valid @RequestBody UpdateSlotCapacityRequest request) {
        return slotService.updateCapacity(slotId, request.maxTokens());
    }
}
