package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.Flowchart;
import com.sirentide.parse.DslParser;
import org.junit.jupiter.api.Test;

/// Slice A of plan e159e907: an over-long edge operator must NOT mint a phantom node.
///
/// THE BEHAVIOUR IS ALREADY CORRECT AND WAS COMPLETELY UNPINNED, which is why this exists. The fix
/// landed under plan c46d3c51 as the operator-residue guard at `DslParser.java:853`, three weeks
/// before this test, in another Commander's lane. A grep of every `*Test.java` for the long arrow
/// returned ZERO hits: correct behaviour with no pin is how a class-level fix gets quietly undone
/// by someone who cannot see what it was for.
///
/// WHAT THE DEFECT WAS. `matchEdgeOp` matches FIXED lengths, so `A ---> B` matched the three-char
/// `---` and stranded `> B`, which `parseEndpoint` then accepted as a bare node id -- minting a
/// drawn node literally named `> B`, at `outcome=OK`. A finished-looking diagram containing a box
/// the author never wrote, which is worse than a refusal because nothing on the page looks wrong.
///
/// DERIVED, NOT ASSUMED (plan re-derivation 2026-09-08). Every expectation below was read off the
/// parser first by parsing the input and reading `Flowchart.nodes()` directly, rather than counting
/// rects in rendered SVG or trusting the retired `nodes=3` census figure. The count contract for
/// this input is ZERO nodes, because the whole LINE is dropped.
final class OperatorResidueNoPhantomTest {

    private static Flowchart parseFlowchart(String body) {
        Diagram d = DslParser.parse("flowchart TD\n  " + body + "\n");
        assertTrue(d instanceof Flowchart,
            body + ": expected a Flowchart to inspect, got " + d.getClass().getSimpleName());
        return (Flowchart) d;
    }

    /// The instance the plan was filed for.
    @Test
    void theLongArrowMintsNothingAndSaysWhy() {
        Flowchart f = parseFlowchart("A ---> B");

        // THE NODE-IDENTITY ASSERTION IS THE LOAD-BEARING ONE, and the plan's original acceptance
        // criterion got this wrong. It asked for node-COUNT assertions, which is right for THIS
        // input and wrong for the sibling `A -- yes --> B`, where the phantom is MIS-NAMED rather
        // than extra and the count matches a correct baseline exactly. Identity is what the defect
        // changes; count is what it happens to change here too.
        for (var n : f.nodes()) {
            assertNotEquals("> B", n.id(),
                "the stranded operator tail must never become a node id: " + f.nodes());
        }
        assertEquals(0, f.nodes().size(),
            "the whole line is dropped, so it contributes NO nodes: " + f.nodes());
        assertEquals(0, f.edges().size(), "and no edges: " + f.edges());

        // LOUD, not silently empty. The defect's whole shape was outcome=OK on a diagram carrying a
        // phantom; an empty diagram reported as fine would be the same failure wearing less.
        RenderResult r = Sirentide.renderWithDiagnostics("flowchart TD\n  A ---> B\n");
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT, r.diagnostics().outcome(),
            "a dropped line must be reported, never rendered as a quiet empty: "
                + r.diagnostics().message());
        assertEquals("parse", r.diagnostics().stage());
        assertTrue(r.diagnostics().message().contains("edge operator longer than"),
            "the message must name the MECHANISM so an author can fix the line: "
                + r.diagnostics().message());
    }

    /// The CLASS, which is the point of the guard and the reason it is not a blacklist.
    ///
    /// Pinned ONE SPELLING AT A TIME rather than asserted as a group, per the convention
    /// `UnknownDirectiveShapeTest` states: a single combined case would leave the others unproven.
    /// None of these was enumerated anywhere in the parser; they are covered because the RESIDUE is
    /// the detector, so any operator run longer than a recognised form trips it.
    @Test
    void everyOverLongOperatorSpellingIsCoveredWithoutBeingEnumerated() {
        for (String body : new String[] {"A ---> B", "A ----> B", "A -->> B", "A ==>> B",
                                         "A -.->> B", "A ===> B"}) {
            Flowchart f = parseFlowchart(body);
            assertEquals(0, f.nodes().size(),
                body + ": an operator run longer than any recognised form must mint NOTHING, and "
                    + "this spelling is nowhere enumerated in the parser: " + f.nodes());
        }
    }

    /// MARLOW'S BINDING CONDITION, and it is half the fix rather than a courtesy negative.
    ///
    /// The guard must be a RESIDUE detector and not a character guard: a line whose operators are
    /// fully consumed is untouched, so legal syntax cannot be caught by widening. Without this, the
    /// test above is satisfiable by a parser that simply refuses every dash it sees.
    @Test
    void legalOperatorsAreUntouchedByTheResidueGuard() {
        for (String[] legal : new String[][] {
                {"A --> B", "2"}, {"A --- B", "2"}, {"A -.-> B", "2"},
                {"A -.- B", "2"}, {"A ==> B", "2"}, {"A === B", "2"},
                {"A-B --> C", "2"}}) {
            Flowchart f = parseFlowchart(legal[0]);
            assertEquals(Integer.parseInt(legal[1]), f.nodes().size(),
                legal[0] + ": fully-consumed operators are LEGAL and must be untouched; a guard "
                    + "that catches these is a character guard rather than a residue detector: "
                    + f.nodes());
            assertFalse(f.nodes().isEmpty(), legal[0] + ": must still build its nodes");
        }
    }
}
