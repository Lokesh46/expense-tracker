package com.lokesh_codes.expense_tracker_backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ExpenseTrackerBackendApplicationTests {

    /**
     * Catches wiring mistakes — a missing bean, a bad property placeholder, a
     * circular dependency — that unit tests never see.
     */
    @Test
    @DisplayName("the application context starts")
    void contextLoads() {
    }
}
