package com.lokesh_codes.expense_tracker_backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lokesh_codes.expense_tracker_backend.DTO.MerchantAssignmentDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.ReviewActionDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.ReviewQueueDTO;
import com.lokesh_codes.expense_tracker_backend.service.CategoryReviewService;

import jakarta.validation.Valid;

/**
 * The queue of imported rows whose category was guessed.
 *
 * <p>Everything here is scoped to the signed-in account inside the service; a
 * merchant is addressed by a digest that is derived from the caller's own key,
 * so one account's identifier means nothing in another's.
 */
@RestController
@RequestMapping("/api/review")
public class ReviewController {

    private final CategoryReviewService review;

    public ReviewController(CategoryReviewService review) {
        this.review = review;
    }

    /** The merchants waiting, largest group first. */
    @GetMapping("/merchants")
    public ResponseEntity<ReviewQueueDTO> merchants() {
        return ResponseEntity.ok(review.merchants());
    }

    /** Counts only, for the navigation badge. */
    @GetMapping("/summary")
    public ResponseEntity<ReviewQueueDTO> summary() {
        return ResponseEntity.ok(review.summary());
    }

    /** Accepts the suggested category for one merchant, unchanged. */
    @PostMapping("/merchants/{merchantHash}/approve")
    public ResponseEntity<ReviewActionDTO> approve(@PathVariable String merchantHash) {
        return ResponseEntity.ok(review.approve(merchantHash));
    }

    /** Refiles one merchant, and by default remembers the decision as a rule. */
    @PostMapping("/merchants/{merchantHash}/assign")
    public ResponseEntity<ReviewActionDTO> assign(@PathVariable String merchantHash,
            @Valid @RequestBody MerchantAssignmentDTO request) {
        return ResponseEntity.ok(review.assign(merchantHash, request));
    }

    @PostMapping("/approve-all")
    public ResponseEntity<ReviewActionDTO> approveAll() {
        return ResponseEntity.ok(review.approveAll());
    }
}
