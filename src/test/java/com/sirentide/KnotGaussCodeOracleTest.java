package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.ir.Knot;
import com.sirentide.layout.Group;
import com.sirentide.layout.KnotDiagramLayout;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.Path;
import com.sirentide.layout.Shape;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// The KNOT semantic ORACLE (the snake matching-count / dynkin Cartan analogue): reconstruct the GAUSS
/// CODE from the EMITTED GEOMETRY and assert it equals the knot's KNOWN code. This is the discriminator
/// that proves the diagram is a valid double-point knot projection with the correct over/under
/// structure — it FAILS on a missing/extra crossing, a flipped over/under (a moved gap), or a
/// non-closed curve.
///
/// HOW THE RECONSTRUCTION READS GEOMETRY (non-circular w.r.t. over/under). The layout emits one open
/// stroked strand ARC per maximal run between under-gaps, in traversal (emit) order. Walking the arcs:
///   * each arc passes THROUGH exactly one crossing (a strand vertex lands within {@link #OVER_EPS} px
///     of that crossing's centre) — the OVER pass; and
///   * each arc's far END stops a GAP short of the next crossing (its last vertex is {@code >
///     }{@link #GAP_MIN} px from any crossing centre) — the UNDER pass.
/// The over/under of every encounter is thus DERIVED from whether the strand reaches the crossing or
/// stops short — NOT from any stored flag. Flip one crossing's under pass in the layout table and a
/// crossing gets two pass-throughs while another gets two gaps: the reconstructed code (and the
/// alternating property) breaks. {@link #reconstruct} is fed the arcs + the crossing CENTRES only
/// (positions, no over/under), exactly as the snake oracle recomputes matchings from raw tile geometry.
///
/// The KNOWN codes are the canonical alternating Gauss codes these built-in traversals realize:
/// trefoil O1U2O3U1O2U3; figure-eight (4₁) O1U4O2U1O3U2O4U3; unknot the empty code.
class KnotGaussCodeOracleTest {

    /// A strand vertex within this many px of a crossing centre counts as passing THROUGH it (OVER).
    /// The sampling density puts the closest sample ~1px from the exact double point.
    private static final double OVER_EPS = 4.0;
    /// An arc endpoint FARTHER than this from every crossing centre is a genuine GAP (UNDER pass), not
    /// a pass-through. The gap is ~15–22px; the over pass is ~1px — a wide, unambiguous separation.
    private static final double GAP_MIN = 6.0;

    // -- the KNOWN Gauss codes (canonical, alternating) --------------------------------------------

    private static final String TREFOIL_CODE = "O1U2O3U1O2U3";
    /// The figure-eight (4₁) canonical alternating Gauss code its hand-built embedding realizes.
    private static final String FIGURE8_CODE = "O1U4O2U1O3U2O4U3";

    // -- THE ORACLE --------------------------------------------------------------------------------

    @Test
    void trefoilReconstructsItsCanonicalGaussCode() {
        assertEquals(TREFOIL_CODE, reconstruct(Knot.TREFOIL),
            "the trefoil's emitted geometry must reconstruct the canonical Gauss code O1U2O3U1O2U3");
    }

    @Test
    void unknotHasTheEmptyGaussCode() {
        assertEquals("", reconstruct(Knot.UNKNOT), "the unknot (0 crossings) reconstructs the empty code");
    }

    @Test
    void figure8ReconstructsItsCanonicalGaussCode() {
        assertEquals(FIGURE8_CODE, reconstruct(Knot.FIGURE8),
            "the figure-eight's emitted geometry must reconstruct the canonical Gauss code "
                + "O1U4O2U1O3U2O4U3 (a valid alternating 4₁ realization) — proving the hand-built "
                + "embedding is a genuine 4-crossing figure-eight, not a 4-kink unknot");
    }

    // -- independent cross-checks (count / double-visit / alternating) -----------------------------

    @Test
    void crossingCountMatchesTheKnotType() {
        assertEquals(0, KnotDiagramLayout.crossingCentresPx(Knot.UNKNOT).size(), "unknot: 0 crossings");
        assertEquals(3, KnotDiagramLayout.crossingCentresPx(Knot.TREFOIL).size(), "trefoil: 3 crossings");
        assertEquals(4, KnotDiagramLayout.crossingCentresPx(Knot.FIGURE8).size(), "figure-eight: 4 crossings");
    }

    @Test
    void everyCrossingIdIsVisitedExactlyTwice() {
        for (String type : List.of(Knot.TREFOIL, Knot.FIGURE8)) {
            List<int[]> visits = visits(type);   // {id, over?1:0} in traversal order
            int n = KnotDiagramLayout.crossingCentresPx(type).size();
            int[] count = new int[n + 1];
            for (int[] v : visits) {
                count[v[0]]++;
            }
            for (int id = 1; id <= n; id++) {
                assertEquals(2, count[id],
                    type + ": crossing " + id + " must be a valid DOUBLE point (visited exactly twice)");
            }
        }
    }

