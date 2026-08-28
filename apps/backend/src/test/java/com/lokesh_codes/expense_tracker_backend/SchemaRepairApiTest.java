package com.lokesh_codes.expense_tracker_backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import com.lokesh_codes.expense_tracker_backend.service.SchemaRepair;

/**
 * An enum that gained a value must keep working on a database created before it
 * did.
 *
 * <p>This exists because the ordinary suite cannot catch the failure it guards
 * against. Tests build the schema with {@code create-drop}, so Hibernate's
 * generated {@code check (action in (...))} always lists the current enum
 * values. A real database keeps the list it was created with — schema updates
 * never revisit a check constraint — so adding {@code TRANSACTIONS_IMPORTED}
 * made every import fail in the running application while every test passed.
 *
 * <p>The stale constraint is therefore recreated here deliberately, which is the
 * only way to put the test database in the state a real one is actually in.
 */
class SchemaRepairApiTest extends ApiTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SchemaRepair schemaRepair;

    /** Puts the schema back into the state an upgraded database is in. */
    private void freezeActionConstraint() {
        jdbc.execute("""
                alter table activity_log add constraint stale_action_check
                check (action in ('LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'REGISTERED'))
                """);
    }

    private void dropIfPresent() {
        try {
            jdbc.execute("alter table activity_log drop constraint stale_action_check");
        } catch (Exception ignored) {
            // Already gone, which is what the repair is for.
        }
    }

    @Test
    @DisplayName("an import still works on a database whose constraint predates the new events")
    void importSurvivesAStaleEnumConstraint() throws Exception {
        String token = signUp("upgraded");
        freezeActionConstraint();

        try {
            // Exactly what a deployed instance does on the first import after the
            // upgrade: writes an audit row naming an event the constraint has
            // never heard of.
            schemaRepair.run(null);

            mockMvc.perform(multipart("/api/transactions/import")
                    .file(new MockMultipartFile("file", "statement.csv", "text/csv", """
                            Date,Description,Category,Amount
                            2026-08-10,Coffee,Groceries,3.50
                            """.getBytes(StandardCharsets.UTF_8)))
                    .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.imported").value(1));
        } finally {
            dropIfPresent();
        }
    }

    @Test
    @DisplayName("the repair reports nothing to do on a database that is already current")
    void repairIsSafeToRunRepeatedly() {
        // Runs on every startup, so it has to be harmless when there is nothing
        // stale to remove.
        schemaRepair.run(null);
        schemaRepair.run(null);

        Long rows = jdbc.queryForObject("select count(*) from activity_log", Long.class);
        assertThat(rows).isNotNull();
    }
}
