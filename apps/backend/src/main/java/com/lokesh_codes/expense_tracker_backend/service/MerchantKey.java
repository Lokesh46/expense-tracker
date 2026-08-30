package com.lokesh_codes.expense_tracker_backend.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reduces a statement description to the merchant it is about.
 *
 * <p>A bank writes the same shop a different way every month:
 * {@code UPI-SWIGGY-1234@ybl} one time, {@code POS 4123XXXX9876 SWIGGY BANGALORE}
 * the next. The merchant is in there, wrapped in a channel prefix, a reference
 * number, a payment handle and a city. Everything downstream — grouping a
 * hundred rows into one decision, and remembering where that decision filed
 * them — needs those two to come out as one key.
 *
 * <p>The reduction is: drop the payment handle, then drop every token that is a
 * channel name, an aggregator, a reference number, a month or too short to be a
 * name. What is left is the merchant.
 */
final class MerchantKey {

    /** A UPI payment handle: an {@code @} and everything up to the next space. */
    private static final Pattern HANDLE = Pattern.compile("@\\S*");

    /** Anything that is not a letter or a digit separates one token from the next. */
    private static final Pattern SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");

    /**
     * How many surviving tokens make up the key.
     *
     * <p>One, deliberately, and this is the constant most worth revisiting
     * against real statements. Two keeps {@code amazon pay} apart from
     * {@code amazon} — but it also splits {@code SWIGGY} from
     * {@code SWIGGY BANGALORE}, which is the same shop and now costs two
     * decisions instead of one, forever.
     *
     * <p>The two failure modes are not equally bad. Merging too much is undone
     * by writing one rule, and rules already beat history. Splitting too much
     * quietly doubles the reviewing this whole feature exists to avoid, and
     * nothing in the interface tells you it is happening.
     */
    private static final int MAX_TOKENS = 1;

    /** Long enough for any merchant name; short enough to keep the column small. */
    private static final int MAX_LENGTH = 40;

    /** Below this a token is an initial or an abbreviation, not a name. */
    private static final int MIN_TOKEN_LENGTH = 3;

    /** At this length a mostly-numeric token is a reference, not a word. */
    private static final int REFERENCE_LENGTH = 4;

    /**
     * Tokens that appear next to a merchant without ever being one.
     *
     * <p>The aggregators are here for a reason worth stating: a wallet is not a
     * merchant. Without them {@code PAYTM-SWIGGY-123} and {@code PAYTM-UBER-456}
     * both reduce to "paytm" and two unrelated shops become one group. A genuine
     * wallet top-up reduces to nothing instead and lands in review, which is the
     * honest answer.
     */
    private static final Set<String> NOISE = Set.of(
            // Channels and instruments.
            "upi", "neft", "imps", "rtgs", "ach", "nach", "pos", "atm", "ecom",
            "inb", "tpt", "bil", "mmt", "chq", "ref", "txn", "trans", "transaction",
            "card", "mandate", "ift", "edc", "vps", "nfs", "emi", "ecs",
            // Aggregators and wallets.
            "paytm", "phonepe", "gpay", "googlepay", "razorpay", "billdesk",
            "ccavenue", "payu", "bharatpe", "instamojo",
            // Words a bank adds around the name.
            "debit", "credit", "payment", "purchase", "transfer", "trf", "txfr",
            "ind", "india", "ltd", "pvt", "the", "and", "for", "from", "via",
            // Months, so a salary line is the same merchant every month rather
            // than twelve.
            "jan", "feb", "mar", "apr", "may", "jun",
            "jul", "aug", "sep", "oct", "nov", "dec");

    private MerchantKey() {
    }

    /**
     * The merchant in a description, or null when there is nothing left.
     *
     * <p>Null is a real answer, not a failure: a row that is only a reference
     * number has no merchant to group by and belongs in review rather than in a
     * group named after a channel prefix.
     */
    static String of(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }

        // The handle goes before tokenising, not after. Splitting first would
        // turn "@ybl" into the token "ybl", which survives every later test and
        // would become the merchant for every UPI payment on the statement.
        String withoutHandle = HANDLE.matcher(description).replaceAll(" ");

        List<String> tokens = List.of(
                SEPARATOR.split(withoutHandle.toLowerCase(Locale.ROOT)));

        StringBuilder key = new StringBuilder();
        int taken = 0;

        for (String token : tokens) {
            if (taken == MAX_TOKENS) {
                break;
            }
            if (!isName(token)) {
                continue;
            }
            if (key.length() > 0) {
                key.append(' ');
            }
            key.append(token);
            taken++;
        }

        if (key.length() == 0) {
            return null;
        }
        return key.length() > MAX_LENGTH ? key.substring(0, MAX_LENGTH) : key.toString();
    }

    /** Whether a token could be part of a merchant's name. */
    private static boolean isName(String token) {
        if (token.length() < MIN_TOKEN_LENGTH || NOISE.contains(token)) {
            return false;
        }

        int digits = 0;
        for (int i = 0; i < token.length(); i++) {
            if (Character.isDigit(token.charAt(i))) {
                digits++;
            }
        }

        // All digits is a reference at any length. Mostly digits is a reference
        // once it is long enough that the letters are a bank's prefix rather
        // than a name -- "hdfc0001234", "4123xxxx9876".
        if (digits == token.length()) {
            return false;
        }
        return !(token.length() >= REFERENCE_LENGTH && digits * 2 > token.length());
    }
}
