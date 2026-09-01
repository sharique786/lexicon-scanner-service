package com.db.macs3.ecomms.spectre.model;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Represents how the caller supplied lexicon term(s), independent of
 * {@link TermType}. The two dimensions combine as follows in
 * {@code LexiconScanOrchestrator}:
 *
 * <table border="1">
 *   <tr><th>TermType</th><th>TermSource</th><th>Behaviour</th></tr>
 *   <tr><td>NATURAL_LANGUAGE</td><td>TextTerms</td>
 *       <td>Terms sent to Compile Service {@code POST /api/lexicon/compile}</td></tr>
 *   <tr><td>NATURAL_LANGUAGE</td><td>CsvFile</td>
 *       <td>Raw CSV file forwarded as-is to Compile Service {@code POST /api/lexicon/compile/csv}</td></tr>
 *   <tr><td>REGEX</td><td>TextTerms</td>
 *       <td>Terms compiled directly into Hyperscan, no Compile Service call</td></tr>
 *   <tr><td>REGEX</td><td>CsvFile</td>
 *       <td>CSV parsed locally (term_description column) into raw regex
 *           patterns, compiled directly into Hyperscan, no Compile Service call</td></tr>
 * </table>
 */
public sealed interface TermSource {

    /** One or more term descriptions supplied as typed/pasted text (newline-separated in the UI). */
    record TextTerms(List<String> descriptions) implements TermSource {}

    /** A CSV file upload, retained unparsed so it can be forwarded as-is when required. */
    record CsvFile(MultipartFile file) implements TermSource {}
}