    @Test
    void overUnderStrictlyAlternatesAlongTheTraversal() {
        for (String type : List.of(Knot.TREFOIL, Knot.FIGURE8)) {
            List<int[]> visits = visits(type);
            for (int i = 0; i < visits.size(); i++) {
                assertNotEquals(visits.get(i)[1], visits.get((i + 1) % visits.size())[1],
                    type + ": over/under must strictly alternate along the traversal (alternating knot) "
                        + "at visit " + i);
            }
        }
    }

    // -- NON-VACUITY: a mutated (over/under-flipped) diagram must break the oracle ------------------

    /// Simulate a FLIPPED crossing by CLOSING one of the trefoil's gaps (append the gapped crossing
    /// centre to the two arcs that stop short of it, so that crossing now reads as passed-THROUGH on
    /// BOTH passes — an invalid double point / a flipped over/under). The reconstruction must no longer
    /// match the canonical code — proving the oracle is a real discriminator, not a tautology.
    @Test
    void closingAGapBreaksTheReconstructedCode() {
        List<double[][]> arcs = arcs(Knot.TREFOIL);
        List<double[]> cross = KnotDiagramLayout.crossingCentresPx(Knot.TREFOIL);
        // Sanity: the honest arcs reconstruct the canonical code.
        assertEquals(TREFOIL_CODE, reconstruct(arcs, cross), "baseline honest reconstruction");

        // Pick crossing id 2 and pull BOTH arc endpoints that gap around it onto its exact centre —
        // i.e. fill the gap (the under strand now reaches the crossing on its second pass too).
        double[] c2 = cross.stream().filter(c -> (int) c[0] == 2).findFirst().orElseThrow();
        double[] c2xy = {c2[1], c2[2]};
        List<double[][]> mutated = new ArrayList<>();
        for (double[][] arc : arcs) {
            double[][] m = arc;
            // If this arc's END gaps around c2 (c2 is the nearest crossing to its last vertex), extend
            // it THROUGH c2 — filling the gap so c2 now reads as passed-through on that pass too.
            if (nearestCross(arc[arc.length - 1], cross) == 2) {
                m = append(m, c2xy);
            }
            if (nearestCross(arc[0], cross) == 2) {
                m = prepend(c2xy, m);
            }
            mutated.add(m);
        }
        String broken = reconstructOrError(mutated, cross);
        assertNotEquals(TREFOIL_CODE, broken,
            "closing crossing 2's gap (a flipped over/under) MUST change the reconstructed code — got "
                + broken);
    }

    // -- STRUCTURE discriminators (reviews 370 + 371): a broken/non-contiguous strand must fail -------

    /// Lattice 370: change the first interior `L` of a real trefoil arc to a second `M` (a pen-lift
    /// that visibly splits the strand). RED-ON-OLD: the folded parser merged the split into one vertex
    /// list, so the whole diagram still reconstructed O1U2O3U1O2U3. GREEN-ON-NEW: the structure-
    /// validating parser fails closed on the second M.
    @Test
    void anInteriorPenLiftSplittingAnArcIsRejected() {
        String arc = rawArcStrings(Knot.TREFOIL).stream()
            .filter(s -> countCommand(s, 'L') >= 2).findFirst()
            .orElseThrow(() -> new AssertionError("expected a trefoil arc with interior L commands"));
        String split = splitFirstInteriorL(arc);
        assertNotEquals(arc, split, "the mutation must actually change the arc's d string");
        assertEquals(2, countCommand(split, 'M'), "the split introduces exactly one extra M (pen-lift)");
        // RED-ON-OLD: the retired parser folded the pen-lift into one contiguous vertex list (no throw).
        assertDoesNotThrow(() -> legacyUndifferentiatedParse(split),
            "the retired M/L-agnostic parser silently merged the split — the false-green");
        // GREEN-ON-NEW: the structure-validating parser rejects the second M as a broken curve.
        AssertionError err = assertThrows(AssertionError.class, () -> parse(split),
            "a mid-arc pen-lift (second M) must be rejected, not folded into one arc");
        assertTrue(err.getMessage().contains("single contiguous") || err.getMessage().contains("second M"),
            "the rejection names the broken-curve cause: " + err.getMessage());
    }

    /// Confluence 371: append a DETACHED stray subpath (`M … L …` far from the curve) to a real trefoil
    /// arc's d string — a floating fragment. RED-ON-OLD: the folded parser merged the fragment into the
    /// arc's vertex list, so a visibly broken diagram reconstructed clean. GREEN-ON-NEW: rejected.
    @Test
    void aDetachedStraySubpathMergedIntoAnArcIsRejected() {
        String arc = rawArcStrings(Knot.TREFOIL).get(0);
        String withStray = arc + " M 9999 9999 L 9998 9998";   // a fragment far from the knot
        assertEquals(2, countCommand(withStray, 'M'), "the stray subpath adds a second M");
        // RED-ON-OLD: the retired parser folded the floating fragment into the containing arc (no throw).
        assertDoesNotThrow(() -> legacyUndifferentiatedParse(withStray),
            "the retired parser merged the detached fragment — the false-green");
        // GREEN-ON-NEW: the structure-validating parser rejects the second M.
        assertThrows(AssertionError.class, () -> parse(withStray),
            "a detached stray subpath (second M) must be rejected, not merged into the containing arc");
    }

