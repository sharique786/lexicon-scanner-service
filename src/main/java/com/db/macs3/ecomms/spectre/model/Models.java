package com.db.macs3.ecomms.spectre.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Shared domain models for the Lexicon Scanner Service.
 *
 * <h2>Request models</h2>
 * <ul>
 *   <li>{@link ScanApiRequest} — JSON body for the REST API</li>
 * </ul>
 *
 * <h2>Lexicon Compile Service wire models</h2>
 * <ul>
 *   <li>{@link CompileServiceRequest} / {@link CompileServiceRequest.TermInput}</li>
 *   <li>{@link CompileServiceResponse} / {@link CompileServiceResponse.TermResult}</li>
 * </ul>
 *
 * <h2>Internal pipeline models</h2>
 * <ul>
 *   <li>{@link CompiledTerm}   — a term that compiled successfully, ready for Hyperscan</li>
 *   <li>{@link RawMatch}       — raw Hyperscan match (byte-offset based)</li>
 *   <li>{@link MatchHighlight} — a single match region mapped to char indices</li>
 *   <li>{@link TermScanResult} — aggregated result for one lexicon term</li>
 *   <li>{@link ScanResponse}   — the full response returned to the UI / caller</li>
 * </ul>
 */
public final class Models {

    private Models() {}

