package com.lokesh_codes.expense_tracker_backend.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Extracts a PDF's text with its horizontal layout rebuilt.
 *
 * <p>PDFBox returns the characters of a line in reading order and separates them
 * with single spaces. That is correct for prose and useless for a table: a
 * statement's columns are drawn as gaps, and a line that arrives as
 * {@code "Date Narration Chq. / Ref No. Value Date Withdrawal Amount"} has had
 * the only thing that distinguished one column from the next collapsed away.
 *
 * <p>This is what {@code pdftotext -layout} does and PDFBox has no equivalent
 * for. Each fragment is placed at the character position its x-coordinate
 * implies, so the gaps come back and a fixed-width table can be read off the
 * result.
 *
 * <p>The character width is measured per line rather than assumed. A statement
 * mixes font sizes — a heading, a table, a footnote — and a width taken from the
 * document as a whole would stretch some lines and squash others, which for a
 * table is the same as losing the columns again.
 */
final class PdfLayoutStripper extends PDFTextStripper {

    /** A fragment of a line, and where across the page it starts. */
    private record Fragment(float x, float width, String text) {
    }

    private final List<Fragment> currentLine = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();

    PdfLayoutStripper() throws IOException {
        // Without this the fragments arrive in the order the PDF happens to draw
        // them, which for a table is close to arbitrary.
        setSortByPosition(true);
    }

    /** The document's lines, with the horizontal spacing restored. */
    List<String> lines() {
        return lines;
    }

    @Override
    protected void writeString(String text, List<TextPosition> positions) {
        if (positions.isEmpty() || text.isBlank()) {
            return;
        }
        TextPosition first = positions.get(0);

        float width = 0;
        for (TextPosition position : positions) {
            width += position.getWidthDirAdj();
        }
        currentLine.add(new Fragment(first.getXDirAdj(), width, text));
    }

    @Override
    protected void writeLineSeparator() {
        lines.add(layOutCurrentLine());
        currentLine.clear();
    }

    @Override
    protected void endDocument(org.apache.pdfbox.pdmodel.PDDocument document) {
        // The last line of a page arrives without a separator after it.
        if (!currentLine.isEmpty()) {
            lines.add(layOutCurrentLine());
            currentLine.clear();
        }
    }

    /**
     * Places each fragment at the column its x-coordinate implies.
     *
     * <p>The unit is this line's own average character width — total width over
     * total characters — which is the closest thing to "how wide is a character
     * here" that does not require knowing the font.
     */
    private String layOutCurrentLine() {
        if (currentLine.isEmpty()) {
            return "";
        }

        float totalWidth = 0;
        int totalChars = 0;
        for (Fragment fragment : currentLine) {
            totalWidth += fragment.width();
            totalChars += fragment.text().length();
        }
        if (totalChars == 0 || totalWidth <= 0) {
            return "";
        }
        float charWidth = totalWidth / totalChars;

        List<Fragment> ordered = new ArrayList<>(currentLine);
        ordered.sort(Comparator.comparingDouble(Fragment::x));

        StringBuilder line = new StringBuilder();
        for (Fragment fragment : ordered) {
            int column = Math.round(fragment.x() / charWidth);

            // Never let rounding run two fragments together: a gap of nothing is
            // still a gap, and a single space is the least it can be.
            if (column < line.length()) {
                column = line.length() + (line.isEmpty() ? 0 : 1);
            }
            while (line.length() < column) {
                line.append(' ');
            }
            line.append(fragment.text());
        }
        return line.toString();
    }
}