    /// Lattice sir381: change the first interior absolute `L` of a real trefoil arc to relative `l`. A
    /// browser reads the SAME numbers relative to the current point — a materially different / broken
    /// strand. RED-ON-OLD: the case-insensitive parser uppercased `l` back to `L` and accepted the
    /// original absolute geometry (a browser-visible mutation false-greened). GREEN-ON-NEW: the
    /// case-sensitive parser rejects the relative command, never reinterpreting it as absolute.
    @Test
    void aRelativeLineCommandMustNotBeReinterpretedAsAbsolute() {
        String arc = rawArcStrings(Knot.TREFOIL).stream()
            .filter(s -> countCommand(s, 'L') >= 2).findFirst()
            .orElseThrow(() -> new AssertionError("expected a trefoil arc with interior L commands"));
        String relative = relativizeFirstInteriorL(arc);
        assertNotEquals(arc, relative, "the mutation must change an interior L to l");
        // RED-ON-OLD: the retired case-insensitive parser folded relative l back to absolute L (no throw).
        assertDoesNotThrow(() -> legacyCaseInsensitiveParse(relative),
            "the retired case-insensitive parser read relative l as absolute L — the false-green");
        // GREEN-ON-NEW: case-sensitive parse rejects the relative command.
        AssertionError err = assertThrows(AssertionError.class, () -> parse(relative),
            "a relative line command (lowercase l) must be rejected, not read as absolute geometry");
        assertTrue(err.getMessage().contains("relative") || err.getMessage().contains("outside the emitted"),
            "the rejection names the relative/out-of-grammar cause: " + err.getMessage());
    }

    /// Lattice sir388: insert a `Z` (closepath) before an interior `L` of a real trefoil arc. In SVG the
    /// mid-arc close draws back to the subpath start and resets the current point, so the following line
    /// sequence is materially different. RED-ON-OLD: the token-by-token parser skipped the mid-arc Z and
    /// accepted the original vertex list. GREEN-ON-NEW: the strict grammar accepts `Z` ONLY as the
    /// terminal token, so a mid-arc Z fails closed.
    @Test
    void aMidArcCloseCommandMustNotBeAcceptedAsTerminal() {
        String arc = rawArcStrings(Knot.TREFOIL).stream()
            .filter(s -> countCommand(s, 'L') >= 2).findFirst()
            .orElseThrow(() -> new AssertionError("expected a trefoil arc with interior L commands"));
        String midZ = insertMidArcZ(arc);
        assertNotEquals(arc, midZ, "the mutation must insert a mid-arc Z");
        // RED-ON-OLD: the retired lenient-Z parser skipped the mid-arc close and accepted the geometry.
        assertDoesNotThrow(() -> legacyLenientZParse(midZ),
            "the retired parser skipped the mid-arc Z and accepted the original vertices — the false-green");
        // GREEN-ON-NEW: the strict grammar rejects a non-terminal Z.
        AssertionError err = assertThrows(AssertionError.class, () -> parse(midZ),
            "a mid-arc Z (closepath resets the current point) must fail closed, not be silently ignored");
        assertTrue(err.getMessage().contains("Z") || err.getMessage().contains("final token"),
            "the rejection names the mid-arc-Z cause: " + err.getMessage());
    }

    /// Lattice sir393: replace a real trefoil arc's first decimal coordinate with its Java HEXADECIMAL-
    /// float spelling ({@link Double#toHexString}). {@code Double.parseDouble} round-trips it to the
    /// EXACT same double, so a raw-parseDouble oracle reconstructs the unchanged vertices and preserves
    /// the canonical Gauss code — a false-green. But SVG path-number syntax has no hex float, so a real
    /// browser draws that path with total length 0 (verified by Lattice's BrewShot probe). RED-ON-OLD:
    /// the retired raw-coordinate parser accepts the hex token. GREEN-ON-NEW: the SVG-number-validating
    /// parser rejects it before conversion.
    @Test
    void aHexadecimalCoordinateMustNotBeAcceptedAsDecimal() {
        String arc = rawArcStrings(Knot.TREFOIL).get(0);
        String hex = hexifyFirstCoordinate(arc);
        assertNotEquals(arc, hex, "the mutation must change the first coordinate to a hex-float spelling");
        assertTrue(hex.contains("0x"), "the mutated coordinate is a Java hexadecimal-float literal: " + hex);
        // The hex spelling parses to the SAME double a browser-invalid string maps to under parseDouble.
        assertEquals(firstCoordinate(arc), Double.parseDouble(firstHexToken(hex)),
            "Double.parseDouble round-trips the hex spelling to the exact original coordinate");
        // RED-ON-OLD: the retired raw-coordinate parser accepts the hex token (Double.parseDouble reads it).
        assertDoesNotThrow(() -> legacyRawCoordinateParse(hex),
            "the retired raw-coordinate parser accepted the Java hex float — the false-green");
        // GREEN-ON-NEW: the SVG-number-validating parser rejects a non-decimal coordinate.
        AssertionError err = assertThrows(AssertionError.class, () -> parse(hex),
            "a Java hexadecimal-float coordinate (0x…p…) a browser cannot draw must fail closed");
        assertTrue(err.getMessage().contains("SVG decimal") || err.getMessage().contains("hexadecimal"),
            "the rejection names the SVG-number cause: " + err.getMessage());
    }

