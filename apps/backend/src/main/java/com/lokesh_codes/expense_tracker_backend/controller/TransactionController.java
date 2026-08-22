package com.lokesh_codes.expense_tracker_backend.controller;

import java.io.IOException;
import java.time.LocalDate;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.lokesh_codes.expense_tracker_backend.DTO.ImportResultDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.PageResponse;
import com.lokesh_codes.expense_tracker_backend.DTO.TransactionDTO;
import com.lokesh_codes.expense_tracker_backend.DTO.TransactionFilter;
import com.lokesh_codes.expense_tracker_backend.service.RecurringTransactionService;
import com.lokesh_codes.expense_tracker_backend.service.TransactionCsvService;
import com.lokesh_codes.expense_tracker_backend.service.TransactionService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final TransactionCsvService csvService;
    private final RecurringTransactionService recurringService;

    public TransactionController(TransactionService transactionService,
            TransactionCsvService csvService,
            RecurringTransactionService recurringService) {
        this.transactionService = transactionService;
        this.csvService = csvService;
        this.recurringService = recurringService;
    }

    /**
     * Filtered, sorted and paged search.
     *
     * <p>Any recurring transactions that have come due are materialised first, so
     * the list a user sees is complete even when the nightly sweep has not run.
     */
    @GetMapping
    public ResponseEntity<PageResponse<TransactionDTO>> search(
            @ModelAttribute TransactionFilter filter,
            @PageableDefault(size = 20, sort = "date", direction = Sort.Direction.DESC) Pageable pageable) {

        recurringService.generateDueForCurrentUser();
        return ResponseEntity.ok(transactionService.search(filter, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionDTO> getById(@PathVariable Integer id) {
        return ResponseEntity.ok(transactionService.getTransactionById(id));
    }

    @PostMapping
    public ResponseEntity<TransactionDTO> create(@Valid @RequestBody TransactionDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.createTransaction(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TransactionDTO> update(@PathVariable Integer id,
            @Valid @RequestBody TransactionDTO dto) {
        return ResponseEntity.ok(transactionService.updateTransaction(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id) {
        transactionService.deleteTransaction(id);
        return ResponseEntity.noContent().build();
    }

    /** Exports the transactions matching the same filters as the search endpoint. */
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> export(@ModelAttribute TransactionFilter filter) {
        String csv = csvService.export(filter);
        String filename = "transactions-" + LocalDate.now() + ".csv";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResultDTO> importCsv(@RequestParam("file") MultipartFile file)
            throws IOException {
        return ResponseEntity.ok(csvService.importCsv(file));
    }
}
