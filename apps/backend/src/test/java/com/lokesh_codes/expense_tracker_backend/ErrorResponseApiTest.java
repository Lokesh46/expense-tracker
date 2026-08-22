package com.lokesh_codes.expense_tracker_backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/**
 * Client mistakes report as client errors.
 *
 * <p>Each of these used to reach the catch-all handler and come back as a 500
 * with a stack trace in the log. On a public deployment that is not a rare edge
 * case -- scanners and mistyped URLs arrive constantly -- and at error level they
 * bury anything that matters.
 */
class ErrorResponseApiTest extends ApiTestBase {

    @Test
    void theRootPathIsANotFound() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("No such endpoint."));
    }

    /**
     * A browser asks for this unprompted, and the API serves no static content --
     * but it is not a permitted path, so the security chain answers first. 401
     * rather than 404 is the right answer: whether a path exists is not something
     * an anonymous caller gets to learn.
     */
    @Test
    void anUnauthenticatedRequestForAMissingFileIsRefusedNotDescribed() throws Exception {
        mockMvc.perform(get("/favicon.ico")).andExpect(status().isUnauthorized());
    }

    /**
     * An unmapped path under /api needs a token first, so this checks the 404 is
     * reached rather than masked by the security chain.
     */
    @Test
    void anUnmappedApiPathIsANotFoundOnceAuthenticated() throws Exception {
        String token = signUp("mia");

        mockMvc.perform(get("/api/nothing-here").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());

        // Without a token it is still a 401: whether the path exists is not
        // something an anonymous caller gets to find out.
        mockMvc.perform(get("/api/nothing-here")).andExpect(status().isUnauthorized());
    }

    @Test
    void theWrongVerbIsAMethodNotAllowed() throws Exception {
        String token = signUp("mia");

        mockMvc.perform(delete("/api/categories").header("Authorization", bearer(token)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                // 405 has to say what would have worked.
                .andExpect(header().exists("Allow"));
    }

    /**
     * Posting to the CSV import endpoint with nothing attached.
     *
     * <p>415 rather than 400: the endpoint consumes multipart and the request was
     * not multipart at all, so the format is what is wrong. Either way it is the
     * caller's mistake, and it used to come back as a 500 -- which told someone
     * whose upload failed nothing about why.
     */
    @Test
    void aBodyInTheWrongFormatIsAnUnsupportedMediaType() throws Exception {
        String token = signUp("mia");

        mockMvc.perform(post("/api/transactions/import").header("Authorization", bearer(token)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.message").value("This endpoint expects multipart/form-data."));
    }

    /** Every error shares one shape, so the client has one thing to parse. */
    @Test
    void everyErrorHasTheSameEnvelope() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists());
    }
}
