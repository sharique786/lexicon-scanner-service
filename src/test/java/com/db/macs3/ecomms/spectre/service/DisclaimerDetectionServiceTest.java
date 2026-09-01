package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.model.Models.DisclaimerSpan;
import com.db.macs3.ecomms.spectre.model.Models.MatchHighlight;
import com.db.macs3.ecomms.spectre.model.Models.StrippedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DisclaimerDetectionService}.
 */
@DisplayName("DisclaimerDetectionService")
class DisclaimerDetectionServiceTest {

    private DisclaimerDetectionService service;
    private HtmlStrippingService htmlStrippingService;

    @BeforeEach
    void setUp() {
        htmlStrippingService = new HtmlStrippingService();
        service = new DisclaimerDetectionService(htmlStrippingService);
    }

    private StrippedMessage strip(String text) {
        return htmlStrippingService.strip(text);
    }

    // ── detectDisclaimers ────────────────────────────────────────────────────────

    @Test
    @DisplayName("finds an exact disclaimer occurrence and maps it to original-text coordinates")
    void detectsSimpleDisclaimer() {
        String message = "Buy now.\n\nThis email is confidential and privileged.";
        StrippedMessage stripped = strip(message);

        List<DisclaimerSpan> spans = service.detectDisclaimers(stripped,
                List.of("This email is confidential and privileged."));

        assertThat(spans).hasSize(1);
        DisclaimerSpan span = spans.get(0);
        assertThat(message.substring(span.startCharIndex(), span.endCharIndex()))
                .isEqualTo("This email is confidential and privileged.");
    }

    @Test
    @DisplayName("no disclaimer text supplied — returns empty list, no exception")
    void noDisclaimersSupplied_returnsEmpty() {
        StrippedMessage stripped = strip("insider trading is illegal");
        assertThat(service.detectDisclaimers(stripped, List.of())).isEmpty();
    }

    @Test
    @DisplayName("disclaimer text not present in the message — returns empty list")
    void disclaimerNotPresent_returnsEmpty() {
        StrippedMessage stripped = strip("insider trading is illegal");
        assertThat(service.detectDisclaimers(stripped, List.of("This is a totally different disclaimer"))).isEmpty();
    }

    @Test
    @DisplayName("HTML-formatted disclaimer in an HTML-formatted message is still found " +
                 "(both normalised the same way before matching)")
    void htmlFormattedDisclaimerStillFound() {
        String message = "<p>Buy now.</p><div>This email is <b>confidential</b> and privileged.</div>";
        StrippedMessage stripped = strip(message);

        // Supplied disclaimer text has NO html -- it should still match, because
        // detection strips HTML and collapses whitespace on BOTH sides before comparing.
        List<DisclaimerSpan> spans = service.detectDisclaimers(stripped,
                List.of("This email is confidential and privileged."));

        assertThat(spans).hasSize(1);
    }

    @Test
    @DisplayName("multiple distinct disclaimers are all detected")
    void multipleDisclaimersAllDetected() {
        String message = "Body text. Disclaimer A here. More text. Disclaimer B here.";
        StrippedMessage stripped = strip(message);

        List<DisclaimerSpan> spans = service.detectDisclaimers(stripped,
                List.of("Disclaimer A here.", "Disclaimer B here."));

        assertThat(spans).hasSize(2);
    }

    @Test
    @DisplayName("multiple non-overlapping occurrences of the SAME disclaimer are all found")
    void multipleOccurrencesOfSameDisclaimer() {
        String message = "CONFIDENTIAL. Body text. CONFIDENTIAL.";
        StrippedMessage stripped = strip(message);

        List<DisclaimerSpan> spans = service.detectDisclaimers(stripped, List.of("CONFIDENTIAL."));

        assertThat(spans).hasSize(2);
    }

