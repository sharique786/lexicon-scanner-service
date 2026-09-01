package com.db.macs3.ecomms.spectre.service;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses CSV files uploaded by the user into a list of lexicon term descriptions.
 *
 * <h2>Supported CSV format</h2>
 * <pre>
 * term_id,term_description,risk_driver_name
 * rule1::1,insider AND trading,Market Abuse
 * rule1::2,price OR spread OR manipulation,Market Manipulation
 * </pre>
 *
 * <ul>
 *   <li>Header row is detected by checking whether the first cell looks like
 *       a label (contains letters only, no operator keywords).</li>
 *   <li>BOM (Byte Order Mark) is stripped from UTF-8 files produced by Excel.</li>
 *   <li>Empty rows and comment rows starting with {@code #} are skipped.</li>
 *   <li>Rows with fewer than 2 columns are skipped (term_description is mandatory).</li>
 * </ul>
 */
@Service
public class CsvParserService {

    private static final Logger log = LoggerFactory.getLogger(CsvParserService.class);

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final int COL_TERM_ID = 0;
    private static final int COL_TERM_DESC = 1;

    /**
     * Parses a CSV {@link MultipartFile} into a list of term description strings.
     *
     * @param file the uploaded CSV file
     * @return list of raw term descriptions (never null, may be empty)
     * @throws CsvParseException if the file cannot be read or parsed
     */
    public List<String> parseTermDescriptions(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            log.warn("CSV parse called with null or empty file");
            return List.of();
        }

        log.info("Parsing CSV file: name={}, size={} bytes", file.getOriginalFilename(), file.getSize());

        try {
            byte[] raw = file.getBytes();
            byte[] stripped = stripBom(raw);
            return parseCsvBytes(stripped);
        } catch (IOException e) {
            throw new CsvParseException("Failed to read CSV file: " + e.getMessage(), e);
        }
    }

    /**
     * Parses raw CSV text (newline-separated rows) into term descriptions.
     * Used when the user pastes CSV content directly as text rather than uploading a file.
     *
     * @param csvContent raw CSV text
     * @return list of term descriptions
     */
    public List<String> parseTermDescriptionsFromText(String csvContent) {
        if (csvContent == null || csvContent.isBlank()) {
            return List.of();
        }
        byte[] bytes = csvContent.getBytes(StandardCharsets.UTF_8);
        return parseCsvBytes(bytes);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<String> parseCsvBytes(byte[] bytes) {
        List<String> descriptions = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
             CSVReader csv = new CSVReader(reader)) {

            String[] row;
            boolean headerSkipped = false;

            while ((row = csv.readNext()) != null) {

                // Skip empty rows and comment rows
                if (row.length == 0 || isBlankRow(row) || isCommentRow(row)) {
                    continue;
                }

                // Skip header row: first cell is "term_id" or "id" (case-insensitive)
                if (!headerSkipped && isHeaderRow(row)) {
                    log.debug("Skipping CSV header row");
                    headerSkipped = true;
                    continue;
                }
                headerSkipped = true;  // treat any non-header first row as data

                // Need at least 2 columns (termId + termDescription)
                if (row.length < 2) {
                    log.debug("Skipping short row with {} column(s): {}", row.length, row[0]);
                    continue;
                }

                String termDesc = row[COL_TERM_DESC].strip();
                if (!termDesc.isBlank()) {
                    descriptions.add(termDesc);
                    log.trace("Parsed term: id={}, desc={}", row[COL_TERM_ID].strip(), termDesc);
                }
            }

        } catch (IOException | CsvValidationException e) {
            throw new CsvParseException("CSV parsing failed: " + e.getMessage(), e);
        }

        log.info("CSV parsing complete: {} term descriptions extracted", descriptions.size());
        return descriptions;
    }

    /**
     * Strips the UTF-8 BOM ({@code EF BB BF}) from the start of the byte array
     * if present. Excel exports frequently include a BOM that causes the first
     * cell value to be garbled.
     */
    private byte[] stripBom(byte[] bytes) {
        if (bytes.length >= 3
                && bytes[0] == UTF8_BOM[0]
                && bytes[1] == UTF8_BOM[1]
                && bytes[2] == UTF8_BOM[2]) {
            log.debug("Stripping UTF-8 BOM from CSV file");
            byte[] stripped = new byte[bytes.length - 3];
            System.arraycopy(bytes, 3, stripped, 0, stripped.length);
            return stripped;
        }
        return bytes;
    }

    private boolean isBlankRow(String[] row) {
        for (String cell : row) {
            if (cell != null && !cell.isBlank()) return false;
        }
        return true;
    }

    private boolean isCommentRow(String[] row) {
        return row[0] != null && row[0].strip().startsWith("#");
    }

    /**
     * Heuristic: a header row has a first cell that matches known column name labels
     * and does NOT look like a term ID (which contains {@code ::}).
     */
    private boolean isHeaderRow(String[] row) {
        String first = row[0].strip().toLowerCase();
        return (first.equals("term_id") || first.equals("id") || first.equals("termid"))
                && !first.contains("::");
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    /** Thrown when a CSV file or text cannot be parsed. */
    public static final class CsvParseException extends RuntimeException {

        public CsvParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
