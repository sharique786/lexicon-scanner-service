package com.db.macs3.ecomms.spectre.model;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Represents how the caller supplied disclaimer text, mirroring
 * {@link TermSource}'s design exactly (typed text vs. an uploaded CSV file).
 * Providing disclaimers is entirely optional — {@link None} is the default
 * when the user has not enabled the disclaimer panel.
 *
 * <p>Disclaimer CSVs use the SAME two-column format as lexicon term CSVs
 * ({@code term_id, term_description} — here read simply as "id, disclaimer
 * text"), so {@link com.db.macs3.ecomms.spectre.service.CsvParserService} is
 * reused unchanged rather than duplicated for a second, near-identical format.
 */
public sealed interface DisclaimerSource {

    /** No disclaimer text supplied — disclaimer detection is skipped entirely. */
    record None() implements DisclaimerSource {}

    /** One or more disclaimer texts supplied as typed/pasted text (newline-separated in the UI). */
    record TextDisclaimers(List<String> disclaimerTexts) implements DisclaimerSource {}

    /** A CSV file of disclaimer texts, parsed via {@link com.db.macs3.ecomms.spectre.service.CsvParserService}. */
    record CsvFile(MultipartFile file) implements DisclaimerSource {}
}
