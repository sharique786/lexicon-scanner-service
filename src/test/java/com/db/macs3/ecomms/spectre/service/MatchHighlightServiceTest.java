package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.model.Models.CompiledTerm;
import com.db.macs3.ecomms.spectre.model.Models.MatchHighlight;
import com.db.macs3.ecomms.spectre.model.Models.RawMatch;
import com.db.macs3.ecomms.spectre.model.Models.StrippedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link MatchHighlightService}.
 */
@DisplayName("MatchHighlightService")
class MatchHighlightServiceTest {

    private MatchHighlightService service;
    private HtmlStrippingService htmlStrippingService;

    @BeforeEach
    void setUp() {
        service = new MatchHighlightService();
        htmlStrippingService = new HtmlStrippingService();
    }

    /** Builds a StrippedMessage with an IDENTITY mapping — as if the text had no HTML/whitespace to collapse. */
    private StrippedMessage identityStripped(String plainText) {
        return htmlStrippingService.strip(plainText);
    }

    // ── groupMatchesByTerm ────────────────────────────────────────────────────

    @Test
    @DisplayName("groups raw matches by expression index correctly")
    void groupMatchesByTerm_groupsCorrectly() {
        String message = "insider trading is illegal";
        StrippedMessage stripped = identityStripped(message);

        List<CompiledTerm> terms = List.of(
                term(0, "(?:insider)", 1),
                term(1, "(?:trading)", 1));

        // "insider" = chars 0-7, "trading" = chars 8-15 (byte positions same for ASCII)
        List<RawMatch> raw = List.of(
                new RawMatch(0, 7, 0, false),   // insider, term 0
                new RawMatch(8, 15, 1, false)); // trading, term 1

        Map<Integer, List<MatchHighlight>> result =
                service.groupMatchesByTerm(raw, terms, stripped);

        assertThat(result).containsKeys(0, 1);
        assertThat(result.get(0).get(0).matchedText()).isEqualTo("insider");
        assertThat(result.get(1).get(0).matchedText()).isEqualTo("trading");
    }

