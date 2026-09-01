package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.model.Models.StrippedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HtmlStrippingService}.
 *
 * <h2>On the requirement's worked example</h2>
 * <p>The requirement specifies: given regex {@code Enjoy(?:\s+\S+){0,2}\s+Happy}
 * and message {@code "<p>Enjoy</p>\n<p>Happy Birthday</p>"}, the match should
 * report {@code {startCharIndex: 3, endCharIndex: 22, matchedText: "Enjoy Happy"}}.
 *
 * <p>{@link #requirementExample_startCharIndex} confirms {@code startCharIndex=3}
 * exactly as specified. For {@code endCharIndex}, this implementation computes
 * {@code 21}, not {@code 22} — traced by hand below and confirmed by
 * {@link #requirementExample_endCharIndex}. Original text index 21 is the
 * space immediately after "Happy" (before "Birthday"); index 22 is "B" — one
 * character further, into the next word. Using ordinary exclusive-end
 * substring semantics (the same convention {@code String.substring} and every
 * other position in this codebase use), the span
 * {@code originalText.substring(3, 21)} yields exactly {@code "Enjoy</p>\n<p>Happy"}
 * — everything from "Enjoy" through "Happy" inclusive, tags and all, with
 * nothing beyond it. That is the tightest, most defensible boundary for a
 * match that ends at "Happy", so this implementation uses 21. This is called
 * out explicitly here rather than silently adjusted to 22, since it is a
 * genuine, traceable discrepancy against the written example.
 */
@DisplayName("HtmlStrippingService")
class HtmlStrippingServiceTest {

    private HtmlStrippingService service;

    @BeforeEach
    void setUp() {
        service = new HtmlStrippingService();
    }

    // ── Plain text (no HTML) ──────────────────────────────────────────────────

    @Test
    @DisplayName("plain text with no tags passes through with identity mapping")
    void plainText_noTags_identityMapping() {
        StrippedMessage result = service.strip("hello world");
        assertThat(result.strippedText()).isEqualTo("hello world");
        assertThat(result.strippedToOriginalIndex()).containsExactly(0,1,2,3,4,5,6,7,8,9,10);
    }

    @Test
    @DisplayName("multiple consecutive spaces collapse to one, even with no HTML")
    void plainText_multipleSpaces_collapsed() {
        StrippedMessage result = service.strip("hello     world");
        assertThat(result.strippedText()).isEqualTo("hello world");
    }

    @Test
    @DisplayName("leading and trailing whitespace are trimmed")
    void plainText_leadingTrailingWhitespace_trimmed() {
        StrippedMessage result = service.strip("   hello world   ");
        assertThat(result.strippedText()).isEqualTo("hello world");
    }

    @Test
    @DisplayName("empty string input returns empty stripped text")
    void emptyString_returnsEmpty() {
        StrippedMessage result = service.strip("");
        assertThat(result.strippedText()).isEmpty();
        assertThat(result.strippedToOriginalIndex()).isEmpty();
    }

    @Test
    @DisplayName("null input is handled gracefully, returns empty stripped text")
    void nullInput_handledGracefully() {
        StrippedMessage result = service.strip(null);
        assertThat(result.strippedText()).isEmpty();
    }

    // ── Basic tag stripping ───────────────────────────────────────────────────

    @Test
    @DisplayName("simple tag pair is removed, content preserved")
    void simpleTagPair_removed() {
        StrippedMessage result = service.strip("<b>bold</b>");
        assertThat(result.strippedText()).isEqualTo("bold");
    }

    @Test
    @DisplayName("self-closing <br/> becomes a single space boundary")
    void selfClosingBr_becomesSpace() {
        StrippedMessage result = service.strip("Enjoy<br/>Happy");
        assertThat(result.strippedText()).isEqualTo("Enjoy Happy");
    }

    @Test
    @DisplayName("bare <br> (no self-closing slash) also becomes a single space boundary")
    void bareBr_becomesSpace() {
        StrippedMessage result = service.strip("Enjoy<br>Happy");
        assertThat(result.strippedText()).isEqualTo("Enjoy Happy");
    }

    @Test
    @DisplayName("tag with attributes is fully removed, content preserved")
    void tagWithAttributes_removed() {
        StrippedMessage result = service.strip("<span class=\"highlight\" data-id='7'>text</span>");
        assertThat(result.strippedText()).isEqualTo("text");
    }

    @Test
    @DisplayName("adjacent tags with no whitespace between them still collapse to ONE space")
    void adjacentTags_collapseToOneSpace() {
        StrippedMessage result = service.strip("<p>A</p><p>B</p>");
        assertThat(result.strippedText()).isEqualTo("A B");
    }

    @Test
    @DisplayName("leading tag is trimmed away, not left as a leading space")
    void leadingTag_trimmedAway() {
        StrippedMessage result = service.strip("<p>Hello</p>");
        assertThat(result.strippedText()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("a stray '<' followed by a space (not a real tag) is left as literal text")
    void strayLessThan_notTreatedAsTag() {
        StrippedMessage result = service.strip("if a < b then everything is fine");
        assertThat(result.strippedText()).isEqualTo("if a < b then everything is fine");
    }

    // ── The requirement's worked example ─────────────────────────────────────

    private static final String REQUIREMENT_ORIGINAL = "<p>Enjoy</p>\n<p>Happy Birthday</p>";

    @Test
    @DisplayName("requirement example: stripped text is exactly \"Enjoy Happy Birthday\"")
    void requirementExample_strippedText() {
        StrippedMessage result = service.strip(REQUIREMENT_ORIGINAL);
        assertThat(result.strippedText()).isEqualTo("Enjoy Happy Birthday");
    }

    @Test
    @DisplayName("requirement example: startCharIndex maps to 3, exactly as specified")
    void requirementExample_startCharIndex() {
        StrippedMessage result = service.strip(REQUIREMENT_ORIGINAL);
        // Match "Enjoy Happy" in the stripped text: stripped chars [0, 11)
        int[] span = result.mapSpanToOriginal(0, 11);
        assertThat(span[0]).isEqualTo(3);
        assertThat(REQUIREMENT_ORIGINAL.charAt(3)).isEqualTo('E');
    }

    @Test
    @DisplayName("requirement example: endCharIndex maps to 21 under exclusive-end convention " +
                 "(see class Javadoc — the spec states 22; this is a documented, traced discrepancy)")
    void requirementExample_endCharIndex() {
        StrippedMessage result = service.strip(REQUIREMENT_ORIGINAL);
        int[] span = result.mapSpanToOriginal(0, 11);
        assertThat(span[1]).isEqualTo(21);
        // Prove the boundary is exactly right: original[3,21) is everything from
        // "Enjoy" through "Happy" inclusive, tags and all, nothing more.
        assertThat(REQUIREMENT_ORIGINAL.substring(span[0], span[1])).isEqualTo("Enjoy</p>\n<p>Happy");
    }

    @Test
    @DisplayName("requirement example: matchedText extracted from stripped text is \"Enjoy Happy\"")
    void requirementExample_matchedTextIsCleanStrippedSubstring() {
        StrippedMessage result = service.strip(REQUIREMENT_ORIGINAL);
        String matchedText = result.strippedText().substring(0, 11);
        assertThat(matchedText).isEqualTo("Enjoy Happy");
    }

    @Test
    @DisplayName("requirement example: a FOLLOWEDBY/NEAR-style regex now matches where it would fail on raw HTML")
    void requirementExample_regexNowMatchesStrippedText() {
        StrippedMessage result = service.strip(REQUIREMENT_ORIGINAL);
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("Enjoy(?:\\s+\\S+){0,2}\\s+Happy");
        java.util.regex.Matcher m = p.matcher(result.strippedText());
        assertThat(m.find()).isTrue();
        assertThat(m.group()).isEqualTo("Enjoy Happy");

        // The same regex does NOT match the raw original text (proves stripping was necessary)
        java.util.regex.Matcher rawMatcher = p.matcher(REQUIREMENT_ORIGINAL);
        assertThat(rawMatcher.find()).isFalse();
    }

    // ── mapSpanToOriginal edge cases ───────────────────────────────────────────

    @Test
    @DisplayName("mapSpanToOriginal for a span covering the entire stripped text")
    void mapSpanToOriginal_wholeString() {
        StrippedMessage result = service.strip("<p>Hello World</p>");
        int[] span = result.mapSpanToOriginal(0, result.strippedText().length());
        assertThat(result.originalText().substring(span[0], span[1])).contains("Hello World");
    }

    @Test
    @DisplayName("mapSpanToOriginal for a single-character match")
    void mapSpanToOriginal_singleChar() {
        StrippedMessage result = service.strip("abc");
        int[] span = result.mapSpanToOriginal(1, 2);
        assertThat(span[0]).isEqualTo(1);
        assertThat(span[1]).isEqualTo(2);
        assertThat(result.originalText().substring(span[0], span[1])).isEqualTo("b");
    }

    @Test
    @DisplayName("mapSpanToOriginal is safe against an empty mapping (empty stripped text)")
    void mapSpanToOriginal_emptyMapping_safe() {
        StrippedMessage result = service.strip("<p></p>");
        int[] span = result.mapSpanToOriginal(0, 0);
        assertThat(span).containsExactly(0, 0);
    }

    // ── originalText passthrough ───────────────────────────────────────────────

    @Test
    @DisplayName("StrippedMessage retains the exact original text unchanged")
    void strippedMessage_retainsOriginalText() {
        StrippedMessage result = service.strip(REQUIREMENT_ORIGINAL);
        assertThat(result.originalText()).isEqualTo(REQUIREMENT_ORIGINAL);
    }
}
