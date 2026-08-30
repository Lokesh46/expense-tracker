package com.lokesh_codes.expense_tracker_backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The layout of a real HDFC statement, as extracted from the actual PDF.
 *
 * <p>The fixture is the redacted output of the statement preview run against a
 * genuine file: every letter replaced with x and every digit with 9, in place,
 * so the spacing and the column positions are exactly those of the original and
 * none of its contents are. That is the whole point of it — the geometry is what
 * broke the parser twice, and no PDF written by a test could have reproduced it.
 *
 * <p>What it caught, in order: extraction that collapsed every column gap to a
 * single space, and then a positional reading that found four columns where the
 * header has seven, because a real statement's rows drift by several characters
 * and one of them has the narration touching the reference.
 */
class HdfcStatementLayoutTest {

    private List<String> fixture() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/statements/hdfc-redacted.txt")) {
            assertThat(in).as("the redacted HDFC fixture").isNotNull();
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1));
        }
    }

    @Test
    @DisplayName("every transaction is found, with its amounts in the right columns")
    void parsesTheRealLayout() throws IOException {
        PdfStatementTable.Extracted extracted = PdfStatementTable.toCsv(fixture());

        assertThat(extracted.rows())
                .as("nine dated rows, and neither the summary block nor the page furniture")
                .isEqualTo(9);

        List<String> csv = List.of(extracted.csv().split("\n"));
        assertThat(csv.get(0))
                .as("the header, read from the statement rather than assumed")
                .contains("Date")
                .contains("Narration")
                .contains("Withdrawal Amount")
                .contains("Deposit Amount");

        // HDFC never leaves a money column empty -- the direction that did not
        // happen is written as zero -- so every row keeps all seven columns.
        for (int i = 1; i <= 9; i++) {
            assertThat(CsvSupport.parseLine(csv.get(i)))
                    .as("row %d keeps every column", i)
                    .hasSize(7);
        }
    }

    @Test
    @DisplayName("the money columns land the right way round")
    void moneyColumnsAreNotSwapped() throws IOException {
        List<String> csv = List.of(PdfStatementTable.toCsv(fixture()).csv().split("\n"));

        // Row 1 pays out: withdrawal has the figure, deposit is zero.
        List<String> paidOut = CsvSupport.parseLine(csv.get(1));
        assertThat(paidOut.get(4)).isEqualTo("9,999.99");
        assertThat(paidOut.get(5)).isEqualTo("9.99");

        // Row 7 takes money in, and is the mirror image.
        List<String> paidIn = CsvSupport.parseLine(csv.get(7));
        assertThat(paidIn.get(4)).isEqualTo("9.99");
        assertThat(paidIn.get(5)).isEqualTo("9,99,999.99");
    }

    @Test
    @DisplayName("a wrapped narration is joined to its own row, not the one beside it")
    void wrappedNarrationJoinsTheRightRow() throws IOException {
        List<String> csv = List.of(PdfStatementTable.toCsv(fixture()).csv().split("\n"));

        // The merchant's name sits on the wrapped line above the dated one, and
        // it is the part a filing rule is written against -- losing it would
        // make rules far less useful than they look.
        assertThat(CsvSupport.parseLine(csv.get(1)).get(1))
                .startsWith("XXX-XXXXXX XXXXX-XXXXXX@XXXX-XXXX999XXXX");

        // The seventh transaction has no wrapped line of its own; the line above
        // it is the tail of the sixth. Adopting it would put one transaction's
        // merchant into another's description, where a rule would match it.
        assertThat(CsvSupport.parseLine(csv.get(7)).get(1))
                .as("a leftover fragment from the row above is not adopted")
                .isEqualTo("XXX-XXXXXXXX9999-XXXX99999999");
    }
}
