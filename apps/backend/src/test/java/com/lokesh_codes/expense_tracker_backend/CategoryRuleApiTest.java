package com.lokesh_codes.expense_tracker_backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Filing rules decide where an imported row goes.
 *
 * <p>The order of a rule set is its meaning, so most of what is worth testing
 * here is precedence: rule over file, and earlier rule over later one.
 */
class CategoryRuleApiTest extends ApiTestBase {

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "statement.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    /** Creates a rule and returns its id. */
    private int addRule(String token, String pattern, String matchType, int categoryId)
            throws Exception {
        String body = mockMvc.perform(jsonBody(
                post("/api/category-rules").header("Authorization", bearer(token)), """
                        {"pattern":"%s","matchType":"%s","categoryId":%d,"active":true}
                        """.formatted(pattern, matchType, categoryId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return parse(body).get("id").asInt();
    }

    @Test
    @DisplayName("a rule decides the category, overriding the file's own column")
    void ruleBeatsTheFileColumn() throws Exception {
        String token = signUp("ruler");
        addRule(token, "amazon", "CONTAINS", categoryId(token, "Shopping"));

        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount
                        2026-08-01,AMAZON MKTPLACE UK,Groceries,31.20
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.imported").value(1));

        // The file said Groceries. The user's own rule says otherwise, and the
        // user's rule is the more considered of the two.
        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].categoryName").value("Shopping"));
    }

    @Test
    @DisplayName("the first matching rule wins, in priority order")
    void earlierRuleWins() throws Exception {
        String token = signUp("ordered");
        int shopping = categoryId(token, "Shopping");
        int entertainment = categoryId(token, "Entertainment");

        // Both match a Prime charge. The one created first is tried first.
        addRule(token, "amazon", "CONTAINS", shopping);
        int second = addRule(token, "amazon prime", "CONTAINS", entertainment);

        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount
                        2026-08-01,AMAZON PRIME,Other,8.99
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.imported").value(1));

        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].categoryName").value("Shopping"));

        // Promoting the more specific rule changes the answer, which is the
        // whole reason the order is editable.
        mockMvc.perform(put("/api/category-rules/" + second + "/move-up")
                .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pattern").value("amazon prime"));

        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount
                        2026-08-02,AMAZON PRIME,Other,8.99
                        """))
                .header("Authorization", bearer(token)));

        mockMvc.perform(get("/api/transactions?from=2026-08-02")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].categoryName").value("Entertainment"));
    }

    @Test
    @DisplayName("match types compare case-insensitively")
    void matchTypesIgnoreCase() throws Exception {
        String token = signUp("matcher");
        addRule(token, "Tesco", "STARTS_WITH", categoryId(token, "Groceries"));

        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount
                        2026-08-01,TESCO STORES 3421,,20.00
                        2026-08-02,Refund from tesco,,5.00
                        """))
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.imported").value(2));

        // The first starts with the pattern; the second only contains it.
        mockMvc.perform(get("/api/transactions?from=2026-08-01&to=2026-08-01")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].categoryName").value("Groceries"));

        mockMvc.perform(get("/api/transactions?from=2026-08-02&to=2026-08-02")
                .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].categoryName").value("Uncategorised"));
    }

    @Test
    @DisplayName("an inactive rule is ignored")
    void inactiveRulesDoNotFire() throws Exception {
        String token = signUp("paused");
        int ruleId = addRule(token, "amazon", "CONTAINS", categoryId(token, "Shopping"));

        mockMvc.perform(jsonBody(
                put("/api/category-rules/" + ruleId).header("Authorization", bearer(token)), """
                        {"pattern":"amazon","matchType":"CONTAINS","categoryId":%d,"active":false}
                        """.formatted(categoryId(token, "Shopping"))))
                .andExpect(status().isOk());

        mockMvc.perform(multipart("/api/transactions/import")
                .file(csv("""
                        Date,Description,Category,Amount
                        2026-08-01,AMAZON MKTPLACE,,31.20
                        """))
                .header("Authorization", bearer(token)));

        mockMvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.content[0].categoryName").value("Uncategorised"));
    }

    @Test
    @DisplayName("a rule cannot point at another account's category")
    void rulesCannotReachAnotherAccountsCategory() throws Exception {
        String alice = signUp("alice-rules");
        int aliceShopping = categoryId(alice, "Shopping");

        String bob = signUp("bob-rules");

        // "Not found" rather than "forbidden", so ids cannot be probed.
        mockMvc.perform(jsonBody(
                post("/api/category-rules").header("Authorization", bearer(bob)), """
                        {"pattern":"amazon","matchType":"CONTAINS","categoryId":%d,"active":true}
                        """.formatted(aliceShopping)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("one account never sees another's rules")
    void rulesAreScopedToTheAccount() throws Exception {
        String alice = signUp("alice-list");
        addRule(alice, "therapy", "CONTAINS", categoryId(alice, "Health"));

        String bob = signUp("bob-list");
        mockMvc.perform(get("/api/category-rules").header("Authorization", bearer(bob)))
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("deleting a category takes its rules with it")
    void deletingACategoryRemovesItsRules() throws Exception {
        String token = signUp("tidier");

        mockMvc.perform(jsonBody(post("/api/categories").header("Authorization", bearer(token)),
                """
                        {"name":"Pet Care","color":"#22c55e"}
                        """))
                .andExpect(status().isCreated());

        int petCare = categoryId(token, "Pet Care");
        addRule(token, "vet", "CONTAINS", petCare);

        // A rule pointing at a category that no longer exists cannot do anything
        // but fail, so it goes with it rather than blocking the delete.
        mockMvc.perform(delete("/api/categories/" + petCare)
                .header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/category-rules").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("the match types are published so the client keeps no copy of the enum")
    void matchTypesArePublished() throws Exception {
        String token = signUp("lister");

        mockMvc.perform(get("/api/category-rules/match-types")
                .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.value == 'CONTAINS')].label").value("Contains"));
    }
}
