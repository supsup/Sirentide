package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/// UML multiplicities are DRAWN, not only described (plan 35bccb97, Charles's "should render, if
/// possible" ruling).
///
/// WHAT THIS PINS, and why it needed a render test rather than an IR one. Multiplicity has been
/// parsed and carried on {@link com.sirentide.ir.ClassRelation} since plan 24d6b22f, and it reached
/// the accessible description — but a census of every consumer of the accessors found exactly two,
/// BOTH in `A11yDescriber`. Layout and emit never read them. So the cardinality shipped to assistive
/// tech and not to the eye: `ClassDiagramMultiplicityTest` was fully green throughout, because every
/// assertion it makes was still true. A test that asserts the description is blind to whether
/// anything was drawn.
class ClassDiagramMultiplicityRenderTest {

    private static final String BASE = "classDiagram\n  Customer --> Order : places\n";
    private static final String LEFT_ONLY = "classDiagram\n  Customer \"1\" --> Order : places\n";
    private static final String BOTH = "classDiagram\n  Customer \"1\" --> \"0..*\" Order : places\n";

    /// Glyph runs are emitted one per drawn text run, as `<path>` elements — this diagram family has
    /// no other path source, which {@link #theCounterRespondsToDrawnTextAtAll} is the control for.
    private static double[] multiplicitySide(double fx, double fy) {
        return ClassDiagramLayout.multiplicitySide(fx, fy);
    }

    private static int glyphRuns(String svg) {
        return svg.split("<path", -1).length - 1;
    }

    /// THE INSTRUMENT CONTROL, and it is not decoration. My first structural check on this feature
    /// asked whether any `<text>` element carried the cardinality, got no, and proved nothing: this
    /// renderer emits glyphs as PATHS, so there are zero `<text>` elements in any diagram and the
    /// same query "proves" the class names are not drawn either. A counter that cannot see drawn
    /// text would report delta 0 for every case below and every assertion would pass vacuously. So
    /// prove the counter moves for text we already know is drawn, before trusting it about text we
    /// are asking about.
    @Test
    void theCounterRespondsToDrawnTextAtAll() {
        int withLabel = glyphRuns(Sirentide.render(BASE));
        int withoutLabel = glyphRuns(Sirentide.render("classDiagram\n  Customer --> Order\n"));
        assertTrue(withLabel > 0, "a rendered class diagram draws SOMETHING: " + withLabel);
        assertEquals(withoutLabel + 1, withLabel,
            "dropping the `: places` label must remove exactly one glyph run — if this fails the "
                + "counter is not measuring drawn text and every other assertion here is vacuous");
    }

    @Test
    void bothEndpointCardinalitiesAreDrawn() {
        assertEquals(glyphRuns(Sirentide.render(BASE)) + 2, glyphRuns(Sirentide.render(BOTH)),
            "two cardinalities on one relation draw two glyph runs beyond the same relation "
                + "without them");
    }

    /// The one-sided leg. Without it, a change that drew the LEFT cardinality twice — or drew a
    /// single shared run for the pair — would still satisfy the +2 above.
    @Test
    void aSingleCardinalityDrawsExactlyOneRun() {
        assertEquals(glyphRuns(Sirentide.render(BASE)) + 1, glyphRuns(Sirentide.render(LEFT_ONLY)),
            "one cardinality draws exactly one run");
    }

    /// The accessible description must be UNAFFECTED by the drawing change — it already carried the
    /// cardinality and that behaviour is not this plan's to alter.
    @Test
    void drawingTheCardinalityDoesNotDisturbTheDescription() {
        String svg = Sirentide.render(BOTH);
        assertTrue(svg.contains("Customer (1)") && svg.contains("Order (0..*)"),
            "the desc still names both cardinalities: " + svg.substring(0, Math.min(600, svg.length())));
    }

    // ---- the side rule, pinned directly ------------------------------------------------------
    //
    // This is the part that was wrong TWICE, in OPPOSITE directions, with both cuts reading as
    // reasonable source. Cut 1 derived the normal from each endpoint's own OUTWARD direction: the
    // two ends face opposite ways, so the normal flipped sign between them and one relation's
    // cardinalities straddled the stroke — the left one landing back on the label it was moved off.
    // Cut 2 derived ONE normal from a single forward direction: consistent, and consistently wrong,
    // because forward pointed leftward and both cardinalities went ABOVE, which is where the label
    // lives. Only the third cut states the side in SCREEN space, where the label's own flat -3 lift
    // is stated. Each failure is a case below.

    @Test
    void theSideIsAlwaysOppositeTheLabel_whicheverWayTheEdgeRuns() {
        // The label lifts -3 in screen y (above the stroke) regardless of edge direction, so the
        // cardinality's normal must point DOWN-screen for BOTH a rightward and a leftward leg.
        // Cut 2 failed exactly here: a leftward edge sent it up, onto the label.
        assertTrue(multiplicitySide(1, 0)[1] > 0, "rightward leg: normal points down");
        assertTrue(multiplicitySide(-1, 0)[1] > 0, "leftward leg: normal points down");
    }

    @Test
    void bothEndsOfOneRelationGetTheSameSide() {
        // Cut 1's failure, stated as the property it violated. The two endpoints call with their own
        // leg directions, which on a straight edge are exact opposites; the rule must still return
        // the same side for both.
        double[] forward = multiplicitySide(1, 0);
        double[] backward = multiplicitySide(-1, 0);
        assertEquals(forward[1], backward[1], 1e-9,
            "a leg and its reverse name the SAME side — otherwise one relation's two cardinalities "
                + "straddle the stroke and only one of them clears the label");
    }

    @Test
    void aVerticalLegBreaksTheTieDeterministicallyAndHorizontally() {
        // On a vertical edge the normal is horizontal, so the down-screen rule cannot decide it and
        // the label sits ON the stroke rather than above it. Either side clears; the requirement is
        // that the choice be deterministic and identical at both ends.
        double[] down = multiplicitySide(0, 1);
        double[] up = multiplicitySide(0, -1);
        assertEquals(0, down[1], 1e-9, "vertical leg: the normal is horizontal");
        assertTrue(down[0] > 0, "vertical leg: the tie breaks rightward");
        assertEquals(down[0], up[0], 1e-9, "and it breaks the same way for the reversed leg");
    }

    @Test
    void theNormalIsAUnitVector() {
        // A non-unit normal would scale MULT_PERP silently — the offset would look right on the
        // axis-aligned cases above and drift on a diagonal, which is the shape of bug that only
        // shows up on someone else's diagram.
        double[] diag = multiplicitySide(0.6, 0.8);
        assertEquals(1.0, Math.hypot(diag[0], diag[1]), 1e-9, "the normal is unit length");
        assertNotEquals(0.0, diag[1], "and a diagonal leg still resolves to a real side");
    }
}