    /// Lattice sir401: replace the space before an interior `L` with a U+000B VERTICAL TAB. Java's `\s`
    /// (the retired `split("\\s+")` tokenizer) SPLITS on U+000B, so the path tokenizes cleanly and the
    /// oracle accepts it — but SVG path whitespace is only U+0020/09/0A/0D, so a real browser does NOT
    /// split on VT and renders the path zero-length (Lattice's Chrome `getTotalLength()==0` discriminator).
    /// RED-ON-OLD: the Java-whitespace tokenizer accepts it. GREEN-ON-NEW: the SVG-path-alphabet guard
    /// rejects U+000B before tokenizing.
    @Test
    void aVerticalTabSeparatorMustNotBeAcceptedAsWhitespace() {
        String vt = "M 0 0" + '\u000B' + "L 100 0";
        // Sanity: Java's \s+ tokenizer really does split on U+000B (yields a clean 6-token M/L path).
        assertEquals(6, vt.trim().split("\\s+").length,
            "Java \\s splits on U+000B, so the retired tokenizer read it as a separator");
        // RED-ON-OLD: the retired Java-whitespace tokenizer accepted the VT-separated path (no throw).
        assertDoesNotThrow(() -> legacyJavaWhitespaceParse(vt),
            "the retired Java-whitespace tokenizer accepted the U+000B separator — the false-green");
        // GREEN-ON-NEW: the SVG-path-alphabet guard rejects U+000B (not SVG whitespace).
        AssertionError err = assertThrows(AssertionError.class, () -> parse(vt),
            "a U+000B separator a browser will not split on must fail closed");
        assertTrue(err.getMessage().contains("whitespace") || err.getMessage().contains("000B"),
            "the rejection names the SVG-whitespace cause: " + err.getMessage());
        // A U+000C FORM FEED separator is the same class and likewise rejected.
        assertThrows(AssertionError.class, () -> parse("M 0 0" + '\u000C' + "L 100 0"),
            "a U+000C form-feed separator must also fail closed");
    }

    // -- reconstruction (geometry only) ------------------------------------------------------------

    /// The reconstructed Gauss code string ("O1U2…") for a built-in knot, from its EMITTED geometry.
    private static String reconstruct(String type) {
        return reconstruct(arcs(type), KnotDiagramLayout.crossingCentresPx(type));
    }

    /// Reconstruct the code from arcs (in emit order) + crossing centres. Per arc: the OVER crossing is
    /// the one an interior vertex reaches (< OVER_EPS); the UNDER crossing is the one the arc's last
    /// vertex stops a GAP short of (> GAP_MIN). Asserts those geometric facts (so a non-gap or a
    /// non-pass-through is caught), then emits O<id>/U<id> per encounter.
    private static String reconstruct(List<double[][]> arcs, List<double[]> cross) {
        StringBuilder code = new StringBuilder();
        for (int[] v : visits(arcs, cross)) {
            code.append(v[1] == 1 ? 'O' : 'U').append(v[0]);
        }
        return code.toString();
    }

    /// Like {@link #reconstruct(List, List)} but returns "INVALID:<reason>" instead of throwing when a
    /// geometric assertion is violated — used by the mutation test, where the corruption may make an
    /// encounter neither a clean pass-through nor a clean gap.
    private static String reconstructOrError(List<double[][]> arcs, List<double[]> cross) {
        try {
            return reconstruct(arcs, cross);
        } catch (AssertionError | RuntimeException e) {
            return "INVALID:" + e.getMessage();
        }
    }

    private static List<int[]> visits(String type) {
        return visits(arcs(type), KnotDiagramLayout.crossingCentresPx(type));
    }

    /// The ordered list of crossing-visits {id, over?1:0} reconstructed from the arc geometry.
    private static List<int[]> visits(List<double[][]> arcs, List<double[]> cross) {
        List<int[]> out = new ArrayList<>();
        for (double[][] arc : arcs) {
            // OVER: nearest crossing to any INTERIOR vertex; it must be a pass-through (< OVER_EPS).
            int overId = -1;
            double overDist = Double.POSITIVE_INFINITY;
            for (int k = 1; k < arc.length - 1; k++) {
                for (double[] c : cross) {
                    double d = dist(arc[k], c);
                    if (d < overDist) {
                        overDist = d;
                        overId = (int) c[0];
                    }
                }
            }
            if (overId < 0) {
                continue;   // an unknot loop (no crossings) contributes no encounters
            }
            assertTrue(overDist < OVER_EPS,
                "an arc must pass THROUGH its over-crossing (nearest interior vertex " + overDist
                    + "px from a centre, must be < " + OVER_EPS + ")");
            out.add(new int[] {overId, 1});

            // UNDER: nearest crossing to the arc's LAST vertex; it must be a GAP (> GAP_MIN).
            double[] last = arc[arc.length - 1];
            int gapId = -1;
            double gapDist = Double.POSITIVE_INFINITY;
            for (double[] c : cross) {
                double d = dist(last, c);
                if (d < gapDist) {
                    gapDist = d;
                    gapId = (int) c[0];
                }
            }
            assertTrue(gapDist > GAP_MIN,
                "an arc's far end must GAP short of its under-crossing (last vertex " + gapDist
                    + "px from the nearest centre, must be > " + GAP_MIN + ")");
            out.add(new int[] {gapId, 0});
        }
        return out;
    }

