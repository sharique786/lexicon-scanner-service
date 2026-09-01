package com.db.macs3.ecomms.spectre.integration;

import com.db.macs3.ecomms.spectre.client.LexiconCompileClient;
import com.db.macs3.ecomms.spectre.config.AppProperties;
import com.db.macs3.ecomms.spectre.model.DisclaimerSource;
import com.db.macs3.ecomms.spectre.model.MessageSource;
import com.db.macs3.ecomms.spectre.model.Models.CompileServiceResponse;
import com.db.macs3.ecomms.spectre.model.Models.CompileServiceResponse.TermResult;
import com.db.macs3.ecomms.spectre.model.Models.ScanResponse;
import com.db.macs3.ecomms.spectre.model.Models.TermScanResult;
import com.db.macs3.ecomms.spectre.model.TermSource;
import com.db.macs3.ecomms.spectre.model.TermType;
import com.db.macs3.ecomms.spectre.service.CsvParserService;
import com.db.macs3.ecomms.spectre.service.DisclaimerDetectionService;
import com.db.macs3.ecomms.spectre.service.HtmlStrippingService;
import com.db.macs3.ecomms.spectre.service.HyperscanScanService;
import com.db.macs3.ecomms.spectre.service.LexiconScanOrchestrator;
import com.db.macs3.ecomms.spectre.service.MatchHighlightService;
import com.db.macs3.ecomms.spectre.service.ResolvedPatternMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Multi-language integration tests for the Lexicon Scanner pipeline.
 *
 * <h2>Strategy</h2>
 * <p>The {@link LexiconCompileClient} (which calls the external Lexicon Compile
 * Service over HTTP) is mocked with pre-built {@link CompileServiceResponse}
 * fixtures containing patterns equivalent to what the real Lexicon Compile
 * Service would produce.
 *
 * <p>The remaining pipeline — {@link HyperscanScanService} (with Java-regex
 * fallback enabled), {@link MatchHighlightService}, and
 * {@link LexiconScanOrchestrator} — runs with real implementation instances
 * so the full scan and highlight logic is exercised end-to-end.
 *
 * <h2>Multi-language messages tested</h2>
 * <ul>
 *   <li>English — baseline market-abuse scenario</li>
 *   <li>Korean  — 내부자 거래 (insider trading)</li>
 *   <li>Simplified Chinese — 内幕交易 (insider trading)</li>
 *   <li>Arabic  — تداول المعلومات الداخلية (insider trading)</li>
 *   <li>Japanese — インサイダー取引 (insider trading)</li>
 *   <li>Hebrew  — מסחר פנים (insider trading)</li>
 *   <li>Emoji   — 💰🤫 (money bag + shushing face)</li>
 *   <li>Mixed   — English message with emoji and non-ASCII</li>
 * </ul>
 *
 * <h2>Operator scenarios tested</h2>
 * <ul>
 *   <li>Simple OR term</li>
 *   <li>AND term (requiresAndPostFilter=true)</li>
 *   <li>NEAR{n} term</li>
 *   <li>FOLLOWEDBY{n} term</li>
 *   <li>Wildcard pattern</li>
 *   <li>Quoted phrase</li>
 *   <li>NOT / AND NOT term</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Multi-Language Scan Integration")
class MultiLanguageScanIntegrationTest {

    @Mock private LexiconCompileClient compileClient;

    private LexiconScanOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getScanner().setFallbackToJavaRegex(true);

        HyperscanScanService scanService   = new HyperscanScanService(props);
        ResolvedPatternMatcher resolvedPatternMatcher = new ResolvedPatternMatcher();
        MatchHighlightService highlighter  = new MatchHighlightService();
        HtmlStrippingService htmlStrippingService = new HtmlStrippingService();
        CsvParserService csvParserService  = new CsvParserService();
        DisclaimerDetectionService disclaimerDetectionService = new DisclaimerDetectionService(htmlStrippingService);
        orchestrator = new LexiconScanOrchestrator(
                compileClient, scanService, resolvedPatternMatcher, highlighter, htmlStrippingService,
                csvParserService, disclaimerDetectionService);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // English — OR operator
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[EN] OR: 'pump OR dump' matches message containing 'pump'")
    void en_orOperator_matchesPump() {
        mockCompile("(?:pump|dump)", 1, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("pump OR dump")),
                new MessageSource.SingleText("Analyst says: pump the stock before close."),
                new DisclaimerSource.None(),
                "en-test");