    @Test
    @DisplayName("a blank disclaimer entry (empty after stripping) is skipped, not an infinite loop")
    void blankDisclaimerSkipped() {
        StrippedMessage stripped = strip("some message text");
        // Must complete quickly and return no spans for the blank entry.
        List<DisclaimerSpan> spans = service.detectDisclaimers(stripped, List.of("   ", "<p></p>"));
        assertThat(spans).isEmpty();
    }

    // ── isFullyWithinAnyDisclaimer ────────────────────────────────────────────────

    @Test
    @DisplayName("a match fully inside a disclaimer span is flagged")
    void matchFullyInsideDisclaimer_isFlagged() {
        DisclaimerSpan span = new DisclaimerSpan(10, 50, "disclaimer text");
        MatchHighlight match = new MatchHighlight(15, 25, "confidential", false);
        assertThat(service.isFullyWithinAnyDisclaimer(match, List.of(span))).isTrue();
    }

    @Test
    @DisplayName("a match entirely outside any disclaimer span is NOT flagged")
    void matchOutsideDisclaimer_notFlagged() {
        DisclaimerSpan span = new DisclaimerSpan(10, 50, "disclaimer text");
        MatchHighlight match = new MatchHighlight(60, 70, "insider", false);
        assertThat(service.isFullyWithinAnyDisclaimer(match, List.of(span))).isFalse();
    }

    @Test
    @DisplayName("a match that only PARTIALLY overlaps a disclaimer (extends beyond it) is NOT flagged — " +
                 "containment, not overlap, per the 'never hide a genuine alert' policy")
    void matchPartiallyOverlappingDisclaimer_notFlagged() {
        DisclaimerSpan span = new DisclaimerSpan(10, 50, "disclaimer text");
        MatchHighlight match = new MatchHighlight(45, 60, "spans the boundary", false); // starts inside, ends outside
        assertThat(service.isFullyWithinAnyDisclaimer(match, List.of(span))).isFalse();
    }

    @Test
    @DisplayName("a match exactly matching the disclaimer span boundaries is flagged (inclusive/exclusive edges)")
    void matchExactlyMatchesDisclaimerBoundaries_isFlagged() {
        DisclaimerSpan span = new DisclaimerSpan(10, 50, "disclaimer text");
        MatchHighlight match = new MatchHighlight(10, 50, "the whole disclaimer", false);
        assertThat(service.isFullyWithinAnyDisclaimer(match, List.of(span))).isTrue();
    }

    // ── applyDisclaimerFlags ─────────────────────────────────────────────────────

    @Test
    @DisplayName("applyDisclaimerFlags correctly rebuilds matches with the right flags, preserving order and count")
    void applyDisclaimerFlags_rebuildsCorrectly() {
        List<MatchHighlight> matches = List.of(
                new MatchHighlight(15, 25, "in disclaimer", false),   // will become true
                new MatchHighlight(100, 110, "in body", false));       // stays false
        List<DisclaimerSpan> spans = List.of(new DisclaimerSpan(10, 50, "disclaimer"));

        List<MatchHighlight> flagged = service.applyDisclaimerFlags(matches, spans);

        assertThat(flagged).hasSize(2);
        assertThat(flagged.get(0).inDisclaimer()).isTrue();
        assertThat(flagged.get(0).countsTowardAlert()).isFalse();
        assertThat(flagged.get(1).inDisclaimer()).isFalse();
        assertThat(flagged.get(1).countsTowardAlert()).isTrue();
        // Original text/positions must be preserved exactly.
        assertThat(flagged.get(0).matchedText()).isEqualTo("in disclaimer");
        assertThat(flagged.get(1).startCharIndex()).isEqualTo(100);
    }

    @Test
    @DisplayName("applyDisclaimerFlags with no disclaimer spans returns the same list unchanged")
    void applyDisclaimerFlags_noSpans_unchanged() {
        List<MatchHighlight> matches = List.of(new MatchHighlight(0, 5, "hello", false));
        List<MatchHighlight> result = service.applyDisclaimerFlags(matches, List.of());
        assertThat(result).isEqualTo(matches);
    }
}