    @Test
    @DisplayName("empty raw matches returns empty map")
    void groupMatchesByTerm_emptyMatches_emptyMap() {
        Map<Integer, List<MatchHighlight>> result =
                service.groupMatchesByTerm(List.of(), List.of(), identityStripped("some text"));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("HTML-stripped message: matched positions map back to ORIGINAL text coordinates")
    void groupMatchesByTerm_htmlStripped_positionsMapToOriginal() {
        String original = "<p>Enjoy</p>\n<p>Happy Birthday</p>";
        StrippedMessage stripped = htmlStrippingService.strip(original);
        // stripped.strippedText() == "Enjoy Happy Birthday"; "Enjoy Happy" spans stripped [0,11)

        List<CompiledTerm> terms = List.of(term(0, "Enjoy(?:\\s+\\S+){0,2}\\s+Happy", 1));
        // byte offsets == char offsets here (all ASCII in the stripped text)
        List<RawMatch> raw = List.of(new RawMatch(0, 11, 0, false));

        Map<Integer, List<MatchHighlight>> result = service.groupMatchesByTerm(raw, terms, stripped);

        MatchHighlight highlight = result.get(0).get(0);
        assertThat(highlight.matchedText()).isEqualTo("Enjoy Happy"); // clean, from STRIPPED text
        assertThat(highlight.startCharIndex()).isEqualTo(3);          // mapped to ORIGINAL text
        assertThat(highlight.endCharIndex()).isEqualTo(21);           // mapped to ORIGINAL text
    }

    // ── buildHighlightedHtml ─────────────────────────────────────────────────

    @Test
    @DisplayName("no matches returns HTML-escaped plain message")
    void buildHighlightedHtml_noMatches_returnsEscapedText() {
        String result = service.buildHighlightedHtml("Hello & World <test>", List.of());
        assertThat(result)
                .isEqualTo("Hello &amp; World &lt;test&gt;")
                .doesNotContain("<mark");
    }

    @Test
    @DisplayName("single match wraps matched text in <mark> with class 'lex-match'")
    void buildHighlightedHtml_singleMatch_wrapsInMark() {
        String message = "insider bought shares";
        MatchHighlight match = new MatchHighlight(0, 7, "insider", false);

        String html = service.buildHighlightedHtml(message, List.of(match));

        assertThat(html)
                .startsWith("<mark class=\"lex-match\">insider</mark>")
                .endsWith("bought shares");
    }

    @Test
    @DisplayName("match in the middle of text — before and after parts are HTML-escaped")
    void buildHighlightedHtml_matchInMiddle_correctBoundaries() {
        String message = "The insider bought <shares>.";
        MatchHighlight match = new MatchHighlight(4, 11, "insider", false);

        String html = service.buildHighlightedHtml(message, List.of(match));

        assertThat(html)
                .startsWith("The ")
                .contains("<mark class=\"lex-match\">insider</mark>")
                .contains("bought &lt;shares&gt;.");
    }

    @Test
    @DisplayName("multiple non-overlapping matches are each wrapped in <mark>")
    void buildHighlightedHtml_multipleNonOverlappingMatches() {
        String message = "insider trading is illegal";
        List<MatchHighlight> matches = List.of(
                new MatchHighlight(0, 7, "insider", false),
                new MatchHighlight(8, 15, "trading", false));

        String html = service.buildHighlightedHtml(message, matches);

        assertThat(html)
                .contains("<mark class=\"lex-match\">insider</mark>")
                .contains("<mark class=\"lex-match\">trading</mark>")
                .doesNotContain("insider trading"); // the plain version should not appear
    }

    @Test
    @DisplayName("overlapping matches are merged — single <mark> span covers both")
    void buildHighlightedHtml_overlappingMatches_merged() {
        String message = "front running scheme";
        // Match 1: "front running" (0-13), Match 2: "running" (6-13) — overlapping
        List<MatchHighlight> matches = List.of(
                new MatchHighlight(0, 13, "front running", false),
                new MatchHighlight(6, 13, "running", false));

        String html = service.buildHighlightedHtml(message, matches);

        // Should not have nested marks
        assertThat(html).doesNotContain("<mark class=\"lex-match\"><mark");
        assertThat(html).contains("<mark class=\"lex-match\">");
    }

    @Test
    @DisplayName("match at end of text — no trailing content after mark")
    void buildHighlightedHtml_matchAtEnd() {
        String message = "shares trade";
        MatchHighlight match = new MatchHighlight(7, 12, "trade", false);

        String html = service.buildHighlightedHtml(message, List.of(match));

        assertThat(html)
                .startsWith("shares ")
                .endsWith("<mark class=\"lex-match\">trade</mark>");
    }

    @Test
    @DisplayName("HTML-special characters inside matched text are escaped")
    void buildHighlightedHtml_htmlSpecialCharsInMatch() {
        String message = "Term <insider> here";
        MatchHighlight match = new MatchHighlight(5, 14, "<insider>", false);

        String html = service.buildHighlightedHtml(message, List.of(match));

        assertThat(html).contains("<mark class=\"lex-match\">&lt;insider&gt;</mark>");
    }

    @Test
    @DisplayName("Korean multi-byte text — match on Korean word produces correct <mark>")
    void buildHighlightedHtml_koreanText_correctHighlight() {
        String message = "내부자 거래 정보";
        // "내부자" = chars 0-2 (3 chars, 9 bytes); "거래" = chars 4-5
        MatchHighlight match = new MatchHighlight(0, 3, "내부자", false);

        String html = service.buildHighlightedHtml(message, List.of(match));

        assertThat(html)
                .contains("<mark class=\"lex-match\">내부자</mark>")
                .contains(" 거래 정보");
    }

    @Test
    @DisplayName("Arabic text — match on Arabic word produces correct <mark>")
    void buildHighlightedHtml_arabicText_correctHighlight() {
        String message = "معلومات سرية";
        MatchHighlight match = new MatchHighlight(0, 8, "معلومات", false);

        String html = service.buildHighlightedHtml(message, List.of(match));

        assertThat(html).contains("<mark class=\"lex-match\">معلومات</mark>");
    }

    @Test
    @DisplayName("emoji match — emoji is correctly enclosed in <mark>")
    void buildHighlightedHtml_emojiMatch() {
        String message = "buy 💰 now";
        MatchHighlight match = new MatchHighlight(4, 6, "💰", false); // emoji is 2 Java chars (surrogate pair)

        String html = service.buildHighlightedHtml(message, List.of(match));

        assertThat(html)
                .contains("<mark class=\"lex-match\">💰</mark>")
                .startsWith("buy ");
    }

    @Test
    @DisplayName("message is exactly one match — entire message is wrapped")
    void buildHighlightedHtml_wholeMessageMatch() {
        String message = "insider";
        MatchHighlight match = new MatchHighlight(0, 7, "insider", false);

        String html = service.buildHighlightedHtml(message, List.of(match));

        assertThat(html).isEqualTo("<mark class=\"lex-match\">insider</mark>");
    }

    @Test
    @DisplayName("HTML-stripped match: the <mark> shows the ORIGINAL substring (tags escaped), " +
                 "not the clean matchedText field")
    void buildHighlightedHtml_htmlStrippedMatch_showsOriginalMarkupInMark() {
        String original = "<p>Enjoy</p>\n<p>Happy Birthday</p>";
        // Simulates what groupMatchesByTerm would produce: matchedText is the CLEAN
        // stripped text, but start/end are ORIGINAL-text positions (3 to 21).
        MatchHighlight highlight = new MatchHighlight(3, 21, "Enjoy Happy", false);

        String html = service.buildHighlightedHtml(original, List.of(highlight));

        // The <mark> must contain the ORIGINAL substring (HTML-escaped), not "Enjoy Happy" —
        // this is what lets an analyst see that the match spanned two HTML blocks.
        assertThat(html).contains("<mark class=\"lex-match\">Enjoy&lt;/p&gt;\n&lt;p&gt;Happy</mark>");
        assertThat(html).contains("Birthday");
    }

    // ── Disclaimer-aware rendering ──────────────────────────────────────────────

    @Test
    @DisplayName("a disclaimer-flagged match renders with the lex-match-disclaimer CSS class, not lex-match")
    void buildHighlightedHtml_disclaimerMatch_usesDisclaimerCssClass() {
        String message = "please keep this confidential";
        MatchHighlight disclaimerMatch = new MatchHighlight(18, 30, "confidential", true);

        String html = service.buildHighlightedHtml(message, List.of(disclaimerMatch));

        assertThat(html).contains("<mark class=\"lex-match-disclaimer\"");
        assertThat(html).doesNotContain("<mark class=\"lex-match\">"); // must not ALSO get the regular class
    }

    @Test
    @DisplayName("a regular (non-disclaimer) match still renders with the plain lex-match class")
    void buildHighlightedHtml_regularMatch_usesPlainCssClass() {
        String message = "insider trading";
        MatchHighlight regularMatch = new MatchHighlight(0, 7, "insider", false);

        String html = service.buildHighlightedHtml(message, List.of(regularMatch));

        assertThat(html).contains("<mark class=\"lex-match\">");
        assertThat(html).doesNotContain("lex-match-disclaimer");
    }

    @Test
    @DisplayName("merging two overlapping disclaimer-only matches stays disclaimer-only")
    void mergeOverlapping_bothDisclaimer_staysDisclaimer() {
        String message = "this message is strictly confidential and private";
        MatchHighlight a = new MatchHighlight(19, 31, "confidential", true);
        MatchHighlight b = new MatchHighlight(25, 40, "al and private", true);

        String html = service.buildHighlightedHtml(message, List.of(a, b));

        assertThat(html).contains("<mark class=\"lex-match-disclaimer\"");
        assertThat(html).doesNotContain("<mark class=\"lex-match\">");
    }

    @Test
    @DisplayName("merging a disclaimer match with an overlapping REAL match wins toward alerting — " +
                 "never let a boundary overlap hide a genuine alert")
    void mergeOverlapping_oneRealOneDisclaimer_mergedIsNotDisclaimer() {
        String message = "this message is strictly confidential and private";
        MatchHighlight disclaimerMatch = new MatchHighlight(19, 31, "confidential", true);
        MatchHighlight realMatch = new MatchHighlight(25, 40, "al and private", false);

        String html = service.buildHighlightedHtml(message, List.of(disclaimerMatch, realMatch));

        // The merged span must be treated as a REAL alert-worthy match, not muted.
        assertThat(html).contains("<mark class=\"lex-match\">");
        assertThat(html).doesNotContain("lex-match-disclaimer");
    }

    @Test
    @DisplayName("REGRESSION: merging two overlapping matches on a large HTML-heavy message does not throw " +
                 "StringIndexOutOfBoundsException — the reported 'Range [x, y) out of bounds' crash")
    void mergeOverlapping_largeHtmlHeavyMessage_doesNotThrow() {
        // 'current' spans almost the WHOLE original text (as a proximity/AND match's original-text
        // span does when there's substantial HTML markup between its two ends), while 'next' is a
        // SHORT, later, overlapping match with a short stripped-text matchedText. The previous
        // implementation computed a substring start index from ORIGINAL-text position arithmetic
        // (current.endCharIndex() - next.startCharIndex()) and applied it to next.matchedText() — a
        // STRIPPED-text string, in a different, shorter coordinate system. For a long run of HTML
        // markup between the two matches, that index reliably exceeds the stripped string's own
        // length, throwing exactly the "Range [x, y) out of bounds for length y" the report described.
        StringBuilder sb = new StringBuilder("Happy Birthday ");
        for (int i = 0; i < 60; i++) {
            sb.append("<span style=\"color:rgb(61,142,185)\"></span>");
        }
        sb.append("Ishaan Jaitly following our discussion");
        String originalText = sb.toString();

        int ishaanIndex = originalText.indexOf("Ishaan");
        MatchHighlight current = new MatchHighlight(0, originalText.length() - 5, "Happy Birthday", false);
        MatchHighlight next = new MatchHighlight(ishaanIndex, ishaanIndex + 6, "Ishaan", false);

        // The specific arithmetic that used to crash: a large original-text-coordinate gap
        // (60 stripped empty <span></span> tags' worth of markup) versus a short matchedText.
        assertThat(current.endCharIndex() - next.startCharIndex()).isGreaterThan(next.matchedText().length());

        assertThatCode(() -> service.buildHighlightedHtml(originalText, List.of(current, next)))
                .doesNotThrowAnyException();
    }

    // ── Private helper ────────────────────────────────────────────────────────

    private CompiledTerm term(int idx, String pattern, int flags) {
        return new CompiledTerm("t::" + idx, "desc-" + idx, List.of(pattern), null, flags, false, idx);
    }
}