    // ══════════════════════════════════════════════════════════════════════════
    // API Request
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * JSON body accepted by {@code POST /api/scan/json}.
     * Multipart form data uses {@code POST /api/scan} with individual fields.
     *
     * @param inputMode   how lexicon terms are supplied ({@code TERMS} or {@code CSV})
     * @param termType    how the term(s) should be interpreted ({@code NATURAL_LANGUAGE} or
     *                    {@code REGEX}); defaults to {@code NATURAL_LANGUAGE} when blank —
     *                    see {@link TermType#fromString(String)}
     * @param messageMode how the message is supplied ({@code TEXT} or {@code FILE})
     * @param terms       newline-separated term descriptions (when inputMode=TERMS)
     * @param csvContent  raw CSV text content (when inputMode=CSV, for JSON requests)
     * @param messageText raw message text (when messageMode=TEXT)
     * @param ruleName    optional rule / session name for the compile request
     * @param disclaimerInputMode how disclaimer text is supplied ({@code NONE}, {@code TEXT}, or {@code CSV});
     *                            defaults to {@code NONE} — see {@link com.db.macs3.ecomms.spectre.model.DisclaimerSource}
     * @param disclaimers          newline-separated disclaimer texts (when disclaimerInputMode=TEXT)
     * @param disclaimerCsvContent raw CSV text content for disclaimers (when disclaimerInputMode=CSV, JSON callers only)
     */
    public record ScanApiRequest(
            String inputMode,
            String termType,
            String messageMode,
            String terms,
            String csvContent,
            String messageText,
            String ruleName,
            String disclaimerInputMode,
            String disclaimers,
            String disclaimerCsvContent
    ) {}

    // ══════════════════════════════════════════════════════════════════════════
    // Lexicon Compile Service wire models
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Request body sent to the Lexicon Compile Service.
     *
     * <p>Matches the Compile Service's current single request type,
     * {@code TypedCompileRequest} exactly — including its JSON key names,
     * which is the part an earlier revision of this record got wrong:
     * {@code request_id} is snake_case (Jackson does not fold camelCase
     * {@code requestId} into it automatically), and the term-type field's
     * JSON key is {@code requestType}, not {@code termType} — both are
     * {@code @NotBlank}/{@code @NotNull} on the Compile Service side, so
     * sending the wrong key means the Compile Service never sees a value
     * for either and rejects the call with HTTP 400. See {@code CLAUDE.md}
     * "Current, confirmed contract breaks" for how this was found.
     * {@code riskDriverName} per term has been removed entirely, since
     * {@code TypedCompileRequest.TermInput} no longer carries it.
     *
     * @param requestId       caller-generated UUID, echoed back for end-to-end tracing —
     *                        REQUIRED by the Compile Service (rejects a blank/missing value)
     * @param lexiconRuleName logical name for this compilation session
     * @param termType        always {@code "Natural Language"} for this client — the only
     *                        caller of this record ({@link com.db.macs3.ecomms.spectre.service.LexiconScanOrchestrator})
     *                        exclusively uses it for Natural Language terms; {@code TermType.REGEX}
     *                        terms bypass the Compile Service entirely and are compiled directly.
     *                        Serialised as JSON key {@code requestType} — see class Javadoc
     * @param terms           list of term inputs to compile
     */
    public record CompileServiceRequest(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("lexiconRuleName") String lexiconRuleName,
            @JsonProperty("requestType") String termType,
            @JsonProperty("terms") List<TermInput> terms
    ) {

        /** The only {@code termType} value this client ever sends. */
        public static final String TERM_TYPE_NATURAL_LANGUAGE = "Natural Language";

        /**
         * One term to compile.
         *
         * @param termId          unique identifier, e.g. {@code scanner::1}
         * @param termDescription the raw operator expression, e.g. {@code insider AND trading}
         */
        public record TermInput(
                @JsonProperty("termId") String termId,
                @JsonProperty("termDescription") String termDescription
        ) {}
    }

    /**
     * Full response from the Lexicon Compile Service.
     *
     * <p>Annotated {@code ignoreUnknown = true} because the Compile Service's
     * response has grown new fields over time (e.g. {@code hyperscanVersion},
     * {@code hyperscanExpressionId} per term) — this client only needs the
     * fields declared below and must not break when the upstream service adds more.
     *
     * @param requestId       echoed from the request — see {@link CompileServiceRequest}
     * @param lexiconRuleName rule name echoed from the request
     * @param totalTerms      total number of terms submitted
     * @param passCount       number that compiled successfully
     * @param failedCount     number that failed compilation or translation
     * @param hasFailures     convenience flag — true when failedCount &gt; 0
     * @param results         per-term compilation outcomes
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompileServiceResponse(
            String requestId,
            String lexiconRuleName,
            int totalTerms,
            int passCount,
            int failedCount,
            boolean hasFailures,
            List<TermResult> results
    ) {

        /**
         * Compilation outcome for one term — matches the Compile Service's
         * current {@code TermCompilationResult} shape field-for-field
         * (verified against its actual source, not just its own Javadoc
         * claims — see {@code CLAUDE.md} "Current, confirmed contract
         * breaks" for the earlier version of this record, which had drifted
         * on three separate fields at once without any of them failing loudly).
         *
         * <p><b>{@code regexPattern} and {@code exclusionRegex}</b> — renamed
         * from {@code translatedPattern}/{@code exclusionPattern} by Compile
         * Service commit {@code a0717b3}. Both are lists, not single strings:
         * one entry for a term simple enough to compile as a single pattern;
         * several when the term used {@code NEAR{n}}/{@code FOLLOWEDBY{n}}
         * and the Compile Service decomposed it into independent leaf
         * patterns — unconditionally now, not only as a "pattern too large"
         * fallback (see {@code TermCompilationResult} class Javadoc,
         * compile-service repo). This project no longer scans these leaves
         * with Hyperscan directly; see {@code resolvedPatterns} below and
         * {@link com.db.macs3.ecomms.spectre.model.ResolvedPatternTree}.
         *
         * <p><b>{@code hyperscanFlags} no longer exists on this response</b>
         * — the Compile Service made it {@code @JsonIgnore} (commit
         * {@code 768a7ce}); it is not part of the JSON any more for any
         * endpoint. This record no longer declares it. (It never mattered
         * for scanning purposes to begin with, once {@code resolvedPatterns}-based
         * Java-regex evaluation replaced Hyperscan scanning for Natural
         * Language terms — see {@code LexiconScanOrchestrator}. It remains
         * relevant only for {@code TermType.REGEX} terms, which never call
         * the Compile Service and use {@code HyperscanScanService.HS_FLAGS_REGEX_DEFAULT} instead.)
         *
         * <p><b>{@code resolvedPatterns} — new field, the reason AND NOT and
         * proximity terms now scan correctly at all.</b> Present (non-blank)
         * for every PASS Natural-Language term: the term (or, for AND NOT,
         * both sides) rendered with literal {@code NEAR{n}}/{@code FOLLOWEDBY{n}}/
         * {@code AND NOT} keyword text standing in for the regex gap between
         * {@code regexPattern}/{@code exclusionRegex} leaves — e.g.
         * {@code "bash FOLLOWEDBY{30} (?:fuck|fck)"} or
         * {@code "insider AND NOT (fraud)"}. {@link ResolvedPatternTree#build}
         * parses this together with {@code regexPattern} into an evaluable
         * tree; {@link com.db.macs3.ecomms.spectre.service.ResolvedPatternMatcher}
         * evaluates it against real message text — the only way to genuinely
         * verify NEAR/FOLLOWEDBY distance/order or an AND NOT condition,
         * since none of that survives in {@code regexPattern}/{@code exclusionRegex}
         * alone (those leaves are pure, gap-free, boolean-AND-only fragments).
         *
         * @param termId                 echoed from the request
         * @param termDescription        echoed from the request
         * @param compilationStatus      {@code "PASS"} or {@code "FAILED"}
         * @param regexPattern           the REQUIRED side's Hyperscan-valid pattern leaf(s);
         *                               null when FAILED, otherwise always non-empty
         * @param requiresExclusionCheck true for AND NOT terms — {@code exclusionRegex}
         *                               must also be checked; see class Javadoc
         * @param exclusionRegex         independently Hyperscan-valid pattern leaf(s) that must NOT
         *                               match the same message; non-null iff requiresExclusionCheck
         * @param resolvedPatterns       literal NEAR/FOLLOWEDBY/AND-NOT structure as text — see class
         *                               Javadoc; non-null for every PASS Natural-Language term
         * @param errorLog               Hyperscan compile error (null when PASS)
         * @param translationError       translator error (null when PASS)
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record TermResult(
                String termId,
                String termDescription,
                String compilationStatus,
                List<String> regexPattern,
                boolean requiresExclusionCheck,
                List<String> exclusionRegex,
                String resolvedPatterns,
                String errorLog,
                String translationError
        ) {
            /** Returns true when this term compiled successfully and has a usable pattern. */
            public boolean isPass() {
                return "PASS".equalsIgnoreCase(compilationStatus);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Internal pipeline models
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * The result of {@link com.db.macs3.ecomms.spectre.service.HtmlStrippingService#strip}:
     * the original text, the HTML-stripped/whitespace-collapsed text that
     * Hyperscan actually scans, and the index mapping between them.
     *
     * <p>{@code strippedToOriginalIndex[j]} gives the character index in
     * {@link #originalText} that produced the character at index {@code j}
     * in {@link #strippedText}. See {@link #mapSpanToOriginal} for converting
     * a matched span from stripped-text coordinates to original-text coordinates.
     *
     * @param originalText            the message exactly as supplied by the user
     * @param strippedText            HTML-stripped, whitespace-collapsed text (what Hyperscan scans)
     * @param strippedToOriginalIndex per-character offset map, length == strippedText.length()
     */
    public record StrippedMessage(
            String originalText,
            String strippedText,
            int[] strippedToOriginalIndex
    ) {

        /**
         * Maps a {@code [strippedStart, strippedEnd)} span (as reported by
         * Hyperscan against {@link #strippedText}) to the corresponding
         * {@code [originalStart, originalEnd)} span in {@link #originalText}.
         *
         * <p>Convention: {@code originalEnd} is exclusive — one past the
         * original index of the span's last stripped character — matching
         * ordinary Java substring semantics.
         *
         * @param strippedStart start index into {@link #strippedText}, inclusive
         * @param strippedEnd   end index into {@link #strippedText}, exclusive
         * @return a two-element array {@code [originalStart, originalEnd]}
         */
        public int[] mapSpanToOriginal(int strippedStart, int strippedEnd) {
            if (strippedToOriginalIndex.length == 0 || strippedStart >= strippedEnd) {
                return new int[]{0, 0};
            }
            int safeStart = Math.max(0, Math.min(strippedStart, strippedToOriginalIndex.length - 1));
            int safeEnd   = Math.max(safeStart + 1, Math.min(strippedEnd, strippedToOriginalIndex.length));
            int originalStart = strippedToOriginalIndex[safeStart];
            int originalEnd   = strippedToOriginalIndex[safeEnd - 1] + 1;
            return new int[]{originalStart, originalEnd};
        }

        /**
         * Records auto-generate {@code equals()}/{@code hashCode()} using
         * {@link Object#equals} per component — for an {@code int[]} field that
         * means REFERENCE equality, which is almost never what's wanted (two
         * separately-computed but content-identical mappings would compare
         * unequal). Overridden here to use {@link java.util.Arrays} content
         * comparison instead, so {@code StrippedMessage} behaves the way every
         * caller (and every test) reasonably expects it to.
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof StrippedMessage that)) return false;
            return java.util.Objects.equals(originalText, that.originalText)
                    && java.util.Objects.equals(strippedText, that.strippedText)
                    && java.util.Arrays.equals(strippedToOriginalIndex, that.strippedToOriginalIndex);
        }

        @Override
        public int hashCode() {
            int result = java.util.Objects.hash(originalText, strippedText);
            result = 31 * result + java.util.Arrays.hashCode(strippedToOriginalIndex);
            return result;
        }

        @Override
        public String toString() {
            return "StrippedMessage{originalLen=" + (originalText != null ? originalText.length() : 0)
                    + ", strippedLen=" + strippedText.length() + "}";
        }
    }

    /**
     * Result of validating one raw regex pattern directly against Hyperscan
     * (used for {@code TermType.REGEX} terms, which skip the Compile Service
     * entirely — see {@link com.db.macs3.ecomms.spectre.service.HyperscanScanService#validatePattern}).
     *
     * @param pass         true when Hyperscan accepted the pattern
     * @param errorMessage Hyperscan's compile error text; null when {@code pass} is true
     */
    public record PatternValidationResult(
            boolean pass,
            String errorMessage
    ) {}

    /**
     * A lexicon term that passed Hyperscan compilation and is ready to scan.
     *
     * <h2>{@code TermType.REGEX} only now</h2>
     * <p>Natural Language terms no longer go through this record or through
     * Hyperscan at all — every PASS Natural Language term's AND NOT/proximity
     * condition is now verified directly against real message text via
     * {@link ResolvedPatternTree} +
     * {@link com.db.macs3.ecomms.spectre.service.ResolvedPatternMatcher} (see
     * {@link com.db.macs3.ecomms.spectre.service.LexiconScanOrchestrator}
     * class Javadoc). This eliminates the native Hyperscan
     * {@code HS_FLAG_COMBINATION} AND NOT design this record previously
     * supported ({@code exclusionPatterns}/{@code requiresExclusionCheck}/a
     * {@code needsCombination()}-style branch) — confirmed unreliable by
     * Hyperscan's own documented eager, progressive combination evaluation
     * (a formula mixing a positive requirement with a negation can fire
     * before the negated pattern has been reached by the scan at all), the
     * same bug the Compile Service found and fixed in its own
     * {@code HyperscanCombinationHandler}.
     *
     * <p>The only remaining caller ({@code LexiconScanOrchestrator.resolveRegexTerms})
     * always constructs this with exactly one entry in {@link #requiredPatterns},
     * {@link #exclusionPatterns} null, and {@link #requiresExclusionCheck} false —
     * a {@code TermType.REGEX} term is always a single, raw, caller-supplied
     * PCRE pattern with no operator-language syntax, so it never has an AND
     * NOT or NEAR/FOLLOWEDBY structure to represent. {@link #exclusionPatterns}/
     * {@link #requiresExclusionCheck} are kept on the record only so existing
     * call sites/tests don't need to change shape; {@code HyperscanScanService}
     * no longer reads them.
     *
     * @param termId                 unique identifier
     * @param termDescription        original lexicon expression (the raw regex, for REGEX terms)
     * @param requiredPatterns       always exactly one entry for the only remaining caller — see above
     * @param exclusionPatterns      always null for the only remaining caller — see above
     * @param hsFlags                bitmask of Hyperscan expression flags
     * @param requiresExclusionCheck always false for the only remaining caller — see above
     * @param expressionIndex        this term's own reportable Hyperscan expression id
     */
    public record CompiledTerm(
            String termId,
            String termDescription,
            List<String> requiredPatterns,
            List<String> exclusionPatterns,
            int hsFlags,
            boolean requiresExclusionCheck,
            int expressionIndex
    ) {}

    /**
     * A raw match returned by Hyperscan, using byte offsets into the UTF-8 input.
     *
     * <h2>{@code fromCombination} is always {@code false} now</h2>
     * <p>Native Hyperscan {@code COMBINATION} expressions are no longer built
     * anywhere in this project (see {@link CompiledTerm} class Javadoc) — the
     * only remaining Hyperscan caller ({@code TermType.REGEX} terms) always
     * compiles a single plain expression per term. This field is kept only
     * for wire-shape stability with any existing serialized test fixtures;
     * a future cleanup may remove it once nothing sets it {@code true}.
     *
     * @param startByteOffset start of the matched region (bytes into UTF-8 message)
     * @param endByteOffset   end of the matched region (exclusive, bytes)
     * @param expressionIndex index of the matching expression in the compiled database —
     *                        always a {@code CompiledTerm.expressionIndex()}
     * @param fromCombination always {@code false} — see above
     */
    public record RawMatch(
            long startByteOffset,
            long endByteOffset,
            int expressionIndex,
            boolean fromCombination
    ) {}

    /**
     * A single match region mapped to Java {@code String} character indices.
     *
     * @param startCharIndex  start of the match in the message (Java char index)
     * @param endCharIndex    end of the match in the message (exclusive, Java char index)
     * @param matchedText     the extracted substring from the message
     * @param inDisclaimer    true when this match falls entirely within a detected
     *                        disclaimer region — see {@link DisclaimerSpan} and
     *                        {@link com.db.macs3.ecomms.spectre.service.DisclaimerDetectionService}.
     *                        A disclaimer match is still reported (and shown on the UI
     *                        with a "not an alert — found in disclaimer" note) but does
     *                        NOT count toward {@link TermScanResult#hasMatches()} /
     *                        alerting, matching the requirement that boilerplate
     *                        disclaimer text (e.g. the word "confidential" appearing
     *                        in a standard footer) must not trigger a false alert.
     */
    public record MatchHighlight(
            int startCharIndex,
            int endCharIndex,
            String matchedText,
            boolean inDisclaimer
    ) {
        /** @return true when this match should count toward alerting (i.e. NOT disclaimer-only). */
        public boolean countsTowardAlert() {
            return !inDisclaimer;
        }
    }

    /**
     * One occurrence of a user-supplied disclaimer text found within a message,
     * in ORIGINAL-text character coordinates (same convention as {@link MatchHighlight}).
     *
     * @param startCharIndex start of the disclaimer occurrence (inclusive)
     * @param endCharIndex   end of the disclaimer occurrence (exclusive)
     * @param disclaimerText the disclaimer text that was matched (as supplied by the user)
     */
    public record DisclaimerSpan(
            int startCharIndex,
            int endCharIndex,
            String disclaimerText
    ) {}

    /**
     * All scan results aggregated for one lexicon term, against ONE message.
     *
     * @param termId                 unique term identifier
     * @param termDescription        original lexicon expression
     * @param translatedPattern      the REQUIRED side's Hyperscan-valid pattern(s) used for
     *                               scanning — one entry normally, several when decomposed;
     *                               matches the Compile Service's own list shape (see
     *                               {@link CompileServiceResponse.TermResult})
     * @param compilationStatus      {@code "PASS"}, {@code "FAILED"}, or {@code "SKIPPED"}
     * @param requiresExclusionCheck true for AND NOT terms — an exclusion pattern
     *                               was independently scanned for; see {@code excludedByAndNot}
     * @param matches                match regions found for the REQUIRED pattern in this
     *                               message (each individually flagged {@link MatchHighlight#inDisclaimer()})
     * @param hasMatches             true when at least one ALERT-worthy match exists —
     *                               false when there are no required-pattern matches, when every
     *                               match is disclaimer-only, OR when {@code excludedByAndNot} is true
     *                               (the exclusion pattern negates the whole term regardless of matches)
     * @param hasDisclaimerOnlyMatches true when every match found for this term/message fell
     *                               within a disclaimer — informational: nothing to alert on,
     *                               but not silently invisible either
     * @param excludedByAndNot       true when this term's exclusion pattern (see
     *                               {@code requiresExclusionCheck}) was found ANYWHERE in this
     *                               message — required-pattern matches are still listed in
     *                               {@code matches} for visibility, but never count toward alerting
     * @param compilationError       error message when compilationStatus is FAILED
     * @param exclusionNote          informational note explaining an AND NOT exclusion when
     *                               {@code excludedByAndNot} is true
     */
    public record TermScanResult(
            String termId,
            String termDescription,
            List<String> translatedPattern,
            String compilationStatus,
            boolean requiresExclusionCheck,
            List<MatchHighlight> matches,
            boolean hasMatches,
            boolean hasDisclaimerOnlyMatches,
            boolean excludedByAndNot,
            String compilationError,
            String exclusionNote
    ) {}

    /**
     * Scan results for exactly one message — one message may be scanned
     * against many terms; this bundles all of them together with the
     * message's own text/highlighting.
     *
     * @param messageId              identifies which message this is — the uploaded
     *                               file's name, or {@code "Pasted Message"} /
     *                               {@code "Message N"} for text-area input
     * @param plainMessage           original message text (for the UI text panel)
     * @param htmlHighlightedMessage HTML with {@code <mark>} tags for matched regions —
     *                               disclaimer-only matches get a visually distinct
     *                               {@code lex-match-disclaimer} CSS class instead of
     *                               {@code lex-match}, so the UI can render them muted
     *                               with a "not an alert" note rather than as a live alert
     * @param disclaimerSpans        disclaimer occurrences detected in this message
     * @param termResults            per-term scan results for this message
     * @param matchedTerms           number of terms with at least one ALERT-worthy match
     *                               (i.e. {@link TermScanResult#hasMatches()} true) in this message
     * @param hasMatches             true when any term has an alert-worthy match in this message
     */
    public record MessageScanResult(
            String messageId,
            String plainMessage,
            String htmlHighlightedMessage,
            List<DisclaimerSpan> disclaimerSpans,
            List<TermScanResult> termResults,
            int matchedTerms,
            boolean hasMatches
    ) {}

    /**
     * Complete scan response returned to the UI and REST callers — covers
     * one or more messages scanned against the same term set.
     *
     * @param scanId               UUID of this scan session
     * @param scanDurationMs       wall-clock time for the full pipeline
     * @param termType             {@code "Natural Language"} or {@code "Regex"} — the request
     *                             type applied to every term in this scan (see {@link TermType})
     * @param totalMessages        number of messages scanned
     * @param totalTerms           number of terms submitted
     * @param totalDisclaimers     number of disclaimer texts supplied (0 if none)
     * @param matchedTerms         number of (message, term) pairs with at least one alert-worthy match,
     *                             summed across all messages
     * @param failedTerms          number of terms that failed compilation (term-level, not per-message)
     * @param hasMatches           true when any message has an alert-worthy match
     * @param messageResults       one entry per scanned message
     * @param warnings             non-fatal informational messages
     * @param status               {@code "SUCCESS"} or {@code "ERROR"}
     * @param errorMessage         populated only when status is {@code "ERROR"}
     */
    public record ScanResponse(
            String scanId,
            long scanDurationMs,
            String termType,
            int totalMessages,
            int totalTerms,
            int totalDisclaimers,
            int matchedTerms,
            int failedTerms,
            boolean hasMatches,
            List<MessageScanResult> messageResults,
            List<String> warnings,
            String status,
            String errorMessage
    ) {

        /** Convenience factory for a successful scan response. */
        public static ScanResponse success(
                String scanId, long durationMs, String termType,
                int totalTerms, int totalDisclaimers, int failedTerms,
                List<MessageScanResult> messageResults, List<String> warnings) {
            int matchedTerms = messageResults.stream().mapToInt(MessageScanResult::matchedTerms).sum();
            boolean hasMatches = messageResults.stream().anyMatch(MessageScanResult::hasMatches);
            return new ScanResponse(
                    scanId, durationMs, termType,
                    messageResults.size(), totalTerms, totalDisclaimers,
                    matchedTerms, failedTerms, hasMatches,
                    messageResults, warnings, "SUCCESS", null
            );
        }

        /** Convenience factory for an error response. */
        public static ScanResponse error(String scanId, String message) {
            return new ScanResponse(
                    scanId, 0, null, 0, 0, 0, 0, 0, false,
                    List.of(), List.of(), "ERROR", message
            );
        }
    }
}