    // -- arc extraction (public geometry) ----------------------------------------------------------

    /// The emitted strand ARCS (each a polyline of (x,y) vertices), in emit (traversal) order — one per
    /// anchor group. Parsed from the public {@link Path} `d` strings (absolute M/L only), exactly the
    /// geometry a browser draws.
    private static List<double[][]> arcs(String type) {
        LaidOut laid = KnotDiagramLayout.layout(new Knot(type, null));
        List<double[][]> arcs = new ArrayList<>();
        for (Shape s : laid.shapes()) {
            for (Shape leaf : (s instanceof Group g) ? g.members() : List.of(s)) {
                if (leaf instanceof Path p) {
                    arcs.add(parse(p.d()));
                }
            }
        }
        return arcs;
    }

    /// Parse a path `d` into its vertex list by validating the EXACT EMITTED GRAMMAR strictly:
    /// `M x y (L x y)* Z?` — one leading absolute `M` with two coordinates, then zero-or-more absolute
    /// `L x y`, then AT MOST ONE `Z` and only as the FINAL token. Case-sensitive; every token is at a
    /// fixed grammatical position, so ANY deviation fails closed. This is a MECHANISM (a whole-grammar
    /// validator), not a token-by-token classifier — it closes the entire class of "the parser accepts a
    /// malformed token/position and reconstructs a clean code" findings in ONE rule, instead of patching
    /// them one edge case per round:
    ///   * a SECOND `M`, an `L` before the `M`, or a detached stray subpath (reviews 370 + 371) →
    ///     rejected: a non-`L` command appears where the grammar requires `L`.
    ///   * a RELATIVE command (lowercase `m`/`l`) reinterpreting the coordinates (review sir381) →
    ///     rejected: it is not the exact `L` the grammar requires (case-sensitive).
    ///   * a MID-ARC `Z` (closepath resets the current point — a materially different browser render,
    ///     review sir388) → rejected: `Z` is legal ONLY as the terminal token.
    ///   * a coordinate in Java-only number syntax a browser cannot draw — a HEXADECIMAL float
    ///     (`0x1.4p3`), a type suffix, or a non-finite `NaN`/`Infinity` (review sir393) → rejected:
    ///     each coordinate must match the SVG decimal/exponent number grammar AND be finite before
    ///     conversion, so a hex spelling that `Double.parseDouble` round-trips to the exact double
    ///     (but Chrome renders as a zero-length path) fails closed instead of false-greening.
    ///   * a Java-only WHITESPACE separator a browser does not split on — U+000B VERTICAL TAB or
    ///     U+000C FORM FEED (both matched by Java's `\s` but NOT SVG path whitespace, review sir401) →
    ///     rejected by the SVG-whitespace guard below, which also tokenizes on SVG whitespace only, so
    ///     such a separator can neither be split on nor glued silently into a coordinate.
    /// The browser draws exactly this grammar; anything outside it is a broken/altered curve.
    private static double[][] parse(String d) {
        // SVG-path-WHITESPACE guard (review sir401): SVG path whitespace is ONLY U+0020 space,
        // U+0009 tab, U+000A LF, U+000D CR. Java's `\s` (the retired `split("\\s+")`) ALSO matches
        // U+000B VERTICAL TAB and U+000C FORM FEED, and Character.isWhitespace is broader still — none
        // of which the SVG path grammar treats as a separator, so a browser renders a path "separated"
        // by one as zero-length. Reject any whitespace char that is not SVG path whitespace BEFORE
        // tokenizing, so a Java-only separator fails closed with a clear cause instead of gluing tokens.
        // (Non-whitespace lexical divergences — a hex coordinate, a relative command — fall through to
        // svgNumber / the case-sensitive position checks below; this guard is only the separator class.)
        for (int ci = 0; ci < d.length(); ci++) {
            char c = d.charAt(ci);
            boolean svgWs = c == ' ' || c == '\t' || c == '\n' || c == '\r';
            boolean javaWs = Character.isWhitespace(c) || c == '\u000B' || c == '\u000C';
            assertTrue(!(javaWs && !svgWs),
                "the path `d` uses a Java-only whitespace separator (U+000B VERTICAL TAB / U+000C FORM "
                + "FEED / other non-SVG whitespace) that SVG path syntax does not split on — a browser "
                + "renders it zero-length (review sir401): U+" + String.format("%04X", (int) c)
                + " in: " + d);
        }
        // Tokenize on SVG path WHITESPACE ONLY (not Java `\s`, which would eat U+000B/U+000C).
        String[] raw = d.trim().split("[ \\t\\n\\r]+");
        // Drop empty tokens (defensive; a trimmed non-empty d yields none).
        List<String> tokList = new ArrayList<>();
        for (String t : raw) {
            if (!t.isEmpty()) {
                tokList.add(t);
            }
        }
        String[] tok = tokList.toArray(new String[0]);
        int n = tok.length;
        // A single closepath `Z` is legal ONLY as the final token (sir388): a mid-arc Z resets the
        // current point. Strip exactly one terminal Z; forbid Z anywhere else.
        int end = (n > 0 && tok[n - 1].equals("Z")) ? n - 1 : n;
        for (int k = 0; k < end; k++) {
            assertTrue(!tok[k].equals("Z"),
                "Z (closepath) is accepted ONLY as the final token — a mid-arc Z resets the current "
                + "point (a materially different curve) and fails closed (review sir388): " + d);
        }
        // Body: M x y (L x y)* — one leading absolute M, then only absolute L commands, exact coords.
        assertTrue(end >= 3 && tok[0].equals("M"),
            "a strand arc must begin with an absolute `M x y` (grammar M x y (L x y)* Z?): " + d);
        List<double[]> pts = new ArrayList<>();
        int i = 0;
        boolean first = true;
        while (i < end) {
            String expected = first ? "M" : "L";
            assertTrue(tok[i].equals(expected),
                "expected an absolute '" + expected + "' at token " + i + " (grammar M x y (L x y)* Z?; "
                + "case-sensitive — no relative command, second M, mid-arc Z, or stray token): got '"
                + tok[i] + "' in: " + d);
            assertTrue(i + 2 < end, "the '" + expected + "' command needs two coordinates: " + d);
            pts.add(new double[] {svgNumber(tok[i + 1], d), svgNumber(tok[i + 2], d)});
            i += 3;
            first = false;
        }
        return pts.toArray(new double[0][]);
    }

