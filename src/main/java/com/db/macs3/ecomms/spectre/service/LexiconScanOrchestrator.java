package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.client.LexiconCompileClient;
import com.db.macs3.ecomms.spectre.model.DisclaimerSource;
import com.db.macs3.ecomms.spectre.model.Models.CompiledTerm;
import com.db.macs3.ecomms.spectre.model.Models.CompileServiceResponse;
import com.db.macs3.ecomms.spectre.model.Models.DisclaimerSpan;
import com.db.macs3.ecomms.spectre.model.Models.MatchHighlight;
import com.db.macs3.ecomms.spectre.model.Models.MessageScanResult;
import com.db.macs3.ecomms.spectre.model.Models.PatternValidationResult;
import com.db.macs3.ecomms.spectre.model.Models.RawMatch;
import com.db.macs3.ecomms.spectre.model.Models.ScanResponse;
import com.db.macs3.ecomms.spectre.model.Models.StrippedMessage;
import com.db.macs3.ecomms.spectre.model.Models.TermScanResult;
import com.db.macs3.ecomms.spectre.model.MessageSource;
import com.db.macs3.ecomms.spectre.model.ResolvedPatternTree;
import com.db.macs3.ecomms.spectre.model.TermSource;
import com.db.macs3.ecomms.spectre.model.TermType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates the full Lexicon Scanner pipeline for one or more messages,
 * scanned against one shared term set and one shared disclaimer set.
 *
 * <ol>
 *   <li>Resolves lexicon term(s) ONCE — see {@link #resolveNaturalLanguageTerms} /
 *       {@link #resolveRegexTerms}. The two term types now use genuinely
 *       different scanning mechanisms — see "Two scanning paths" below.</li>
 *   <li>Resolves disclaimer text(s) ONCE from {@link DisclaimerSource} — optional;
 *       {@link DisclaimerSource.None} skips disclaimer detection entirely.</li>
 *   <li>Resolves the message(s) to scan from {@link MessageSource} — either the
 *       single pasted-text message, or one message per uploaded file.</li>
 *   <li>For EACH message independently: strips HTML
 *       ({@link HtmlStrippingService}), detects disclaimer occurrences
 *       ({@link DisclaimerDetectionService}), evaluates every term, maps
 *       matches back to original-text coordinates, flags each match
 *       {@link MatchHighlight#inDisclaimer()}, and builds one
 *       {@link MessageScanResult}.</li>
 *   <li>Aggregates every message's results into one {@link ScanResponse}.</li>
 * </ol>
 *
 * <h2>Two scanning paths — Natural Language vs. Regex</h2>
 * <p><b>Natural Language terms never touch Hyperscan.</b> Every PASS term's
 * {@code resolvedPatterns} (the Compile Service's literal NEAR/FOLLOWEDBY/
 * AND-NOT keyword text — see {@code Models.CompileServiceResponse.TermResult}
 * class Javadoc) is parsed into a {@link ResolvedPatternTree} and evaluated
 * directly against the message's HTML-stripped text by
 * {@link ResolvedPatternMatcher} — see {@link #resolveNaturalLanguageTerms} /
 * {@link #scanOneMessageNaturalLanguage}. This replaces an earlier design
 * that compiled every term's {@code regexPattern}/{@code exclusionRegex}
 * leaves into a shared Hyperscan database, using native
 * {@code HS_FLAG_COMBINATION} for AND NOT and pure boolean AND (no distance
 * check at all) for decomposed NEAR/FOLLOWEDBY terms. That design is gone
 * for two independent reasons:
 * <ul>
 *   <li>Hyperscan's own documentation confirms {@code HS_FLAG_COMBINATION}
 *       evaluates eagerly and progressively — a formula mixing a positive
 *       requirement with a negation (the old AND NOT shape) can fire before
 *       the negated pattern has been reached by the scan at all. The Compile
 *       Service found and fixed this exact bug in its own
 *       {@code HyperscanCombinationHandler}; this project's Hyperscan-based
 *       AND NOT had the identical bug and is now fixed the same way — by not
 *       using native {@code COMBINATION} for it at all.</li>
 *   <li>A decomposed NEAR/FOLLOWEDBY term's leaves, combined with plain
 *       boolean AND, can never verify the actual configured word distance —
 *       {@code resolvedPatterns} plus {@link ResolvedPatternMatcher} is the
 *       only source of that information; Hyperscan cannot report it.</li>
 * </ul>
 * <p><b>Regex-type terms are unaffected</b> — they bypass the Compile
 * Service entirely, are always a single raw caller-supplied pattern with no
 * operator-language syntax (so never AND NOT, never NEAR/FOLLOWEDBY), and
 * still scan via {@link HyperscanScanService} exactly as before — see
 * {@link #resolveRegexTerms} / {@link #scanOneMessageRegex}.
 *
 * <h2>Disclaimer exclusion from alerting</h2>
 * <p>A match found ONLY within a detected disclaimer region does not count
 * toward {@link TermScanResult#hasMatches()} (see
 * {@link MatchHighlight#countsTowardAlert()}) — the requirement that
 * boilerplate disclaimer text (e.g. "confidential" in a standard footer)
 * must never trigger a false alert, even when the same word also appears
 * meaningfully elsewhere in the same message. The match is still recorded
 * and shown on the UI with a "not an alert" note, rather than being
 * silently discarded. Applies identically to both scanning paths.
 *
 * <h2>Preserving the "excluded vs. never matched" distinction for AND NOT</h2>
 * <p>{@link ResolvedPatternTree.AndNot} is evaluated by checking the
 * required side and the excluded side INDEPENDENTLY (not via a single
 * opaque call that just returns nothing when excluded) — see
 * {@link #buildNaturalLanguageTermResult}. This preserves the same
 * analyst-facing distinction the old design provided:
 * {@link TermScanResult#excludedByAndNot()} true means the required
 * condition genuinely matched but was suppressed by the exclusion (still
 * worth an analyst seeing, just not alerting on), which is different from
 * the required condition never matching at all.
 */
@Service
public class LexiconScanOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(LexiconScanOrchestrator.class);

    private static final String EXCLUSION_NOTE_TEMPLATE =
            "AND NOT: excluded — the exclusion condition was also found in this message, "
            + "so this term does not count as an alert even though its required pattern matched.";

    private static final String SINGLE_TEXT_MESSAGE_ID = "Pasted Message";

    private final LexiconCompileClient compileClient;
    private final HyperscanScanService scanService;
    private final ResolvedPatternMatcher resolvedPatternMatcher;
    private final MatchHighlightService highlightService;
    private final HtmlStrippingService htmlStrippingService;
    private final CsvParserService csvParserService;
    private final DisclaimerDetectionService disclaimerDetectionService;

    public LexiconScanOrchestrator(LexiconCompileClient compileClient,
                                    HyperscanScanService scanService,
                                    ResolvedPatternMatcher resolvedPatternMatcher,
                                    MatchHighlightService highlightService,
                                    HtmlStrippingService htmlStrippingService,
                                    CsvParserService csvParserService,
                                    DisclaimerDetectionService disclaimerDetectionService) {
        this.compileClient = compileClient;
        this.scanService = scanService;
        this.resolvedPatternMatcher = resolvedPatternMatcher;
        this.highlightService = highlightService;
        this.htmlStrippingService = htmlStrippingService;
        this.csvParserService = csvParserService;
        this.disclaimerDetectionService = disclaimerDetectionService;
    }

    /**
     * Runs the full scan pipeline for the given lexicon term source, message
     * source, and (optional) disclaimer source.
     *
     * @param termType         whether the term(s) are Natural Language (translated via
     *                         the Compile Service) or already-valid Regex (compiled directly)
     * @param termSource       the term(s) themselves — typed text or an uploaded CSV file
     * @param messageSource    the message(s) to scan — pasted text, or one or more uploaded files
     * @param disclaimerSource disclaimer text(s) to exclude from alerting — optional
     * @param ruleName         optional rule name for the compile request
     * @return fully populated {@link ScanResponse}, covering every scanned message
     */
    public ScanResponse scan(TermType termType, TermSource termSource, MessageSource messageSource,
                              DisclaimerSource disclaimerSource, String ruleName) {
        String scanId = UUID.randomUUID().toString();
        long startMs = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();

        try {
            List<MessageSource.NamedMessageText> messages = resolveMessages(messageSource);
            List<String> disclaimerTexts = resolveDisclaimerTexts(disclaimerSource);

            log.info("Starting scan session: scanId={}, termType={}, messageCount={}, disclaimerCount={}",
                    scanId, termType, messages.size(), disclaimerTexts.size());

            int totalTerms;
            int failedTerms;
            List<MessageScanResult> messageResults = new ArrayList<>(messages.size());

            if (termType == TermType.REGEX) {
                RegexResolution resolution = resolveRegexTerms(termSource, warnings);
                totalTerms = resolution.allResults().size();
                failedTerms = (int) resolution.allResults().stream().filter(r -> !r.isPass()).count();
                log.info("scanId={}: {} regex term(s) ready for Hyperscan, of {} submitted",
                        scanId, resolution.compiledTerms().size(), totalTerms);
                for (MessageSource.NamedMessageText message : messages) {
                    messageResults.add(scanOneMessageRegex(message, resolution, disclaimerTexts));
                }
            } else {
                NaturalLanguageResolution resolution = resolveNaturalLanguageTerms(termSource, ruleName, warnings);
                totalTerms = resolution.allResults().size();
                failedTerms = (int) resolution.allResults().stream().filter(r -> !r.isPass()).count();
                long andNotCount = resolution.treesByTermId().values().stream()
                        .filter(t -> t instanceof ResolvedPatternTree.AndNot).count();
                log.info("scanId={}: {} Natural Language term(s) ready ({} with AND NOT), of {} submitted",
                        scanId, resolution.treesByTermId().size(), andNotCount, totalTerms);
                for (MessageSource.NamedMessageText message : messages) {
                    messageResults.add(scanOneMessageNaturalLanguage(message, resolution, disclaimerTexts));
                }
            }

            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("Scan complete: scanId={}, messages={}, elapsed={}ms", scanId, messages.size(), elapsedMs);

            return ScanResponse.success(
                    scanId, elapsedMs, termType.jsonValue(),
                    totalTerms, disclaimerTexts.size(), failedTerms,
                    messageResults, warnings);

        } catch (Exception e) {
            log.error("Scan pipeline failed: scanId={}, error={}", scanId, e.getMessage(), e);
            return ScanResponse.error(scanId, "Scan failed: " + e.getMessage());
        }
    }

    // ── Per-message scanning: Natural Language ──────────────────────────────────

    private MessageScanResult scanOneMessageNaturalLanguage(MessageSource.NamedMessageText message,
                                                              NaturalLanguageResolution resolution,
                                                              List<String> disclaimerTexts) {
        StrippedMessage strippedMessage = htmlStrippingService.strip(message.text());
        log.debug("message='{}': stripped {} chars -> {} chars",
                message.messageId(), message.text().length(), strippedMessage.strippedText().length());

        List<DisclaimerSpan> disclaimerSpans =
                disclaimerDetectionService.detectDisclaimers(strippedMessage, disclaimerTexts);

        List<TermScanResult> termResults = new ArrayList<>(resolution.allResults().size());
        for (CompileServiceResponse.TermResult result : resolution.allResults()) {
            if (!result.isPass()) {
                termResults.add(new TermScanResult(
                        result.termId(), result.termDescription(),
                        null, "FAILED", false,
                        List.of(), false, false, false,
                        buildError(result, TermType.NATURAL_LANGUAGE), null));
                continue;
            }

            String parseError = resolution.parseErrorsByTermId().get(result.termId());
            if (parseError != null) {
                termResults.add(new TermScanResult(
                        result.termId(), result.termDescription(),
                        result.regexPattern(), "FAILED", false,
                        List.of(), false, false, false,
                        "Pattern structure error: " + parseError, null));
                continue;
            }

            ResolvedPatternTree tree = resolution.treesByTermId().get(result.termId());
            termResults.add(buildNaturalLanguageTermResult(result, tree, strippedMessage, disclaimerSpans));
        }

        return assembleMessageResult(message, disclaimerSpans, termResults);
    }

    /**
     * Evaluates one PASS term's {@link ResolvedPatternTree} against this
     * message. For an {@link ResolvedPatternTree.AndNot}, the required and
     * excluded sides are evaluated INDEPENDENTLY — see class Javadoc
     * "Preserving the 'excluded vs. never matched' distinction".
     */
    private TermScanResult buildNaturalLanguageTermResult(CompileServiceResponse.TermResult result,
                                                            ResolvedPatternTree tree,
                                                            StrippedMessage strippedMessage,
                                                            List<DisclaimerSpan> disclaimerSpans) {
        String strippedText = strippedMessage.strippedText();

        if (tree instanceof ResolvedPatternTree.AndNot andNot) {
            List<MatchHighlight> requiredHighlights = disclaimerDetectionService.applyDisclaimerFlags(
                    mapSpans(resolvedPatternMatcher.findChainMatches(
                            (ResolvedPatternTree.Chain) andNot.required(), strippedText), strippedMessage),
                    disclaimerSpans);

            boolean excludedByAndNot = !resolvedPatternMatcher
                    .findChainMatches((ResolvedPatternTree.Chain) andNot.excluded(), strippedText).isEmpty();

            boolean hasAlertWorthyMatches = !excludedByAndNot
                    && requiredHighlights.stream().anyMatch(MatchHighlight::countsTowardAlert);
            boolean hasDisclaimerOnlyMatches = !excludedByAndNot
                    && !requiredHighlights.isEmpty() && !hasAlertWorthyMatches;
            String exclusionNote = excludedByAndNot ? EXCLUSION_NOTE_TEMPLATE : null;

            return new TermScanResult(
                    result.termId(), result.termDescription(),
                    result.regexPattern(), "PASS", true,
                    requiredHighlights, hasAlertWorthyMatches, hasDisclaimerOnlyMatches, excludedByAndNot,
                    null, exclusionNote);
        }

        List<MatchHighlight> highlights = disclaimerDetectionService.applyDisclaimerFlags(
                mapSpans(resolvedPatternMatcher.findChainMatches((ResolvedPatternTree.Chain) tree, strippedText),
                        strippedMessage),
                disclaimerSpans);

        boolean hasAlertWorthyMatches = highlights.stream().anyMatch(MatchHighlight::countsTowardAlert);
        boolean hasDisclaimerOnlyMatches = !highlights.isEmpty() && !hasAlertWorthyMatches;

        return new TermScanResult(
                result.termId(), result.termDescription(),
                result.regexPattern(), "PASS", false,
                highlights, hasAlertWorthyMatches, hasDisclaimerOnlyMatches, false,
                null, null);
    }

    /**
     * Maps {@link ResolvedPatternMatcher.TextSpan}s (in STRIPPED-text
     * coordinates — see {@link ResolvedPatternMatcher}) back to
     * ORIGINAL-text coordinates via {@link StrippedMessage#mapSpanToOriginal},
     * the same convention {@link MatchHighlightService} uses for the Regex
     * scanning path.
     */
    private List<MatchHighlight> mapSpans(List<ResolvedPatternMatcher.TextSpan> spans,
                                           StrippedMessage strippedMessage) {
        List<MatchHighlight> result = new ArrayList<>(spans.size());
        for (ResolvedPatternMatcher.TextSpan span : spans) {
            int[] original = strippedMessage.mapSpanToOriginal(span.startChar(), span.endChar());
            result.add(new MatchHighlight(original[0], original[1], span.matchedText(), false));
        }
        return result;
    }

    // ── Per-message scanning: Regex ──────────────────────────────────────────────

    private MessageScanResult scanOneMessageRegex(MessageSource.NamedMessageText message,
                                                    RegexResolution resolution,
                                                    List<String> disclaimerTexts) {
        StrippedMessage strippedMessage = htmlStrippingService.strip(message.text());
        log.debug("message='{}': stripped {} chars -> {} chars",
                message.messageId(), message.text().length(), strippedMessage.strippedText().length());

        List<DisclaimerSpan> disclaimerSpans =
                disclaimerDetectionService.detectDisclaimers(strippedMessage, disclaimerTexts);

        List<RawMatch> rawMatches = resolution.compiledTerms().isEmpty()
                ? Collections.emptyList()
                : scanService.compileAndScan(resolution.compiledTerms(), strippedMessage.strippedText());

        Map<Integer, List<MatchHighlight>> matchesByIndex =
                highlightService.groupMatchesByTerm(rawMatches, resolution.compiledTerms(), strippedMessage);

        Map<Integer, List<MatchHighlight>> flaggedMatchesByIndex = matchesByIndex.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> disclaimerDetectionService.applyDisclaimerFlags(e.getValue(), disclaimerSpans)));

        List<TermScanResult> termResults = buildRegexTermResults(
                resolution.allResults(), resolution.compiledTerms(), flaggedMatchesByIndex);

        return assembleMessageResult(message, disclaimerSpans, termResults);
    }

    private List<TermScanResult> buildRegexTermResults(
            List<CompileServiceResponse.TermResult> allResults,
            List<CompiledTerm> compiledTerms,
            Map<Integer, List<MatchHighlight>> matchesByIndex) {

        Map<String, CompiledTerm> compiledByTermId = compiledTerms.stream()
                .collect(Collectors.toMap(CompiledTerm::termId, ct -> ct));

        return allResults.stream().map(result -> {
            if (!result.isPass()) {
                return new TermScanResult(
                        result.termId(), result.termDescription(),
                        null, "FAILED", false,
                        List.of(), false, false, false, buildError(result, TermType.REGEX), null);
            }

            CompiledTerm ct = compiledByTermId.get(result.termId());
            List<MatchHighlight> highlights = ct != null
                    ? matchesByIndex.getOrDefault(ct.expressionIndex(), List.of())
                    : List.of();

            boolean hasAlertWorthyMatches = highlights.stream().anyMatch(MatchHighlight::countsTowardAlert);
            boolean hasDisclaimerOnlyMatches = !highlights.isEmpty() && !hasAlertWorthyMatches;

            return new TermScanResult(
                    result.termId(), result.termDescription(),
                    result.regexPattern(), "PASS", false,
                    highlights, hasAlertWorthyMatches, hasDisclaimerOnlyMatches, false,
                    null, null);
        }).toList();
    }

    // ── Shared message-result assembly ───────────────────────────────────────────

    private MessageScanResult assembleMessageResult(MessageSource.NamedMessageText message,
                                                      List<DisclaimerSpan> disclaimerSpans,
                                                      List<TermScanResult> termResults) {
        List<MatchHighlight> allHighlights = termResults.stream()
                .flatMap(t -> t.matches().stream())
                .toList();
        String htmlHighlighted = highlightService.buildHighlightedHtml(message.text(), allHighlights);

        int matchedTerms = (int) termResults.stream().filter(TermScanResult::hasMatches).count();
        boolean hasMatches = matchedTerms > 0;

        return new MessageScanResult(
                message.messageId(), message.text(), htmlHighlighted,
                disclaimerSpans, termResults, matchedTerms, hasMatches);
    }

    // ── Message resolution ───────────────────────────────────────────────────────

    /**
     * Extracts the resolved message list from {@link MessageSource}. File
     * reading and per-message length validation already happened in
     * {@code ScanController} — see {@link MessageSource} class Javadoc for
     * why that split exists — so this is a plain, exception-free extraction.
     */
    private List<MessageSource.NamedMessageText> resolveMessages(MessageSource source) {
        return switch (source) {
            case MessageSource.SingleText text ->
                    List.of(new MessageSource.NamedMessageText(SINGLE_TEXT_MESSAGE_ID, text.text()));
            case MessageSource.MultipleMessages messages -> messages.messages();
        };
    }

    // ── Disclaimer resolution ────────────────────────────────────────────────────

    /**
     * Resolves disclaimer text(s) into a flat list, reusing
     * {@link CsvParserService} unchanged for the CSV case — a disclaimer CSV
     * is read exactly like a lexicon term CSV (same two-column shape), just
     * interpreted as disclaimer text rather than a term description.
     */
    private List<String> resolveDisclaimerTexts(DisclaimerSource source) {
        return switch (source) {
            case DisclaimerSource.None ignored -> List.of();
            case DisclaimerSource.TextDisclaimers text -> text.disclaimerTexts();
            case DisclaimerSource.CsvFile csv -> csvParserService.parseTermDescriptions(csv.file());
        };
    }

    // ── Term resolution: NATURAL_LANGUAGE ───────────────────────────────────────

    /**
     * Resolves Natural Language terms by calling the Lexicon Compile Service —
     * {@code /compile} for typed text, {@code /compile/csv} for an uploaded
     * CSV file (forwarded as-is, never parsed locally). Every PASS term's
     * {@code resolvedPatterns} is parsed into a {@link ResolvedPatternTree}
     * — see class Javadoc "Two scanning paths". A term whose
     * {@code resolvedPatterns} cannot be structurally parsed (should not
     * happen given the documented Compile Service contract, but is not
     * assumed) is recorded in {@link NaturalLanguageResolution#parseErrorsByTermId()}
     * and surfaced as a FAILED result rather than silently dropped or
     * thrown all the way out of the scan.
     */
    private NaturalLanguageResolution resolveNaturalLanguageTerms(TermSource termSource, String ruleName,
                                                                    List<String> warnings) {
        CompileServiceResponse compileResp = switch (termSource) {
            case TermSource.TextTerms text -> compileClient.compile(text.descriptions(), ruleName);
            case TermSource.CsvFile csv -> compileClient.compileCsv(csv.file(), ruleName);
        };

        if (compileResp.hasFailures()) {
            long failedCount = compileResp.results().stream().filter(r -> !r.isPass()).count();
            warnings.add(failedCount + " term(s) failed pattern compilation and were skipped.");
            log.warn("{} Natural Language term(s) failed compilation", failedCount);
        }

        Map<String, ResolvedPatternTree> treesByTermId = new LinkedHashMap<>();
        Map<String, String> parseErrorsByTermId = new LinkedHashMap<>();

        for (CompileServiceResponse.TermResult r : compileResp.results()) {
            if (!r.isPass()) {
                continue;
            }
            try {
                // ResolvedPatternTree.zip() pulls both the required AND excluded chain's
                // leaves from ONE shared cursor, in the same left-to-right order
                // resolvedPatterns renders them (required side first, then the AND NOT
                // excluded side) — regexPattern alone only carries the required side, so
                // exclusionRegex's leaves must be appended, never passed separately.
                List<String> leaves = new ArrayList<>(r.regexPattern());
                if (r.exclusionRegex() != null) {
                    leaves.addAll(r.exclusionRegex());
                }
                treesByTermId.put(r.termId(), ResolvedPatternTree.build(r.termId(), r.resolvedPatterns(), leaves));
            } catch (ResolvedPatternTree.TermMetadataParseException e) {
                log.error("Term '{}' PASS-compiled but resolvedPatterns could not be parsed — "
                        + "treating as failed: {}", r.termId(), e.getMessage());
                parseErrorsByTermId.put(r.termId(), e.getMessage());
                warnings.add("Term '" + r.termDescription() + "' returned an inconsistent pattern structure "
                        + "and was skipped.");
            }
        }

        return new NaturalLanguageResolution(compileResp.results(), treesByTermId, parseErrorsByTermId);
    }

    // ── Term resolution: REGEX ───────────────────────────────────────────────────

    /**
     * Resolves Regex terms directly against Hyperscan — no Compile Service
     * call. Each term is validated INDIVIDUALLY first
     * ({@link HyperscanScanService#validatePattern}) so one bad regex in a
     * CSV batch fails only that term, not the whole scan. Regex-type terms
     * are raw caller-supplied patterns with no operator-language syntax, so
     * they never have an AND NOT exclusion or NEAR/FOLLOWEDBY structure.
     */
    private RegexResolution resolveRegexTerms(TermSource termSource, List<String> warnings) {
        List<String> rawTerms = switch (termSource) {
            case TermSource.TextTerms text -> text.descriptions();
            case TermSource.CsvFile csv -> csvParserService.parseTermDescriptions(csv.file());
        };

        List<CompileServiceResponse.TermResult> allResults = new ArrayList<>(rawTerms.size());
        List<CompiledTerm> compiledTerms = new ArrayList<>(rawTerms.size());
        int exprIndex = 0;

        for (int i = 0; i < rawTerms.size(); i++) {
            String pattern = rawTerms.get(i).strip();
            String termId = "regex::" + (i + 1);

            PatternValidationResult validation =
                    scanService.validatePattern(pattern, HyperscanScanService.HS_FLAGS_REGEX_DEFAULT);

            if (validation.pass()) {
                allResults.add(new CompileServiceResponse.TermResult(
                        termId, pattern, "PASS", List.of(pattern), false, null, null, null, null));
                compiledTerms.add(new CompiledTerm(
                        termId, pattern, List.of(pattern), null,
                        HyperscanScanService.HS_FLAGS_REGEX_DEFAULT, false, exprIndex++));
            } else {
                allResults.add(new CompileServiceResponse.TermResult(
                        termId, pattern, "FAILED", null, false, null, null, validation.errorMessage(), null));
                log.warn("Regex term rejected by Hyperscan: '{}' — {}", pattern, validation.errorMessage());
            }
        }

        long failedCount = allResults.stream().filter(r -> !r.isPass()).count();
        if (failedCount > 0) {
            warnings.add(failedCount + " regex term(s) were rejected by Hyperscan and were skipped.");
        }

        return new RegexResolution(allResults, compiledTerms);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * @param allResults      every submitted term's compile-service result (PASS + FAILED)
     * @param treesByTermId   parsed {@link ResolvedPatternTree} for every PASS term whose
     *                        {@code resolvedPatterns} parsed successfully
     * @param parseErrorsByTermId error message for a PASS term whose {@code resolvedPatterns}
     *                        did NOT parse successfully — see {@link #resolveNaturalLanguageTerms}
     */
    private record NaturalLanguageResolution(
            List<CompileServiceResponse.TermResult> allResults,
            Map<String, ResolvedPatternTree> treesByTermId,
            Map<String, String> parseErrorsByTermId
    ) {}

    /**
     * Bundles the full per-term result list (PASS + FAILED) and the PASS-only
     * compiled subset ready for the Hyperscan scan pass.
     */
    private record RegexResolution(
            List<CompileServiceResponse.TermResult> allResults,
            List<CompiledTerm> compiledTerms
    ) {}

    private String buildError(CompileServiceResponse.TermResult result, TermType termType) {
        if (termType == TermType.REGEX) {
            return result.errorLog() != null && !result.errorLog().isBlank()
                    ? result.errorLog() : "Unknown Hyperscan validation failure";
        }
        if (result.translationError() != null && !result.translationError().isBlank()) {
            return "Translation error: " + result.translationError();
        }
        if (result.errorLog() != null && !result.errorLog().isBlank()) {
            return "Hyperscan error: " + result.errorLog();
        }
        return "Unknown compilation failure";
    }
}
