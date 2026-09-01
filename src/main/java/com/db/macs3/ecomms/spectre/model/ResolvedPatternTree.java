package com.db.macs3.ecomms.spectre.model;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The evaluable form of one term's {@code resolvedPatterns} string — the
 * Lexicon Compile Service's decomposed representation of a NEAR/FOLLOWEDBY
 * proximity chain and/or an AND NOT condition, kept as a left-to-right
 * sequence of {@code regexPattern} leaves joined by the operator text
 * {@code resolvedPatterns} carries. See
 * {@link com.db.macs3.ecomms.spectre.service.ResolvedPatternMatcher} for how
 * a tree is actually evaluated against real message text.
 *
 * <p>Ported from the equivalent class in the sibling {@code lexicon-scan-engine}
 * project (which faced the identical problem first: the Compile Service's
 * decomposed leaves carry no order/distance information of their own, so the
 * real proximity/AND-NOT condition must be re-verified in Java against the
 * leaves' own regex text). This project has no {@code .hdb}/QUIET-expression
 * angle the Scan Engine's equivalent class has to account for — this
 * project's terms are never compiled into Hyperscan at all any more (see
 * {@link com.db.macs3.ecomms.spectre.service.LexiconScanOrchestrator} class
 * Javadoc) — so this class is a direct, simplified port of that project's
 * {@code ResolvedPatternTree}, dropping its Spark/serialization-specific
 * concerns.
 *
 * <h2>Shape-then-zip, not text-slicing</h2>
 * <p>The Compile Service's own reference implementation
 * ({@code TokenProximityMatcher}/{@code ResolvedPatternMatcher} in its test
 * tree) slices each leaf's own regex text directly out of the
 * {@code resolvedPatterns} string via top-level text scanning — safe only so
 * long as a leaf's regex text never happens to contain the literal substring
 * {@code " NEAR{5} "} etc. This class instead uses that same paren-depth-aware
 * scanning ONLY to discover the tree's SHAPE (leaf count per chain segment,
 * operator+distance sequence, the AND NOT split point), then zips that shape
 * against the compile response's own {@code regexPattern} list positionally
 * — no leaf regex text is ever sliced out of {@code resolvedPatterns} itself,
 * and a leaf-count disagreement between the two fields becomes a structural
 * parse error (the zip cursor running out, or having leftovers) rather than
 * a silent mismatch.
 */
public sealed interface ResolvedPatternTree {

    /**
     * A (possibly length-1, i.e. no proximity operator at all) sequence of
     * leaves connected by NEAR/FOLLOWEDBY.
     *
     * @param leaves    one compiled, case-insensitive pattern per leaf, in left-to-right term order
     * @param operators {@code "NEAR"} or {@code "FOLLOWEDBY"} between consecutive leaves —
     *                  {@code operators.size() == leaves.size() - 1}
     * @param distances the raw distance for each operator — same size as {@code operators}
     */
    record Chain(List<Pattern> leaves, List<String> operators, List<Integer> distances) implements ResolvedPatternTree {
    }

    /**
     * @param required the required side — always a plain {@code Chain}, never a further {@code AndNot}
     *                 (AND NOT is never nested, per the Compile Service's own documented contract)
     * @param excluded the excluded side — always a plain {@code Chain}
     */
    record AndNot(ResolvedPatternTree required, ResolvedPatternTree excluded) implements ResolvedPatternTree {
    }

    int JAVA_LEAF_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    /**
     * Parses {@code resolvedPatterns}'s SHAPE (never its leaf text — see
     * class Javadoc) and zips it against {@code regexPatternLeaves} in
     * left-to-right order to build an evaluable tree.
     *
     * @param termId             for error messages only
     * @param resolvedPatterns   the exact {@code resolvedPatterns} string from the compile response
     * @param regexPatternLeaves the exact {@code regexPattern} list — must contain exactly as many
     *                           entries as {@code resolvedPatterns}'s shape has leaves, in the same order
     * @throws TermMetadataParseException on any structural mismatch
     */
    static ResolvedPatternTree build(String termId, String resolvedPatterns, List<String> regexPatternLeaves) {
        if (regexPatternLeaves == null || regexPatternLeaves.isEmpty()) {
            throw new TermMetadataParseException(
                    "Term '" + termId + "' has a resolvedPatterns value but no regexPattern leaves to zip it against.");
        }
        if (resolvedPatterns == null || resolvedPatterns.isBlank()) {
            throw new TermMetadataParseException(
                    "Term '" + termId + "' has regexPattern leaves but no resolvedPatterns value to parse.");
        }
        ShapeNode shape = parseShape(termId, resolvedPatterns.trim());
        Iterator<String> cursor = regexPatternLeaves.iterator();
        ResolvedPatternTree tree = zip(termId, shape, cursor);
        if (cursor.hasNext()) {
            throw new TermMetadataParseException(
                    "Term '" + termId + "': regexPattern has more leaves than resolvedPatterns' shape implies ("
                    + regexPatternLeaves.size() + " provided).");
        }
        return tree;
    }

