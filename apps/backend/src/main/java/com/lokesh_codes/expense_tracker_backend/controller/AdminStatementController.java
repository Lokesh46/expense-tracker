package com.lokesh_codes.expense_tracker_backend.controller;

import java.io.IOException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.lokesh_codes.expense_tracker_backend.DTO.StatementPreviewDTO;
import com.lokesh_codes.expense_tracker_backend.service.CurrentUserService;
import com.lokesh_codes.expense_tracker_backend.service.StatementPreviewService;
import com.lokesh_codes.expense_tracker_backend.service.StatementPreviewStore;

/**
 * A diagnostic for statements that will not import.
 *
 * <p>Shows what the parser saw — the extracted text, the line it took as the
 * header, and the CSV it would have read — so that a failing file can be looked
 * at rather than guessed about.
 *
 * <p><strong>Off unless switched on.</strong> {@code app.admin.statement-preview}
 * defaults to false, so this endpoint does not exist on a normal deployment.
 * That is what keeps it honest: a tool for reading uploaded files is worth
 * having while a format is being worked out and is not worth leaving exposed
 * afterwards.
 *
 * <p>It reads nothing from the database and writes nothing to it. This is not a
 * way for an administrator to see somebody's spending — it describes the file in
 * the request and nothing else, which is why it can sit under {@code /admin}
 * without contradicting the rule that administration grants no sight of anyone's
 * money.
 */
@RestController
@RequestMapping("/api/admin/statement")
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "app.admin.statement-preview", havingValue = "true")
public class AdminStatementController {

    private final StatementPreviewService previewService;
    private final StatementPreviewStore store;
    private final CurrentUserService currentUser;

    public AdminStatementController(StatementPreviewService previewService,
            StatementPreviewStore store,
            CurrentUserService currentUser) {
        this.previewService = previewService;
        this.store = store;
        this.currentUser = currentUser;
    }

    /**
     * Describes an uploaded statement without importing it.
     *
     * <p>{@code redact} replaces every letter with x and every digit with 9,
     * in place, leaving the header and all the spacing intact. The result
     * describes the file's shape exactly and its contents not at all, which is
     * the form to share when asking somebody for help with a layout.
     */
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StatementPreviewDTO> preview(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "pdfPassword", required = false) String pdfPassword,
            @RequestParam(value = "redact", defaultValue = "true") boolean redact)
            throws IOException {

        StatementPreviewDTO preview = previewService.preview(file, pdfPassword, redact);

        // Kept only so it can be collected from another device -- the statement
        // is often on a phone and the person reading the result is not. The
        // store ignores unredacted previews, so nothing with real values in it
        // is held anywhere.
        store.keep(currentUser.require().getUsername(), preview);

        return ResponseEntity.ok(preview);
    }

    /**
     * The last preview this administrator took, if it has not expired.
     *
     * <p>Exists so a statement can be uploaded from the device it is on and read
     * from the device you are working at. Only redacted previews are ever
     * available here; an unredacted one is returned once and forgotten.
     *
     * <p>204 when there is nothing waiting, which is the ordinary case.
     */
    @GetMapping("/last")
    public ResponseEntity<StatementPreviewDTO> last() {
        return store.lastFor(currentUser.require().getUsername())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Forgets the kept preview now rather than waiting for it to expire. */
    @DeleteMapping("/last")
    public ResponseEntity<Void> discard() {
        store.discard(currentUser.require().getUsername());
        return ResponseEntity.noContent().build();
    }
}
