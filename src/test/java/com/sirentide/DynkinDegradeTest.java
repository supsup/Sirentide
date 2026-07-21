package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/// Review 368 (plan 8e13b196): malformed / unknown-family / invalid-finite-type-rank / over-cap
/// Dynkin input must degrade to the UNIVERSAL inert shell — the byte-stable 0x0 `Empty` shell — never
/// a 48x48 titled "Empty Dynkin diagram" canvas. Before the fix, `parseDynkin` returned an invalid
/// `Dynkin('?', 0)` (or a `Dynkin` with an unknown family), which the layout baked as a padded 48x48
/// titled SVG, contradicting the plan's "degrade inertly" contract and the handoff's claim that these
/// inputs reach the inert shell. A valid type still renders with a positive accessible description.
class DynkinDegradeTest {

    /// The universal inert shell: exactly what a blank source (and every degrade target) bakes.
    private static final String INERT = Sirentide.render("");

    private static String type(String t) {
        return Sirentide.render("dynkin\ntype: " + t + "\n");
    }

    @Test
    void unknownFamilyDegradesToTheInertShell() {
        assertEquals(INERT, type("Z9"), "an unknown family letter bakes the universal inert shell");
        assertEquals(INERT, type("H4"), "H is not a supported finite type → inert shell");
    }

    @Test
    void malformedTokenDegradesToTheInertShell() {
        assertEquals(INERT, type("nonsense"), "a malformed type token bakes the inert shell");
        assertEquals(INERT, type("A"), "a family letter with no rank bakes the inert shell");
        assertEquals(INERT, type("A-3"), "a non-positive rank token bakes the inert shell");
        assertEquals(INERT, type("A0"), "rank 0 is not positive → inert shell");
    }

    @Test
    void invalidFiniteTypeRankDegradesToTheInertShell() {
        // A_n:n>=1, B/C_n:n>=2, D_n:n>=4, E_n:6..8, F:4, G:2 — everything else is not a real diagram.
        for (String bad : new String[] {"B1", "C1", "D2", "D3", "E5", "E9", "F3", "F5", "G1", "G3"}) {
            assertEquals(INERT, type(bad), bad + " is not a real finite-type Dynkin diagram → inert shell");
        }
    }

    @Test
    void overCapRankDegradesToTheInertShell() {
        assertEquals(INERT, type("A201"), "a rank past MAX_DYNKIN_RANK (200) bakes the inert shell (never OOM)");
        assertEquals(INERT, type("A1000000"), "a far-over-cap rank bakes the inert shell");
    }

    @Test
    void bareDynkinWithNoTypeDegradesToTheInertShell() {
        assertEquals(INERT, Sirentide.render("dynkin\n"),
            "a bare dynkin with no type line is the universal inert shell (no invalid Dynkin sentinel)");
    }

    @Test
    void everyDegradePathIsByteIdenticalToTheUniversalInertShellWithNoTitle() {
        // The pre-368 bug baked a 48x48 SVG carrying <title>Dynkin diagram</title> + an "Empty Dynkin
        // diagram." desc for each of these. The fix makes them byte-identical to the 0x0 Empty shell.
        for (String bad : new String[] {"Z9", "A201", "nonsense", "B1"}) {
            String svg = type(bad);
            assertEquals(INERT, svg, bad + " must be byte-identical to the universal inert shell");
            assertFalse(svg.contains("<title>"), bad + " inert shell carries no <title>: " + svg);
            assertFalse(svg.contains("Dynkin"), bad + " inert shell never names Dynkin: " + svg);
        }
    }

    @Test
    void validMinimumRankOfEachFamilyRenders() {
        for (String ok : new String[] {"A1", "B2", "C2", "D4", "E6", "F4", "G2"}) {
            assertNotEquals(INERT, type(ok), ok + " is a valid finite type and must render a diagram");
        }
    }

    @Test
    void validMaximumRankAtTheCapRenders() {
        assertNotEquals(INERT, type("A200"),
            "A200 is exactly at MAX_DYNKIN_RANK and must render (the cap boundary is inclusive)");
    }

    @Test
    void validTypeHasAPositiveAccessibleDescription() {
        String svg = type("B4");
        String title = between(svg, "<title>", "</title>");
        String desc = between(svg, "<desc>", "</desc>");
        assertEquals("Dynkin diagram", title, "a valid type carries the positive type title");
        assertTrue(desc.contains("type B4"), "the desc names the canonical type: " + desc);
        assertFalse(desc.contains("Empty Dynkin diagram"),
            "a valid type is never described as an empty diagram: " + desc);
    }

    private static String between(String s, String open, String close) {
        int a = s.indexOf(open);
        if (a < 0) {
            return "";
        }
        a += open.length();
        int b = s.indexOf(close, a);
        return b < 0 ? "" : s.substring(a, b);
    }
}