    // ── Shape discovery (adapted from the Compile Service's reference matcher — shape only, no leaf text) ──

    sealed interface ShapeNode {
        record ChainShape(int leafCount, List<String> operators, List<Integer> distances) implements ShapeNode {
        }

        record AndNotShape(ShapeNode required, ShapeNode excluded) implements ShapeNode {
        }
    }

    String AND_NOT_MARKER = " AND NOT (";
    Pattern PROXIMITY_KEYWORD = Pattern.compile(" (NEAR|FOLLOWEDBY)\\{(\\d+)\\} ");

    static ShapeNode parseShape(String termId, String text) {
        int markerAt = findTopLevel(text, AND_NOT_MARKER);
        if (markerAt < 0) {
            return parseChainShape(text);
        }
        String requiredText = text.substring(0, markerAt);
        int openParenAt = markerAt + AND_NOT_MARKER.length() - 1;
        int closeParenAt = matchingCloseParen(termId, text, openParenAt);
        String excludedText = text.substring(openParenAt + 1, closeParenAt);
        return new ShapeNode.AndNotShape(parseChainShape(requiredText), parseChainShape(excludedText));
    }

    static ShapeNode.ChainShape parseChainShape(String text) {
        List<String> operators = new ArrayList<>();
        List<Integer> distances = new ArrayList<>();

        int depth = 0;
        int leafCount = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (depth == 0) {
                Matcher m = PROXIMITY_KEYWORD.matcher(text);
                m.region(i, text.length());
                if (m.lookingAt()) {
                    leafCount++;
                    operators.add(m.group(1));
                    distances.add(Integer.parseInt(m.group(2)));
                    i = m.end();
                    continue;
                }
            }
            i++;
        }
        leafCount++; // the final segment after the last operator (or the only segment, if none)
        return new ShapeNode.ChainShape(leafCount, operators, distances);
    }

    /** First TOP-level (paren-depth 0) occurrence of {@code marker}, or -1. */
    static int findTopLevel(String text, String marker) {
        int depth = 0;
        for (int i = 0; i <= text.length() - marker.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (depth == 0 && text.startsWith(marker, i)) {
                return i;
            }
        }
        return -1;
    }

    static int matchingCloseParen(String termId, String text, int openParenAt) {
        int depth = 0;
        for (int i = openParenAt; i < text.length(); i++) {
            if (text.charAt(i) == '(') {
                depth++;
            } else if (text.charAt(i) == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new TermMetadataParseException(
                "Term '" + termId + "' has unbalanced parentheses in resolvedPatterns: " + text);
    }

    // ── Zipping the discovered shape against regexPattern's leaves ──────────

    static ResolvedPatternTree zip(String termId, ShapeNode shape, Iterator<String> cursor) {
        if (shape instanceof ShapeNode.AndNotShape andNot) {
            return new AndNot(
                    zip(termId, andNot.required(), cursor),
                    zip(termId, andNot.excluded(), cursor));
        }
        ShapeNode.ChainShape chainShape = (ShapeNode.ChainShape) shape;
        List<Pattern> leaves = new ArrayList<>(chainShape.leafCount());
        for (int i = 0; i < chainShape.leafCount(); i++) {
            if (!cursor.hasNext()) {
                throw new TermMetadataParseException(
                        "Term '" + termId + "': resolvedPatterns' shape implies more leaves than regexPattern provides.");
            }
            leaves.add(Pattern.compile(cursor.next(), JAVA_LEAF_FLAGS));
        }
        return new Chain(leaves, chainShape.operators(), chainShape.distances());
    }

    /** Thrown when a term's {@code resolvedPatterns} cannot be structurally parsed or zipped. */
    final class TermMetadataParseException extends RuntimeException {
        public TermMetadataParseException(String message) {
            super(message);
        }
    }
}
