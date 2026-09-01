package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.model.Models.DisclaimerSpan;
import com.db.macs3.ecomms.spectre.model.Models.MatchHighlight;
import com.db.macs3.ecomms.spectre.model.Models.StrippedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds disclaimer text within a message and determines whether a given
 * lexicon match falls inside a disclaimer region — the mechanism behind the
 * requirement that boilerplate disclaimer text (e.g. the word "confidential"
 * in a standard email footer) must not trigger a false alert, even when the
 * same word also appears meaningfully in the message body.
 *
 * <h2>Detection strategy: exact substring match on stripped text</h2>
 * <p>Each disclaimer text is run through the SAME {@link HtmlStrippingService}
 * used for the message itself (tags removed, whitespace collapsed to single
 * spaces), then searched for as a literal substring within the message's
 * stripped text. This deliberately does not attempt fuzzy/approximate
 * matching — organisations almost always append the exact same boilerplate
 * disclaimer text verbatim, and exact substring matching on already-normalised
 * text is simple, fast, and easy for an analyst to reason about ("does this
 * message contain this exact disclaimer text, ignoring HTML/whitespace
 * differences?"). A disclaimer that varies materially between messages
 * (e.g. templated with a per-message date) will not be found by this method;
 * see the class-level note in the service's Javadoc summary for how to
 * extend this if that becomes a real requirement.
 *
 * <h2>Multiple occurrences and multiple disclaimers</h2>
 * <p>Every non-overlapping occurrence of every supplied disclaimer text is
 * found (not just the first), and every found span is mapped back to
 * ORIGINAL-text coordinates via {@link StrippedMessage#mapSpanToOriginal},
 * exactly like {@link MatchHighlightService} does for lexicon matches — the
 * two use the same coordinate system, which is what makes
 * {@link #isFullyWithinAnyDisclaimer} a simple containment check.
 *
 * <h2>Containment, not overlap</h2>
 * <p>A lexicon match is only treated as disclaimer-only when it falls
 * ENTIRELY within a disclaimer span. A match that starts inside a disclaimer
 * but extends beyond it into real message content is NOT suppressed — the
 * safer choice for a compliance tool is to never let a boundary case hide a
 * genuine alert.
 */
@Service
public class DisclaimerDetectionService {

    private static final Logger log = LoggerFactory.getLogger(DisclaimerDetectionService.class);

    private final HtmlStrippingService htmlStrippingService;

    public DisclaimerDetectionService(HtmlStrippingService htmlStrippingService) {
        this.htmlStrippingService = htmlStrippingService;
    }

    /**
     * Finds every occurrence of every disclaimer text within {@code message},
     * in ORIGINAL-text coordinates.
     *
     * @param message          the message to search, already HTML-stripped
     * @param disclaimerTexts  disclaimer texts supplied by the user (may be empty)
     * @return all detected disclaimer occurrences, in original-text coordinates;
     *         empty list when no disclaimer text was supplied or none was found
     */
    public List<DisclaimerSpan> detectDisclaimers(StrippedMessage message, List<String> disclaimerTexts) {
        if (disclaimerTexts.isEmpty()) {
            return List.of();
        }

        List<DisclaimerSpan> spans = new ArrayList<>();
        String strippedMessageText = message.strippedText();

        for (String disclaimerText : disclaimerTexts) {
            String strippedDisclaimer = htmlStrippingService.strip(disclaimerText).strippedText();
            if (strippedDisclaimer.isBlank()) {
                log.debug("Skipping blank disclaimer text after stripping: '{}'", disclaimerText);
                continue;
            }

            int searchFrom = 0;
            while (searchFrom <= strippedMessageText.length()) {
                int foundAt = strippedMessageText.indexOf(strippedDisclaimer, searchFrom);
                if (foundAt < 0) {
                    break;
                }
                int strippedEnd = foundAt + strippedDisclaimer.length();
                int[] originalSpan = message.mapSpanToOriginal(foundAt, strippedEnd);
                spans.add(new DisclaimerSpan(originalSpan[0], originalSpan[1], disclaimerText));

                log.debug("Disclaimer found: stripped[{},{}] -> original[{},{}]",
                        foundAt, strippedEnd, originalSpan[0], originalSpan[1]);

                searchFrom = strippedEnd; // continue past this occurrence, non-overlapping
            }
        }

        if (!spans.isEmpty()) {
            log.info("Detected {} disclaimer occurrence(s) in message", spans.size());
        }
        return spans;
    }

    /**
     * @return true when {@code match}'s span falls ENTIRELY within at least
     *         one of {@code disclaimerSpans} (containment, not mere overlap —
     *         see class Javadoc).
     */
    public boolean isFullyWithinAnyDisclaimer(MatchHighlight match, List<DisclaimerSpan> disclaimerSpans) {
        for (DisclaimerSpan span : disclaimerSpans) {
            if (match.startCharIndex() >= span.startCharIndex() && match.endCharIndex() <= span.endCharIndex()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rebuilds every {@link MatchHighlight} in {@code matches} with
     * {@link MatchHighlight#inDisclaimer()} set correctly against
     * {@code disclaimerSpans}. {@link MatchHighlightService} itself stays
     * disclaimer-agnostic (its job is UTF-8 byte/char mapping and HTML
     * rendering, not disclaimer business logic) — this is the one place that
     * bridges the two, called by the orchestrator after matches are grouped
     * and before the highlighted HTML is built.
     *
     * @param matches         matches as produced by {@link MatchHighlightService#groupMatchesByTerm}
     *                        (all {@code inDisclaimer=false} at this point)
     * @param disclaimerSpans disclaimer occurrences detected in the same message
     * @return a new list, same size and order, with {@code inDisclaimer} correctly set
     */
    public List<MatchHighlight> applyDisclaimerFlags(List<MatchHighlight> matches, List<DisclaimerSpan> disclaimerSpans) {
        if (disclaimerSpans.isEmpty()) {
            return matches; // nothing to flag — avoid pointless rebuilding
        }
        List<MatchHighlight> flagged = new ArrayList<>(matches.size());
        for (MatchHighlight match : matches) {
            boolean inDisclaimer = isFullyWithinAnyDisclaimer(match, disclaimerSpans);
            flagged.add(new MatchHighlight(match.startCharIndex(), match.endCharIndex(), match.matchedText(), inDisclaimer));
        }
        return flagged;
    }
}