    /// The SVG path-number grammar (SVG 1.1 §8.3.1 `number`): an optional sign, then a decimal
    /// (`123`, `1.5`, `.5`, `1.`) with an optional `e`/`E` exponent. Deliberately has NO hexadecimal
    /// float (`0x1.4p3`), NO type suffix (`1.5f`), and NO `NaN`/`Infinity` — all of which
    /// {@link Double#parseDouble} accepts but a browser's SVG path parser rejects.
    private static final Pattern SVG_NUMBER =
        Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    /// Parse ONE coordinate token, validating it against the SVG number grammar (not Java's) before
    /// conversion, and rejecting non-finite results (review sir393). {@code Double.parseDouble} would
    /// silently accept a Java hexadecimal-float spelling like {@code 0x1.4p3} and return the EXACT
    /// original double, so a raw-parseDouble oracle reconstructs the unchanged vertices and preserves
    /// the canonical Gauss code — yet Chrome draws that path with length 0. Validating the SPELLING (a
    /// browser sees the string, not the double) closes that browser/oracle divergence.
    private static double svgNumber(String tok, String d) {
        assertTrue(SVG_NUMBER.matcher(tok).matches(),
            "a coordinate must match the SVG decimal/exponent number grammar — a browser sees the "
            + "SPELLING, so Java-only syntax (hexadecimal float 0x…p…, type suffix, NaN/Infinity) is "
            + "rejected even when Double.parseDouble would read it (review sir393): got '" + tok
            + "' in: " + d);
        double v = Double.parseDouble(tok);
        assertTrue(Double.isFinite(v),
            "a coordinate must be finite — NaN/±Infinity, including overflow of a well-formed spelling "
            + "like 1e400, fails closed (review sir393): got '" + tok + "' in: " + d);
        return v;
    }

    /// Frozen pre-370 parser (reviews 370 + 371 red-on-old reference): folded `M` and `L` into one
    /// undifferentiated vertex list with NO structure check, so a mid-arc pen-lift or a detached stray
    /// subpath silently merged into the containing arc and reconstructed a clean Gauss code.
    private static double[][] legacyUndifferentiatedParse(String d) {
        String[] tok = d.trim().split("\\s+");
        List<double[]> pts = new ArrayList<>();
        int i = 0;
        while (i < tok.length) {
            char c = tok[i].length() == 1 ? Character.toUpperCase(tok[i].charAt(0)) : ' ';
            if ((c == 'M' || c == 'L') && i + 2 < tok.length) {
                pts.add(new double[] {Double.parseDouble(tok[i + 1]), Double.parseDouble(tok[i + 2])});
                i += 3;
            } else {
                i += 1;
            }
        }
        return pts.toArray(new double[0][]);
    }

    /// Frozen pre-381 parser (review sir381 red-on-old reference): uppercased each command token before
    /// interpreting it, so a relative `l` — which a browser reads as a coordinate offset from the
    /// current point (a materially different / broken strand) — was folded back to absolute `L` and its
    /// numbers accepted as absolute geometry. The false-green.
    private static double[][] legacyCaseInsensitiveParse(String d) {
        String[] tok = d.trim().split("\\s+");
        List<double[]> pts = new ArrayList<>();
        int i = 0;
        boolean sawM = false;
        while (i < tok.length) {
            char c = tok[i].length() == 1 ? Character.toUpperCase(tok[i].charAt(0)) : ' ';
            if (c == 'M' || c == 'L') {
                assertTrue(i + 2 < tok.length, "a " + c + " command needs two coordinates: " + d);
                if (c == 'M') {
                    assertTrue(!sawM, "single contiguous stroke: " + d);
                    sawM = true;
                } else {
                    assertTrue(sawM, "L before M: " + d);
                }
                pts.add(new double[] {Double.parseDouble(tok[i + 1]), Double.parseDouble(tok[i + 2])});
                i += 3;
            } else {
                i += 1;
            }
        }
        assertTrue(sawM, "a strand arc must contain a leading M command: " + d);
        return pts.toArray(new double[0][]);
    }

