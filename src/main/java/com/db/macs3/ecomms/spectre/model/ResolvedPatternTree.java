package com.db.macs3.ecomms.spectre.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
     * class Javadoc) and pairs it, side by side, with the compile response's
     * pattern lists to build an evaluable tree.
     *
     * <p>Each side is resolved independently, per the Compile Service's
     * contract ({@code TermCompilationResult} Javadoc):
     * <ul>
     *   <li><b>pattern count == the shape's leaf count for that side</b> — the
     *       side was decomposed into gap-less leaves; zipped positionally, with
     *       NEAR/FOLLOWEDBY distance and order verified by
     *       {@link com.db.macs3.ecomms.spectre.service.ResolvedPatternMatcher};</li>
     *   <li><b>exactly one pattern, although the shape has several leaves</b> — the
     *       side compiled as ONE self-contained pattern with any gap already
     *       embedded in the regex itself (Hyperscan enforced it on the Compile
     *       Service side; Java's regex engine enforces it here). It becomes a
     *       single-leaf chain with no operators.</li>
     *   <li>anything else — a structural mismatch.</li>
     * </ul>
     *
     * @param termId           for error messages only
     * @param resolvedPatterns the exact {@code resolvedPatterns} string from the compile response
     * @param regexPattern     the exact {@code regexPattern} list (required side)
     * @param exclusionRegex   the exact {@code exclusionRegex} list (excluded side); null/empty
     *                         unless the term is AND NOT
     * @throws TermMetadataParseException on any structural mismatch or uncompilable pattern
     */
    static ResolvedPatternTree build(String termId, String resolvedPatterns,
                                     List<String> regexPattern, List<String> exclusionRegex) {
        if (regexPattern == null || regexPattern.isEmpty()) {
            throw new TermMetadataParseException(
                    "Term '" + termId + "' has a resolvedPatterns value but no regexPattern to evaluate.");
        }
        if (resolvedPatterns == null || resolvedPatterns.isBlank()) {
            throw new TermMetadataParseException(
                    "Term '" + termId + "' has regexPattern but no resolvedPatterns value to parse.");
        }
        boolean hasExclusion = exclusionRegex != null && !exclusionRegex.isEmpty();
        ShapeNode shape = parseShape(termId, resolvedPatterns.trim());

        if (shape instanceof ShapeNode.AndNotShape andNot) {
            if (!hasExclusion) {
                throw new TermMetadataParseException(
                        "Term '" + termId + "': resolvedPatterns has an AND NOT side but exclusionRegex is empty.");
            }
            return new AndNot(
                    buildSide(termId, "regexPattern", andNot.required(), regexPattern),
                    buildSide(termId, "exclusionRegex", andNot.excluded(), exclusionRegex));
        }
        if (hasExclusion) {
            throw new TermMetadataParseException(
                    "Term '" + termId + "': exclusionRegex is present but resolvedPatterns has no AND NOT.");
        }
        return buildSide(termId, "regexPattern", shape, regexPattern);
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

    // ── Pairing one side's shape with that side's pattern list ──────────────

    static Chain buildSide(String termId, String field, ShapeNode sideShape, List<String> patterns) {
        ShapeNode.ChainShape shape = (ShapeNode.ChainShape) sideShape;
        if (patterns.size() == shape.leafCount()) {
            List<Pattern> leaves = new ArrayList<>(patterns.size());
            for (String p : patterns) {
                leaves.add(compileLeaf(termId, p));
            }
            return new Chain(leaves, shape.operators(), shape.distances());
        }
        if (patterns.size() == 1) {
            // One self-contained pattern: its NEAR/FOLLOWEDBY gaps live inside the regex itself.
            return new Chain(List.of(compileLeaf(termId, patterns.get(0))), List.of(), List.of());
        }
        throw new TermMetadataParseException(
                "Term '" + termId + "': " + field + " has " + patterns.size() + " pattern(s) but resolvedPatterns' "
                + "shape implies " + shape.leafCount() + " leaf(s) (expected that many, or exactly one).");
    }

    private static Pattern compileLeaf(String termId, String regex) {
        try {
            return Pattern.compile(regex, JAVA_LEAF_FLAGS);
        } catch (PatternSyntaxException e) {
            throw new TermMetadataParseException(
                    "Term '" + termId + "': pattern is not valid for Java regex evaluation: " + e.getDescription());
        }
    }

    /** Thrown when a term's {@code resolvedPatterns} cannot be structurally parsed or zipped. */
    final class TermMetadataParseException extends RuntimeException {
        public TermMetadataParseException(String message) {
            super(message);
        }
    }
}
