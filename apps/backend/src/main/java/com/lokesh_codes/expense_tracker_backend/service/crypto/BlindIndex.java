package com.lokesh_codes.expense_tracker_backend.service.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.crypto.Mac;

import org.springframework.stereotype.Component;

/**
 * Makes encrypted text searchable without decrypting it.
 *
 * <p>Ciphertext cannot be matched with {@code LIKE}, so searching an encrypted
 * description would otherwise mean loading every row and filtering in Java —
 * which abandons paging and stops working at a few thousand rows.
 *
 * <p>Instead each word is hashed with a keyed digest and the digests are stored
 * alongside. A search term is hashed the same way and matched against that
 * column, so the filtering still happens in the database. Keyed rather than a
 * plain hash: an unkeyed digest of a word is trivially reversed with a
 * dictionary, which would undo the encryption it sits next to.
 *
 * <p><strong>What this costs.</strong> Whole words match; a fragment does not.
 * Searching "starbucks" finds "Starbucks Coffee"; searching "star" does not.
 * That is inherent — a digest of a word tells you nothing about its prefixes,
 * which is the same property that makes the column safe to store.
 */
@Component
public class BlindIndex {

    private static final String HMAC = "HmacSHA256";

    /** Anything that is not a letter or a digit separates one word from the next. */
    private static final String WORD_SEPARATOR = "[^\\p{L}\\p{N}]+";

    /**
     * Base64 characters kept per digest. Seventy-two bits: far too many for a
     * collision to matter over one account's transactions, and short enough that
     * a long description still fits the column.
     */
    private static final int DIGEST_LENGTH = 12;

    /** Beyond this the index is truncated rather than overflowing its column. */
    private static final int MAX_TOKENS = 120;

    private final DataKeyProvider keys;

    public BlindIndex(DataKeyProvider keys) {
        this.keys = keys;
    }

    /**
     * Builds the stored index for one row.
     *
     * <p>Padded with spaces at both ends so that a {@code LIKE '% digest %'}
     * matches a whole digest and never the tail of a neighbouring one.
     */
    public String tokensFor(String... values) {
        Set<String> digests = new LinkedHashSet<>();

        for (String value : values) {
            for (String word : words(value)) {
                digests.add(digest(word));
                if (digests.size() >= MAX_TOKENS) {
                    break;
                }
            }
        }

        // A single space rather than null when there is nothing to index. The
        // column being set is what marks a row as done; leaving it null would
        // make a row with no indexable words look permanently pending, and the
        // backfill would keep picking it up forever.
        if (digests.isEmpty()) {
            return " ";
        }
        return " " + String.join(" ", digests) + " ";
    }

    /** The digests a search term must all be present for, in the same form as the stored ones. */
    public List<String> queryDigests(String search) {
        List<String> digests = new ArrayList<>();
        for (String word : words(search)) {
            digests.add(digest(word));
        }
        return digests;
    }

    private List<String> words(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> words = new ArrayList<>();
        for (String word : value.toLowerCase(Locale.ROOT).split(WORD_SEPARATOR)) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    private String digest(String word) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(keys.indexKey());
            byte[] full = mac.doFinal(word.getBytes(StandardCharsets.UTF_8));
            // URL-safe so the value never contains '+' or '/', which would need
            // escaping inside a LIKE pattern.
            return java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(full)
                    .substring(0, DIGEST_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not build a search digest", e);
        }
    }
}