    /// Frozen pre-388 parser (review sir388 red-on-old reference): the token-by-token classifier that
    /// accepted `Z` at ANY position and simply skipped it (`else if (t.equals("Z")) i += 1`), then
    /// resumed parsing the following `L` commands. A mid-arc closepath (which a browser draws as a return
    /// to the subpath start + current-point reset — a materially different curve) was silently ignored
    /// and the original absolute vertex list accepted. The false-green the strict grammar now closes.
    private static double[][] legacyLenientZParse(String d) {
        String[] tok = d.trim().split("\\s+");
        List<double[]> pts = new ArrayList<>();
        int i = 0;
        boolean sawM = false;
        while (i < tok.length) {
            String t = tok[i];
            if (t.isEmpty()) {
                i += 1;
                continue;
            }
            if (t.equals("M") || t.equals("L")) {
                assertTrue(i + 2 < tok.length, "a " + t + " command needs two coordinates: " + d);
                if (t.equals("M")) {
                    assertTrue(!sawM, "single contiguous stroke: " + d);
                    sawM = true;
                } else {
                    assertTrue(sawM, "L before M: " + d);
                }
                pts.add(new double[] {Double.parseDouble(tok[i + 1]), Double.parseDouble(tok[i + 2])});
                i += 3;
            } else if (t.equals("Z")) {
                i += 1;   // pre-388: accepted a Z anywhere and kept going
            } else {
                assertTrue(false, "outside M/L/Z grammar: " + d);
            }
        }
        assertTrue(sawM, "a strand arc must contain a leading M command: " + d);
        return pts.toArray(new double[0][]);
    }

    /// Frozen pre-393 parser (review sir393 red-on-old reference): the strict whole-grammar validator
    /// with coordinates read by RAW {@link Double#parseDouble}. Java's parseDouble accepts a hexadecimal-
    /// float spelling (`0x1.4p3`) — and returns the EXACT original double — so a browser-invalid hex
    /// coordinate reconstructed the unchanged vertices and preserved the canonical Gauss code. The
    /// false-green the SVG-number validation now closes. (Grammar is otherwise identical to {@link #parse}.)
    private static double[][] legacyRawCoordinateParse(String d) {
        String[] raw = d.trim().split("\\s+");
        List<String> tokList = new ArrayList<>();
        for (String t : raw) {
            if (!t.isEmpty()) {
                tokList.add(t);
            }
        }
        String[] tok = tokList.toArray(new String[0]);
        int n = tok.length;
        int end = (n > 0 && tok[n - 1].equals("Z")) ? n - 1 : n;
        for (int k = 0; k < end; k++) {
            assertTrue(!tok[k].equals("Z"), "Z only as final token: " + d);
        }
        assertTrue(end >= 3 && tok[0].equals("M"), "must begin with absolute M x y: " + d);
        List<double[]> pts = new ArrayList<>();
        int i = 0;
        boolean first = true;
        while (i < end) {
            String expected = first ? "M" : "L";
            assertTrue(tok[i].equals(expected), "expected " + expected + ": " + d);
            assertTrue(i + 2 < end, "the " + expected + " command needs two coordinates: " + d);
            pts.add(new double[] {Double.parseDouble(tok[i + 1]), Double.parseDouble(tok[i + 2])});
            i += 3;
            first = false;
        }
        return pts.toArray(new double[0][]);
    }

    /// Frozen pre-401 parser (review sir401 red-on-old reference): the round-5 grammar (SVG-number
    /// coordinates) but with Java `\s+` tokenization and NO whole-string alphabet guard. Java's `\s`
    /// matches U+000B/U+000C, so a path whose separator is a VERTICAL TAB tokenizes cleanly and is
    /// accepted — while a browser, which splits only on SVG whitespace (U+0020/09/0A/0D), renders it
    /// zero-length. The false-green the SVG-path-alphabet guard now closes.
    private static double[][] legacyJavaWhitespaceParse(String d) {
        String[] raw = d.trim().split("\\s+");   // pre-401: Java \s eats U+000B/U+000C too
        List<String> tokList = new ArrayList<>();
        for (String t : raw) {
            if (!t.isEmpty()) {
                tokList.add(t);
            }
        }
        String[] tok = tokList.toArray(new String[0]);
        int n = tok.length;
        int end = (n > 0 && tok[n - 1].equals("Z")) ? n - 1 : n;
        for (int k = 0; k < end; k++) {
            assertTrue(!tok[k].equals("Z"), "Z only as final token: " + d);
        }
        assertTrue(end >= 3 && tok[0].equals("M"), "must begin with absolute M x y: " + d);
        List<double[]> pts = new ArrayList<>();
        int i = 0;
        boolean first = true;
        while (i < end) {
            String expected = first ? "M" : "L";
            assertTrue(tok[i].equals(expected), "expected " + expected + ": " + d);
            assertTrue(i + 2 < end, "the " + expected + " command needs two coordinates: " + d);
            pts.add(new double[] {svgNumber(tok[i + 1], d), svgNumber(tok[i + 2], d)});
            i += 3;
            first = false;
        }
        return pts.toArray(new double[0][]);
    }

