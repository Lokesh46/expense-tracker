package com.lokesh_codes.expense_tracker_backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lokesh_codes.expense_tracker_backend.entity.Role;
import com.lokesh_codes.expense_tracker_backend.entity.User;
import com.lokesh_codes.expense_tracker_backend.repository.ActivityLogRepository;
import com.lokesh_codes.expense_tracker_backend.repository.BudgetRepository;
import com.lokesh_codes.expense_tracker_backend.repository.CategoryRepository;
import com.lokesh_codes.expense_tracker_backend.repository.CategoryRuleRepository;
import com.lokesh_codes.expense_tracker_backend.repository.RecurringTransactionRepository;
import com.lokesh_codes.expense_tracker_backend.repository.TransactionRepository;
import com.lokesh_codes.expense_tracker_backend.repository.UserRepository;

/**
 * Shared plumbing for the API tests.
 *
 * <p>These drive the application through MockMvc rather than calling services
 * directly, because the defects worth guarding against — the password encoder
 * wiring, the security filter chain, ownership checks — only exist once the
 * whole stack is assembled.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class ApiTestBase {

    protected static final String PASSWORD = "Passw0rd!";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private TransactionRepository transactions;
    @Autowired
    private RecurringTransactionRepository recurring;
    @Autowired
    protected CategoryRuleRepository categoryRules;
    @Autowired
    private BudgetRepository budgets;
    @Autowired
    private CategoryRepository categories;
    @Autowired
    protected UserRepository users;
    @Autowired
    protected ActivityLogRepository activityLog;

    /**
     * The Spring context is shared across the class, so each test starts from an
     * empty database. Without this, ids and counts would depend on the order
     * tests happen to run in.
     *
     * <p>Deleted child-first so foreign keys are never left dangling.
     */
    @BeforeEach
    void resetDatabase() {
        transactions.deleteAllInBatch();
        recurring.deleteAllInBatch();
        budgets.deleteAllInBatch();
        // Before categories: a rule points at one.
        categoryRules.deleteAllInBatch();
        categories.deleteAllInBatch();
        users.deleteAllInBatch();
        // Deliberately outlives its user in production, so it has to be cleared
        // explicitly here or a sign-in count would carry across tests.
        activityLog.deleteAllInBatch();
    }

    // ------------------------------------------------------------------ auth

    /** Registers an account and returns a bearer token for it. */
    protected String signUp(String username) throws Exception {
        mockMvc.perform(jsonBody(post("/register"), """
                {"username":"%s","password":"%s","email":"%s@example.com"}
                """.formatted(username, PASSWORD, username)))
                .andExpect(status().isCreated());

        return tokenFor(username);
    }

    /**
     * Registers an account, promotes it, and returns an administrator's token.
     *
     * <p>Promoted through the repository rather than the API, because the API
     * requires an existing administrator and the first one cannot come from
     * anywhere. This is what {@code ADMIN_USERNAME} does at startup.
     */
    protected String signUpAdmin(String username) throws Exception {
        signUp(username);

        User user = userNamed(username);
        user.setRole(Role.ADMIN);
        users.save(user);

        // Re-issued so the token's own scope claim matches the stored role. The
        // earlier one would work too — AccountStateFilter rebuilds authorities from
        // the database on every request — but a token that disagrees with the
        // database is a confusing thing to debug a failing test against.
        return tokenFor(username);
    }

    protected User userNamed(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new AssertionError("No account named '" + username + "'"));
    }

    protected int userIdOf(String username) {
        return userNamed(username).getId();
    }

    protected String tokenFor(String username) throws Exception {
        String body = mockMvc.perform(jsonBody(post("/authenticate"), """
                {"username":"%s","password":"%s"}
                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return parse(body).get("token").asText();
    }

    // --------------------------------------------------------------- helpers

    protected MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected String bearer(String token) {
        return "Bearer " + token;
    }

    protected JsonNode parse(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    /** The id of the token holder's category with the given name. */
    protected int categoryId(String token, String name) throws Exception {
        String body = mockMvc.perform(get("/api/categories").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        for (JsonNode node : parse(body)) {
            if (node.get("name").asText().equals(name)) {
                return node.get("id").asInt();
            }
        }
        throw new AssertionError("No category named '" + name + "' for this account");
    }

    /** Records a transaction and returns its id. */
    protected int recordExpense(String token, int categoryId, String description, String amount,
            String date) throws Exception {
        String body = mockMvc.perform(jsonBody(
                post("/api/transactions").header("Authorization", bearer(token)), """
                        {"categoryId":%d,"description":"%s","amount":%s,"currency":"GBP",
                         "date":"%s","paymentMethod":"Cash"}
                        """.formatted(categoryId, description, amount, date)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return parse(body).get("id").asInt();
    }
}
