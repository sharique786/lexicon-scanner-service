package com.db.macs3.ecomms.spectre.model;

/**
 * Distinguishes how the user's lexicon term(s) should be interpreted — the
 * "Request Type" for a scan session.
 *
 * <p>Mirrors the Lexicon Compile Service's own {@code TermType} enum
 * exactly, including its {@link #jsonValue()} display strings
 * ({@code "Natural Language"} / {@code "Regex"}) — the two services are
 * independent (the scanner's {@code REGEX} path never calls the Compile
 * Service at all; see {@link com.db.macs3.ecomms.spectre.service.LexiconScanOrchestrator}),
 * but using identical terminology end-to-end means an analyst who has used
 * one service already knows exactly what the field means in the other, and
 * the same display string can be dropped straight into the downloadable
 * report's "Request Type" column with no translation step.
 *
 * <ul>
 *   <li>{@link #NATURAL_LANGUAGE} — the term(s) use the operator language
 *       (OR, AND, NEAR{n}, wildcards, quoted phrases, etc.) and must be
 *       translated into a Hyperscan-compatible PCRE pattern by the Lexicon
 *       Compile Service before compilation. This is the default.</li>
 *   <li>{@link #REGEX} — the term(s) are ALREADY valid Hyperscan-compatible
 *       PCRE patterns supplied directly by the user. No call to the Lexicon
 *       Compile Service is made; the term is compiled into the Hyperscan
 *       database as-is.</li>
 * </ul>
 */
public enum TermType {

    NATURAL_LANGUAGE("Natural Language"),
    REGEX("Regex");

    private final String jsonValue;

    TermType(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    /** The display/report string for this term type, e.g. {@code "Natural Language"}. */
    public String jsonValue() {
        return jsonValue;
    }

    /**
     * Parses a request-supplied string into a {@link TermType}, defaulting to
     * {@link #NATURAL_LANGUAGE} when the value is missing, blank, or unrecognised.
     * Accepts both the enum constant name ({@code "REGEX"}, from the UI's form
     * field) and the display value ({@code "Regex"}, from a JSON caller that
     * echoes the Compile Service's own wording) — case-insensitive either way.
     *
     * @param value raw string value from the request
     * @return the resolved {@link TermType}, never null
     */
    public static TermType fromString(String value) {
        if (value == null || value.isBlank()) {
            return NATURAL_LANGUAGE;
        }
        String normalized = value.trim();
        for (TermType termType : values()) {
            if (termType.jsonValue.equalsIgnoreCase(normalized) || termType.name().equalsIgnoreCase(normalized)) {
                return termType;
            }
        }
        return NATURAL_LANGUAGE;
    }
}