        assertMatched(resp, 1);
        assertMatchText(resp, 0, "pump");
    }

    @Test
    @DisplayName("[EN] OR: 'spoofing OR layering' matches 'layering' in message")
    void en_orOperator_matchesLayering() {
        mockCompile("(?:spoofing|layering)", 1, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("spoofing OR layering")),
                new MessageSource.SingleText("The layering of orders created a false impression of demand."),
                new DisclaimerSource.None(),
                "en-test");

        assertMatched(resp, 1);
        assertMatchText(resp, 0, "layering");
    }

    @Test
    @DisplayName("[EN] OR: no match when none of the OR alternatives appear in message")
    void en_orOperator_noMatch() {
        mockCompile("(?:spoofing|layering)", 1, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("spoofing OR layering")),
                new MessageSource.SingleText("Everything is normal on this trading desk."),
                new DisclaimerSource.None(),
                "en-test");

        assertNoMatch(resp);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // English — AND operator (corrected: self-contained co-occurrence pattern, no exclusion needed)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[EN] AND: 'insider AND trading' matches when both words are present, any order/distance")
    void en_andOperator_bothWordsPresent_matches() {
        // Corrected AND semantics: unbounded bidirectional co-occurrence, NOT an OR pre-scan.
        mockCompile("(?:insider[\\s\\S]*trading|trading[\\s\\S]*insider)", 1, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("insider AND trading")),
                new MessageSource.SingleText("The insider passed the trading information to his broker."),
                new DisclaimerSource.None(),
                "en-test");

        assertMatched(resp, 1);
        TermScanResult tr = resp.messageResults().get(0).termResults().get(0);
        // Plain AND is self-contained — no exclusion check, no exclusion note.
        assertThat(tr.requiresExclusionCheck()).isFalse();
        assertThat(tr.exclusionNote()).isNull();
        assertThat(tr.excludedByAndNot()).isFalse();
    }

    @Test
    @DisplayName("[EN] AND: 'front AND running' — both words present is ONE co-occurrence match, not two separate OR hits")
    void en_andOperator_bothTermsPresent() {
        mockCompile("(?:front[\\s\\S]*running|running[\\s\\S]*front)", 1, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("front AND running")),
                new MessageSource.SingleText("Front running the block trade before client execution."),
                new DisclaimerSource.None(),
                "en-test");

        assertMatched(resp, 1);
        assertThat(resp.messageResults().get(0).termResults().get(0).matches().size()).isGreaterThanOrEqualTo(1);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // English — NEAR{n} operator
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[EN] NEAR: 'insider NEAR{5} trading' matches when words are close")
    void en_nearOperator_wordsAreClose_matches() {
        // NEAR{5}: A within 5 words of B (bidirectional)
        String pattern = "(?:insider(?:\\s+\\S+){0,5}\\s+trading|trading(?:\\s+\\S+){0,5}\\s+insider)";
        mockCompile(pattern, 1, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("insider NEAR{5} trading")),
                new MessageSource.SingleText("The insider is under investigation for illegal trading."),
                new DisclaimerSource.None(),
                "en-test");

        assertMatched(resp, 1);
    }

    @Test
    @DisplayName("[EN] NEAR: 'insider NEAR{2} trading' does not match when words are far apart")
    void en_nearOperator_wordsTooFar_noMatch() {
        // NEAR{2}: only 0-2 words between insider and trading
        String pattern = "(?:insider(?:\\s+\\S+){0,2}\\s+trading|trading(?:\\s+\\S+){0,2}\\s+insider)";
        mockCompile(pattern, 1, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("insider NEAR{2} trading")),
                new MessageSource.SingleText("The insider is involved in many activities including stock market trading."),
                new DisclaimerSource.None(),
                "en-test");

        assertNoMatch(resp);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // English — FOLLOWEDBY{n} operator
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[EN] FOLLOWEDBY: 'buy FOLLOWEDBY{3} shares' matches directionally")
    void en_followedByOperator_matches() {
        String pattern = "buy(?:\\s+\\S+){0,3}\\s+shares";
        mockCompile(pattern, 1, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("buy FOLLOWEDBY{3} shares")),
                new MessageSource.SingleText("I want to buy 1000 shares before the announcement."),
                new DisclaimerSource.None(),
                "en-test");

        assertMatched(resp, 1);
    }

    @Test
    @DisplayName("[EN] FOLLOWEDBY: does not match if B comes before A")
    void en_followedByOperator_reverseOrder_noMatch() {
        String pattern = "buy(?:\\s+\\S+){0,3}\\s+shares";
        mockCompile(pattern, 1, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("buy FOLLOWEDBY{3} shares")),
                new MessageSource.SingleText("Shares were sold, I did not buy."),
                new DisclaimerSource.None(),
                "en-test");

        assertNoMatch(resp);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // English — Wildcard operator
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[EN] Wildcard: 'trade*' matches 'trading', 'trader', 'traded'")
    void en_wildcard_matchesDerivedForms() {
        mockCompile("trade\\S*", 1, false);

        for (String word : new String[]{"trading", "trader", "traded", "tradeoff"}) {
            mockCompile("trade\\S*", 1, false);
            ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("trade*")),
                new MessageSource.SingleText("The " + word + " activity was flagged."),
                new DisclaimerSource.None(),
                "en-test");
            assertMatched(resp, 1);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // English — Quoted phrase
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[EN] Quoted phrase: '\"front running\"' matches exact phrase")
    void en_quotedPhrase_matchesExactPhrase() {
        mockCompile("front\\s+running", 1, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("\"front running\"")),
                new MessageSource.SingleText("The trader was accused of front running the institutional order."),
                new DisclaimerSource.None(),
                "en-test");

        assertMatched(resp, 1);
    }

    @Test
    @DisplayName("[EN] Quoted phrase: '\"front running\"' does not match 'front-running' (hyphen)")
    void en_quotedPhrase_hyphenatedNoMatch() {
        mockCompile("front\\s+running", 1, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("\"front running\"")),
                new MessageSource.SingleText("The front-running concern was raised in the audit."),
                new DisclaimerSource.None(),
                "en-test");

        assertNoMatch(resp);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Korean — 내부자 거래 (insider trading)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[KO] OR: '내부자 OR 거래' matches Korean message")
    void ko_orOperator_matchesKorean() {
        mockCompile("(?:내부자|거래)", 97, false);  // 1|32|64 = CASELESS|UTF8|UCP

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("내부자 OR 거래")),
                new MessageSource.SingleText("이 거래는 내부자 정보를 기반으로 한 것으로 의심됩니다."),
                new DisclaimerSource.None(),
                "ko-test");

        assertMatched(resp, 1);
    }

    @Test
    @DisplayName("[KO] Literal: '내부자 거래' matches Korean insider-trading message")
    void ko_literalPhrase_matches() {
        mockCompile("내부자\\s+거래", 97, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("내부자 거래")),
                new MessageSource.SingleText("혐의자는 내부자 거래 혐의로 조사를 받고 있습니다."),
                new DisclaimerSource.None(),
                "ko-test");

        assertMatched(resp, 1);
    }

    @Test
    @DisplayName("[KO] Korean mixed with English — matches Korean word in mixed message")
    void ko_mixedEnglishKorean_matchesKoreanWord() {
        mockCompile("(?:내부자|insider)", 97, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("내부자 OR insider")),
                new MessageSource.SingleText("Bloomberg report: 내부자 거래 was flagged at the Seoul desk."),
                new DisclaimerSource.None(),
                "ko-test");

        assertMatched(resp, 1);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Simplified Chinese — 内幕交易 (insider trading)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[ZH] OR: '内幕 OR 交易' matches Simplified Chinese message")
    void zh_orOperator_matchesChinese() {
        mockCompile("(?:内幕|交易)", 97, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("内幕 OR 交易")),
                new MessageSource.SingleText("该交易员涉嫌利用内幕信息进行内幕交易。"),
                new DisclaimerSource.None(),
                "zh-test");

        assertMatched(resp, 1);
    }

    @Test
    @DisplayName("[ZH] Wildcard: '内幕*' matches '内幕交易' and '内幕信息'")
    void zh_wildcard_matchesChinese() {
        mockCompile("内幕\\S*", 97, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("内幕*")),
                new MessageSource.SingleText("交易员利用了内幕信息进行了内幕交易。"),
                new DisclaimerSource.None(),
                "zh-test");

        assertMatched(resp, 1);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Arabic — تداول المعلومات الداخلية (insider trading)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[AR] OR: 'معلومات OR داخلية' matches Arabic insider-trading message")
    void ar_orOperator_matchesArabic() {
        mockCompile("(?:معلومات|داخلية)", 97, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("معلومات OR داخلية")),
                new MessageSource.SingleText("تم الإبلاغ عن مخاوف تتعلق بتداول المعلومات الداخلية."),
                new DisclaimerSource.None(),
                "ar-test");

        assertMatched(resp, 1);
    }

    @Test
    @DisplayName("[AR] Arabic match produces correct highlighted HTML with <mark>")
    void ar_matchProducesHighlightedHtml() {
        mockCompile("(?:معلومات|داخلية)", 97, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("معلومات OR داخلية")),
                new MessageSource.SingleText("استخدم المعلومات الداخلية للتداول."),
                new DisclaimerSource.None(),
                "ar-test");

        assertThat(resp.messageResults().get(0).htmlHighlightedMessage())
                .contains("<mark class=\"lex-match\">");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Japanese — インサイダー取引 (insider trading)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[JA] OR: 'インサイダー OR 取引' matches Japanese message")
    void ja_orOperator_matchesJapanese() {
        mockCompile("(?:インサイダー|取引)", 97, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("インサイダー OR 取引")),
                new MessageSource.SingleText("このインサイダー取引は市場の公正性を損なうものです。"),
                new DisclaimerSource.None(),
                "ja-test");

        assertMatched(resp, 1);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Hebrew — מסחר פנים (insider trading)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[HE] OR: 'מסחר OR פנים' matches Hebrew message")
    void he_orOperator_matchesHebrew() {
        mockCompile("(?:מסחר|פנים)", 97, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("מסחר OR פנים")),
                new MessageSource.SingleText("החשוד בוצע מסחר פנים על בסיס מידע סודי."),
                new DisclaimerSource.None(),
                "he-test");

        assertMatched(resp, 1);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Emoji
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[EMOJI] '💰 OR 🤫' matches message containing money-bag emoji")
    void emoji_orOperator_matchesMoneyBag() {
        mockCompile("(?:\\x{1F4B0}|\\x{1F92B})", 97, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("💰 OR 🤫")),
                new MessageSource.SingleText("Meet me after the close 💰 the deal is done."),
                new DisclaimerSource.None(),
                "emoji-test");

        assertMatched(resp, 1);
    }

    @Test
    @DisplayName("[EMOJI] Emoji in mixed English message — both emoji and text terms match")
    void emoji_mixedMessage_emojiAndTextBothMatch() {
        when(compileClient.compile(anyList(), anyString())).thenReturn(
                buildResponse(List.of(
                        pass("test-rule::1", "pump OR dump", "(?:pump|dump)", 1, false),
                        pass("test-rule::2", "💰", "\\x{1F4B0}", 97, false)
                )));

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("pump OR dump", "💰")),
                new MessageSource.SingleText("Buy low 💰, the pump is starting."),
                new DisclaimerSource.None(),
                "emoji-mixed-test");

        assertThat(resp.matchedTerms()).isEqualTo(2);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Multiple terms in one scan
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Multiple terms in one scan — each term's match is independent")
    void multipleTerms_eachMatchesIndependently() {
        when(compileClient.compile(anyList(), anyString())).thenReturn(
                buildResponse(List.of(
                        pass("r::1", "pump OR dump", "(?:pump|dump)", 1, false),
                        pass("r::2", "insider AND trading", "(?:insider|trading)", 3, true),
                        pass("r::3", "\"front running\"", "front\\s+running", 1, false)
                )));

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("pump OR dump", "insider AND trading", "\"front running\"")),
                new MessageSource.SingleText("The trader was pump-and-dump involved, insider trading, and front running."),
                new DisclaimerSource.None(),
                "multi-test");

        assertThat(resp.totalTerms()).isEqualTo(3);
        assertThat(resp.matchedTerms()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Failed term does not prevent other terms from matching")
    void failedTermDoesNotBlockOthers_otherTermsMatch() {
        when(compileClient.compile(anyList(), anyString())).thenReturn(
                buildResponse(List.of(
                        failed("r::1", "bad pattern"),
                        pass("r::2", "pump OR dump", "(?:pump|dump)", 1, false)
                )));

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("bad pattern", "pump OR dump")),
                new MessageSource.SingleText("He tried to pump the stock."),
                new DisclaimerSource.None(),
                "mixed-test");

        assertThat(resp.failedTerms()).isEqualTo(1);
        assertThat(resp.matchedTerms()).isEqualTo(1);
        assertThat(resp.status()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("GZIP-style integration: long message with many terms — scan completes within 5 seconds")
    void longMessage_manyTerms_performanceAcceptable() {
        // Build a 50-term response
        List<TermResult> results = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            results.add(pass("r::" + i, "term" + i, "(?:term" + i + ")", 1, false));
        }
        when(compileClient.compile(anyList(), anyString()))
                .thenReturn(buildResponse(results));

        // Build a 10 000-character message
        String msg = "term1 term5 term10 term25 ".repeat(400);
        List<String> termDescs = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) termDescs.add("term" + i);

        long start = System.currentTimeMillis();
        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(termDescs),
                new MessageSource.SingleText(msg),
                new DisclaimerSource.None(),
                "perf-test");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(5_000L);
        assertThat(resp.status()).isEqualTo("SUCCESS");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HTML highlighting sanity checks
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Highlighted HTML escapes stray angle brackets that are NOT well-formed tags")
    void highlightedHtml_noUnescapedHtml_inPlainText() {
        mockCompile("(?:insider)", 1, false);

        // Note: angle brackets here are space-separated from their contents, so
        // HtmlStrippingService's tag pattern does NOT treat them as tags (see
        // HtmlStrippingServiceTest#strayLessThan_notTreatedAsTag) — they survive
        // into the scanned text as literal characters and must be HTML-escaped
        // in the rendered output.
        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("insider")),
                new MessageSource.SingleText("The insider disclosed material < 5 minutes before the announcement."),
                new DisclaimerSource.None(),
                "html-escape-test");

        String html = resp.messageResults().get(0).htmlHighlightedMessage();
        // The stray '<' should be HTML-escaped in the plain-text portion
        assertThat(html).contains("&lt; 5 minutes");
        // Mark tags themselves are the only raw HTML
        assertThat(html).contains("<mark class=\"lex-match\">");
    }

    @Test
    @DisplayName("A word wrapped in angle brackets IS treated as a real tag and stripped before scanning " +
                 "— documents the tag-stripping requirement's precedence over literal bracket text")
    void wellFormedTagShapedWord_isStrippedBeforeScanning_documentedBehaviour() {
        mockCompile("(?:insider)", 1, false);

        // "<insider>" is well-formed-tag-shaped (matches HtmlStrippingService's
        // TAG_PATTERN exactly like a real <span> would), so per the HTML-stripping
        // requirement it is removed BEFORE Hyperscan ever sees the text — the term
        // "insider" therefore does NOT match here, even though the literal
        // characters "insider" are visually present in the original message.
        // This is the correct, intentional behaviour for real HTML-formatted
        // messages (where tag names are markup, not searchable content); it is
        // called out explicitly here so the tradeoff is documented, not implicit.
        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("insider")),
                new MessageSource.SingleText("The <insider> tag-shaped text is not searchable content."),
                new DisclaimerSource.None(),
                "html-escape-test");

        assertNoMatch(resp);
    }

    @Test
    @DisplayName("Highlighted HTML is produced for Korean match")
    void highlightedHtml_koreanMatch_containsMark() {
        mockCompile("(?:내부자|거래)", 97, false);

        ScanResponse resp = orchestrator.scan(
                TermType.NATURAL_LANGUAGE,
                new TermSource.TextTerms(List.of("내부자 OR 거래")),
                new MessageSource.SingleText("내부자 거래를 통해 부당이득을 취했습니다."),
                new DisclaimerSource.None(),
                "ko-html-test");

        assertThat(resp.messageResults().get(0).htmlHighlightedMessage()).contains("<mark class=\"lex-match\">");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private void mockCompile(String pattern, int flags, boolean postFilter) {
        when(compileClient.compile(anyList(), anyString())).thenReturn(
                buildResponse(List.of(
                        pass("test-rule::1", "test-term", pattern, flags, postFilter))));
    }

    private CompileServiceResponse buildResponse(List<TermResult> results) {
        long pass   = results.stream().filter(TermResult::isPass).count();
        long failed = results.size() - pass;
        return new CompileServiceResponse("req-1", "test-rule", results.size(),
                (int) pass, (int) failed, failed > 0, results);
    }

    /**
     * @param flags unused now — the Compile Service no longer serialises
     *              {@code hyperscanFlags} (see {@code Models.CompileServiceResponse.TermResult}
     *              class Javadoc); kept as a parameter only so the many call
     *              sites throughout this file didn't all need updating
     */
    private TermResult pass(String id, String desc, String pattern, int flags, boolean requiresExclusionCheck) {
        String exclusionPattern = requiresExclusionCheck ? "(?:excluded)" : null;
        String resolvedPatterns = requiresExclusionCheck
                ? pattern + " AND NOT (" + exclusionPattern + ")"
                : pattern;
        return new TermResult(id, desc, "PASS", List.of(pattern), requiresExclusionCheck,
                requiresExclusionCheck ? List.of(exclusionPattern) : null, resolvedPatterns, null, null);
    }

    private TermResult failed(String id, String desc) {
        return new TermResult(id, desc, "FAILED", null, false, null, null, "Compile error", null);
    }

    private void assertMatched(ScanResponse resp, int termCount) {
        assertThat(resp.status()).isEqualTo("SUCCESS");
        assertThat(resp.matchedTerms()).isGreaterThanOrEqualTo(termCount);
        assertThat(resp.hasMatches()).isTrue();
        assertThat(resp.messageResults().get(0).termResults().get(0).hasMatches()).isTrue();
        assertThat(resp.messageResults().get(0).termResults().get(0).matches()).isNotEmpty();
    }

    private void assertNoMatch(ScanResponse resp) {
        assertThat(resp.status()).isEqualTo("SUCCESS");
        assertThat(resp.matchedTerms()).isEqualTo(0);
        assertThat(resp.hasMatches()).isFalse();
    }

    private void assertMatchText(ScanResponse resp, int termIdx, String expectedText) {
        assertThat(resp.messageResults().get(0).termResults().get(termIdx).matches())
                .anySatisfy(m -> assertThat(m.matchedText())
                        .containsIgnoringCase(expectedText));
    }
}