    /// Replace the first coordinate token (the x after the leading `M`) of `d` with its Java hexadecimal-
    /// float spelling — Lattice's sir393 discriminator: {@link Double#parseDouble} round-trips it to the
    /// EXACT same double, but SVG path-number syntax has no hex float, so a browser draws nothing.
    private static String hexifyFirstCoordinate(String d) {
        String[] tok = d.trim().split("\\s+");
        for (int i = 0; i < tok.length; i++) {
            if (tok[i].equals("M") && i + 1 < tok.length) {
                tok[i + 1] = Double.toHexString(Double.parseDouble(tok[i + 1]));
                break;
            }
        }
        return String.join(" ", tok);
    }

    /// The first coordinate (x after the leading M) of a decimal-spelled arc `d`.
    private static double firstCoordinate(String d) {
        String[] tok = d.trim().split("\\s+");
        for (int i = 0; i < tok.length; i++) {
            if (tok[i].equals("M") && i + 1 < tok.length) {
                return Double.parseDouble(tok[i + 1]);
            }
        }
        throw new AssertionError("no leading M coordinate in: " + d);
    }

    /// The first coordinate token (the x after the leading M) as a raw string — the hex spelling in a
    /// hexified arc.
    private static String firstHexToken(String d) {
        String[] tok = d.trim().split("\\s+");
        for (int i = 0; i < tok.length; i++) {
            if (tok[i].equals("M") && i + 1 < tok.length) {
                return tok[i + 1];
            }
        }
        throw new AssertionError("no leading M coordinate in: " + d);
    }

    /// The raw emitted Path `d` strings (one per strand arc, emit order) — the exact strings a browser
    /// draws, before parsing. Used by the structure discriminators to mutate a real arc's d string.
    private static List<String> rawArcStrings(String type) {
        LaidOut laid = KnotDiagramLayout.layout(new Knot(type, null));
        List<String> ds = new ArrayList<>();
        for (Shape s : laid.shapes()) {
            for (Shape leaf : (s instanceof Group g) ? g.members() : List.of(s)) {
                if (leaf instanceof Path p) {
                    ds.add(p.d());
                }
            }
        }
        return ds;
    }

    private static int countCommand(String d, char cmd) {
        int n = 0;
        for (String t : d.trim().split("\\s+")) {
            if (t.length() == 1 && Character.toUpperCase(t.charAt(0)) == cmd) {
                n++;
            }
        }
        return n;
    }

    /// Change the FIRST interior `L` command of `d` to an `M` — Lattice's 370 discriminator: a pen-lift
    /// that visibly splits the arc, without moving any coordinate.
    private static String splitFirstInteriorL(String d) {
        String[] tok = d.trim().split("\\s+");
        for (int i = 0; i < tok.length; i++) {
            if (tok[i].equalsIgnoreCase("L")) {
                tok[i] = "M";
                break;
            }
        }
        return String.join(" ", tok);
    }

    /// Change the first interior absolute `L` command of `d` to a RELATIVE `l` — Lattice's sir381
    /// discriminator: same numbers, but a browser now reads them relative to the current point (a
    /// materially different / broken strand).
    private static String relativizeFirstInteriorL(String d) {
        String[] tok = d.trim().split("\\s+");
        for (int i = 0; i < tok.length; i++) {
            if (tok[i].equals("L")) {
                tok[i] = "l";
                break;
            }
        }
        return String.join(" ", tok);
    }

    /// Insert a `Z` (closepath) immediately before the first interior `L` command — Lattice's sir388
    /// discriminator: a mid-arc close resets the current point, so the following line sequence is a
    /// materially different curve.
    private static String insertMidArcZ(String d) {
        String[] tok = d.trim().split("\\s+");
        List<String> out = new ArrayList<>();
        boolean inserted = false;
        for (String t : tok) {
            if (!inserted && t.equals("L")) {
                out.add("Z");
                inserted = true;
            }
            out.add(t);
        }
        return String.join(" ", out);
    }

    private static int nearestCross(double[] p, List<double[]> cross) {
        int id = -1;
        double best = Double.POSITIVE_INFINITY;
        for (double[] c : cross) {
            double d = dist(p, c);
            if (d < best) {
                best = d;
                id = (int) c[0];
            }
        }
        return id;
    }

    private static double dist(double[] p, double[] c) {
        // c may be {id,x,y} (crossing) or {x,y} (a vertex); handle both.
        double cx = c.length >= 3 ? c[1] : c[0];
        double cy = c.length >= 3 ? c[2] : c[1];
        return Math.hypot(p[0] - cx, p[1] - cy);
    }

    private static double[][] append(double[][] a, double[] p) {
        double[][] out = new double[a.length + 1][];
        System.arraycopy(a, 0, out, 0, a.length);
        out[a.length] = p;
        return out;
    }

    private static double[][] prepend(double[] p, double[][] a) {
        double[][] out = new double[a.length + 1][];
        out[0] = p;
        System.arraycopy(a, 0, out, 1, a.length);
        return out;
    }
}
