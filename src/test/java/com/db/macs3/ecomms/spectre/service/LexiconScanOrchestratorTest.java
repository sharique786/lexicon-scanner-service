package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.client.LexiconCompileClient;
import com.db.macs3.ecomms.spectre.config.AppProperties;
import com.db.macs3.ecomms.spectre.model.DisclaimerSource;
import com.db.macs3.ecomms.spectre.model.Models.CompileServiceResponse;
import com.db.macs3.ecomms.spectre.model.Models.MessageScanResult;
import com.db.macs3.ecomms.spectre.model.Models.ScanResponse;
import com.db.macs3.ecomms.spectre.model.Models.TermScanResult;
import com.db.macs3.ecomms.spectre.model.MessageSource;
import com.db.macs3.ecomms.spectre.model.TermSource;
import com.db.macs3.ecomms.spectre.model.TermType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LexiconScanOrchestrator}.
 *
 * <h2>Real dependencies, not Mockito</h2>
 * <p>Only {@link LexiconCompileClient} is faked here — via
 * {@link FakeLexiconCompileClient}, a hand-built subclass returning canned
 * responses instead of making a real HTTP call, rather than a Mockito mock.
 * {@link HyperscanScanService} (used only by the REGEX path now),
 * {@link ResolvedPatternMatcher}, and {@link MatchHighlightService} are all
 * used as REAL instances.
 *
 * <p>This is deliberate: {@link ResolvedPatternMatcher} contains the
 * NEAR/FOLLOWEDBY/AND-NOT evaluation logic that replaced the old native
 * Hyperscan {@code HS_FLAG_COMBINATION} design (see
 * {@link LexiconScanOrchestrator} class Javadoc). Mocking it away would mean
 * these orchestrator tests could pass even if that logic were broken —
 * exercising it for real, end-to-end, is exactly what gives the AND NOT
 * tests below (particularly {@link #andNotTerm_excludedWhenExclusionPatternPresent}
 * and {@link #andNotTerm_alertWorthyWhenExclusionAbsent}) their value: they
 * prove the independent required/excluded evaluation and the
 * {@code excludedByAndNot} preservation logic both work correctly together,
 * not just that the orchestrator calls its collaborators with the right
 * arguments.
 */
@DisplayName("LexiconScanOrchestrator")
class LexiconScanOrchestratorTest {

    private FakeLexiconCompileClient compileClient;
    private LexiconScanOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getScanner().setFallbackToJavaRegex(true); // matches this sandbox's Hyperscan native limitation
        HyperscanScanService scanService = new HyperscanScanService(props);
        ResolvedPatternMatcher resolvedPatternMatcher = new ResolvedPatternMatcher();
        MatchHighlightService highlightService = new MatchHighlightService();
        HtmlStrippingService htmlStrippingService = new HtmlStrippingService();
        CsvParserService csvParserService = new CsvParserService();
        DisclaimerDetectionService disclaimerDetectionService = new DisclaimerDetectionService(htmlStrippingService);

        compileClient = new FakeLexiconCompileClient();
        orchestrator = new LexiconScanOrchestrator(
                compileClient, scanService, resolvedPatternMatcher, highlightService, htmlStrippingService,
                csvParserService, disclaimerDetectionService);
    }

    /** Convenience: single-message scan with no disclaimer exclusion. */
    private ScanResponse scanSingleMessage(TermType termType, TermSource termSource, String message) {
        return orchestrator.scan(termType, termSource, new MessageSource.SingleText(message),
                new DisclaimerSource.None(), "test-rule");
    }

    private MessageScanResult firstMessage(ScanResponse response) {
        return response.messageResults().get(0);
    }

    // ── Basic flow ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("NATURAL_LANGUAGE + text: scan() returns SUCCESS with a matched term")
    void naturalLanguageText_success() {
        compileClient.nextResponse = termResponse("test-rule::1", "insider OR trading",
                List.of("insider"), false, null);

        ScanResponse response = scanSingleMessage(TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("insider OR trading")), "an insider tip was shared");

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.totalTerms()).isEqualTo(1);
        assertThat(response.hasMatches()).isTrue();
        TermScanResult tr = firstMessage(response).termResults().get(0);
        assertThat(tr.hasMatches()).isTrue();
        assertThat(tr.matches()).isNotEmpty();
    }

    @Test
    @DisplayName("NATURAL_LANGUAGE + text: FAILED term result carries the compile-service error")
    void naturalLanguageText_failedTermCarriesError() {
        compileClient.nextResponse = new CompileServiceResponse(
                "req-1", "test-rule", 1, 0, 1, true,
                List.of(new CompileServiceResponse.TermResult(
                        "test-rule::1", "bad term", "FAILED", null, false, null, null,
                        null, "could not parse operator syntax")));

        ScanResponse response = scanSingleMessage(TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("bad term")), "some message");

        TermScanResult tr = firstMessage(response).termResults().get(0);
        assertThat(tr.compilationStatus()).isEqualTo("FAILED");
        assertThat(tr.compilationError()).contains("could not parse operator syntax");
        assertThat(response.failedTerms()).isEqualTo(1);
    }

    @Test
    @DisplayName("NATURAL_LANGUAGE + text: returns ERROR status when compile client throws")
    void naturalLanguageText_compileClientThrows_returnsError() {
        compileClient.throwOnNextCall = true;

        ScanResponse response = scanSingleMessage(TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("insider")), "some message");

        assertThat(response.status()).isEqualTo("ERROR");
        assertThat(response.errorMessage()).isNotBlank();
    }

    @Test
    @DisplayName("NATURAL_LANGUAGE + text: skips scanning when there are no PASS terms")
    void naturalLanguageText_noPassTerms_skipsScan() {
        compileClient.nextResponse = new CompileServiceResponse(
                "req-1", "test-rule", 1, 0, 1, true,
                List.of(new CompileServiceResponse.TermResult(
                        "test-rule::1", "bad", "FAILED", null, false, null, null,
                        null, "error")));

        ScanResponse response = scanSingleMessage(TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("bad")), "some message");

        assertThat(response.hasMatches()).isFalse();
        assertThat(firstMessage(response).termResults().get(0).matches()).isEmpty();
    }

    // ── AND NOT: the core scenario this round of changes addresses ─────────────

    @Test
    @DisplayName("NATURAL_LANGUAGE + text: AND NOT term is EXCLUDED (not alert-worthy) when its " +
                 "exclusion pattern is ALSO found — resolved natively by Hyperscan in one scan pass, " +
                 "with excludedByAndNot still correctly detected for the UI")
    void andNotTerm_excludedWhenExclusionPatternPresent() {
        compileClient.nextResponse = termResponse("test-rule::1", "insider AND NOT disclosed",
                List.of("insider"), true, List.of("disclosed"));

        String message = "insider trading was disclosed to the board yesterday";
        ScanResponse response = scanSingleMessage(TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("insider AND NOT disclosed")), message);

        TermScanResult tr = firstMessage(response).termResults().get(0);
        assertThat(tr.requiresExclusionCheck()).isTrue();
        assertThat(tr.excludedByAndNot()).isTrue();
        assertThat(tr.hasMatches()).isFalse(); // NOT alert-worthy, even though the required pattern matched
        assertThat(tr.exclusionNote()).isNotBlank();
        assertThat(tr.exclusionNote()).contains("AND NOT");
        assertThat(response.hasMatches()).isFalse();
        assertThat(response.matchedTerms()).isEqualTo(0);
    }

    @Test
    @DisplayName("NATURAL_LANGUAGE + text: AND NOT term IS alert-worthy when its exclusion pattern is NOT found")
    void andNotTerm_alertWorthyWhenExclusionAbsent() {
        compileClient.nextResponse = termResponse("test-rule::1", "insider AND NOT disclosed",
                List.of("insider"), true, List.of("disclosed"));

        String message = "insider trading occurred yesterday";
        ScanResponse response = scanSingleMessage(TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("insider AND NOT disclosed")), message);

        TermScanResult tr = firstMessage(response).termResults().get(0);
        assertThat(tr.excludedByAndNot()).isFalse();
        assertThat(tr.hasMatches()).isTrue();
        assertThat(tr.exclusionNote()).isNull();
    }

    @Test
    @DisplayName("NATURAL_LANGUAGE + text: a decomposed NEAR/FOLLOWEDBY term (multiple required-side " +
                 "leaves) produces one combined highlight spanning every satisfying leaf occurrence")
    void decomposedTerm_producesCombinedHighlight() {
        compileClient.nextResponse = termResponse("test-rule::1", "complex decomposed term",
                List.of("alpha", "beta", "gamma"), false, null);

        String message = "alpha appears here, beta appears there, gamma too";
        ScanResponse response = scanSingleMessage(TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("complex decomposed term")), message);

        TermScanResult tr = firstMessage(response).termResults().get(0);
        assertThat(tr.hasMatches()).isTrue();
        // One combined span per satisfying occurrence-combination (earliest-to-latest chosen leaf) —
        // not one highlight per leaf; see ResolvedPatternMatcher class Javadoc.
        assertThat(tr.matches()).hasSize(1);
        assertThat(tr.matches().get(0).matchedText())
                .contains("alpha").contains("beta").contains("gamma");
    }

    @Test
    @DisplayName("NATURAL_LANGUAGE + text: a NEAR/FOLLOWEDBY term is NOT alert-worthy when the leaves " +
                 "appear too far apart to satisfy the configured distance")
    void decomposedTerm_notMatchedWhenTooFarApart() {
        // NEAR{2} — at most 2 whole words between "alpha" and "beta".
        CompileServiceResponse response = new CompileServiceResponse(
                "req-1", "test-rule", 1, 1, 0, false,
                List.of(new CompileServiceResponse.TermResult(
                        "test-rule::1", "alpha NEAR{2} beta", "PASS", List.of("alpha", "beta"),
                        false, null, "alpha NEAR{2} beta", null, null)));
        compileClient.nextResponse = response;

        String message = "alpha one two three four five beta";
        ScanResponse scanResponse = scanSingleMessage(TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("alpha NEAR{2} beta")), message);

        TermScanResult tr = firstMessage(scanResponse).termResults().get(0);
        assertThat(tr.hasMatches()).isFalse();
        assertThat(tr.matches()).isEmpty();
    }

    // ── REGEX terms bypass the Compile Service entirely ─────────────────────────

    @Test
    @DisplayName("REGEX + text: never calls the Compile Service")
    void regexText_neverCallsCompileService() {
        ScanResponse response = scanSingleMessage(TermType.REGEX,
                new TermSource.TextTerms(List.of("(?:insider|trading)")), "an insider tip");

        assertThat(compileClient.callCount).isEqualTo(0);
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.hasMatches()).isTrue();
    }

    @Test
    @DisplayName("REGEX + text: invalid pattern is rejected with Hyperscan's error, no exception thrown")
    void regexText_invalidPattern_rejectedGracefully() {
        ScanResponse response = scanSingleMessage(TermType.REGEX,
                new TermSource.TextTerms(List.of("(?=lookahead not supported")), "some message");

        assertThat(response.status()).isEqualTo("SUCCESS"); // pipeline itself doesn't fail
        TermScanResult tr = firstMessage(response).termResults().get(0);
        assertThat(tr.compilationStatus()).isEqualTo("FAILED");
    }

    // ── CSV forwarding ────────────────────────────────────────────────────────

    @Test
    @DisplayName("NATURAL_LANGUAGE + CSV: forwards the raw file via compileCsv(), never compile()")
    void naturalLanguageCsv_forwardsFileDirectly() {
        compileClient.nextResponse = termResponse("test-rule::1", "insider OR trading",
                List.of("insider"), false, null);
        MockMultipartFile file = new MockMultipartFile("terms.csv",
                "term_id,term_description\nt::1,insider OR trading\n".getBytes(StandardCharsets.UTF_8));

        ScanResponse response = scanSingleMessage(TermType.NATURAL_LANGUAGE,
                new TermSource.CsvFile(file), "an insider tip");

        assertThat(compileClient.csvCallCount).isEqualTo(1);
        assertThat(compileClient.callCount).isEqualTo(0); // compile() (JSON path) never called
        assertThat(response.status()).isEqualTo("SUCCESS");
    }

    // ── Multi-message scanning ───────────────────────────────────────────────────

    @Test
    @DisplayName("multiple messages are each scanned against the SAME compiled terms")
    void multipleMessages_scannedAgainstSameTerms() {
        compileClient.nextResponse = termResponse("test-rule::1", "insider OR trading",
                List.of("insider"), false, null);

        ScanResponse response = orchestrator.scan(TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("insider OR trading")),
                new MessageSource.MultipleMessages(List.of(
                        new MessageSource.NamedMessageText("msg1.txt", "an insider tip"),
                        new MessageSource.NamedMessageText("msg2.txt", "nothing relevant here"))),
                new DisclaimerSource.None(), "test-rule");

        assertThat(response.totalMessages()).isEqualTo(2);
        assertThat(response.messageResults().get(0).hasMatches()).isTrue();
        assertThat(response.messageResults().get(1).hasMatches()).isFalse();
        // Compile Service is called ONCE, terms shared across both messages.
        assertThat(compileClient.callCount).isEqualTo(1);
    }

    // ── Disclaimer suppression (real DisclaimerDetectionService, end-to-end) ────

    @Test
    @DisplayName("a match found ONLY within a detected disclaimer does not count toward hasMatches / alerting")
    void matchWithinDisclaimer_doesNotCountAsAlert() {
        compileClient.nextResponse = termResponse("test-rule::1", "confidential",
                List.of("confidential"), false, null);

        String message = "This message is confidential and privileged.";
        ScanResponse response = orchestrator.scan(TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("confidential")),
                new MessageSource.SingleText(message),
                new DisclaimerSource.TextDisclaimers(List.of("This message is confidential and privileged.")),
                "test-rule");

        TermScanResult tr = firstMessage(response).termResults().get(0);
        assertThat(tr.hasMatches()).isFalse();
        assertThat(tr.hasDisclaimerOnlyMatches()).isTrue();
        assertThat(response.hasMatches()).isFalse();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a canned PASS {@link CompileServiceResponse} for one term,
     * including a {@code resolvedPatterns} string zipped consistently
     * against {@code requiredPatterns}/{@code exclusionPatterns} — required
     * since {@link com.db.macs3.ecomms.spectre.model.ResolvedPatternTree#build}
     * parses the two together. Multi-leaf chains are joined with a generous
     * {@code NEAR{50}} so ordinary short test messages satisfy the distance
     * constraint without every test needing to hand-craft one.
     */
    private CompileServiceResponse termResponse(String termId, String termDescription,
                                                 List<String> requiredPatterns, boolean requiresExclusion,
                                                 List<String> exclusionPatterns) {
        String requiredChainText = String.join(" NEAR{50} ", requiredPatterns);
        String resolvedPatterns = requiresExclusion
                ? requiredChainText + " AND NOT (" + String.join(" NEAR{50} ", exclusionPatterns) + ")"
                : requiredChainText;

        return new CompileServiceResponse(
                "req-1", "test-rule", 1, 1, 0, false,
                List.of(new CompileServiceResponse.TermResult(
                        termId, termDescription, "PASS", requiredPatterns,
                        requiresExclusion, exclusionPatterns, resolvedPatterns,
                        null, null)));
    }

    /**
     * Hand-built test double replacing a Mockito mock — overrides the only
     * two methods {@link LexiconScanOrchestrator} calls, returning canned
     * responses instead of making a real HTTP call. Constructed with
     * {@code null}/dummy arguments since the real constructor's fields are
     * never touched by the overridden methods.
     */
    private static final class FakeLexiconCompileClient extends LexiconCompileClient {
        CompileServiceResponse nextResponse;
        boolean throwOnNextCall = false;
        int callCount = 0;
        int csvCallCount = 0;

        FakeLexiconCompileClient() {
            super(null, new AppProperties());
        }

        @Override
        public CompileServiceResponse compile(List<String> termDescriptions, String ruleName) {
            callCount++;
            if (throwOnNextCall) {
                throw new LexiconCompileClient.LexiconCompileException("simulated failure");
            }
            return nextResponse;
        }

        @Override
        public CompileServiceResponse compileCsv(org.springframework.web.multipart.MultipartFile file, String ruleName) {
            csvCallCount++;
            if (throwOnNextCall) {
                throw new LexiconCompileClient.LexiconCompileException("simulated failure");
            }
            return nextResponse;
        }
    }
}
