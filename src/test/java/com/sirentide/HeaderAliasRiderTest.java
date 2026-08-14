package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/**
 * TWO MISSING MERMAID HEADER SPELLINGS (crew RFC stafficy/16752 item 5, unanimous).
 *
 * <p>{@code graph} is mermaid's original flowchart spelling and still the most-copied one;
 * {@code xychart-beta} is mermaid's actual spelling of a type Sirentide calls {@code xychart}.
 * Neither resolved, so both degraded the whole diagram — the same first-contact failure the
 * {@code statediagram}/{@code sequencediagram}/{@code quadrantchart} aliases were added to end.
 *
 * <p>{@code sankey-beta} was already admitted, so the {@code -beta} convention had been
 * considered and then applied unevenly. This is the missing half of a decision already taken.
 */
class HeaderAliasRiderTest {

    @Test
    void graphIsAcceptedAsTheFlowchartSpelling() {
        // Identity against the canonical spelling, not merely "it renders": an alias that
        // produced a DIFFERENT diagram would pass a weaker assertion while being a worse bug
        // than the blank it replaces.
        assertEquals(Sirentide.render("flowchart TD\n    A[One] --> B[Two]\n"),
            Sirentide.render("graph TD\n    A[One] --> B[Two]\n"),
            "`graph` must render exactly as `flowchart`, not merely render");
    }

    @Test
    void xychartBetaIsAcceptedAsTheXychartSpelling() {
        String body = "\n \"a\" : 4\n \"b\" : 8\n";
        assertEquals(Sirentide.render("xychart" + body),
            Sirentide.render("xychart-beta" + body),
            "`xychart-beta` must render exactly as `xychart`");
    }

    @Test
    void bothActuallyDrawContentRatherThanAnEmptyCanvas() {
        // THE POSITIVE CONTROL for the two identity assertions above. Two empty canvases are
        // equal to each other, so equality alone would pass even if the alias resolved to
        // nothing at all — which is precisely the failure being fixed.
        assertTrue(Sirentide.render("graph TD\n    A[One] --> B[Two]\n").contains("<path"),
            "`graph` must draw real glyphs");
        assertTrue(Sirentide.render("xychart-beta\n \"a\" : 4\n \"b\" : 8\n").contains("<rect"),
            "`xychart-beta` must draw real bars");
    }

    @Test
    void anUnknownHeaderStillFailsClosedRatherThanFuzzyMatching() {
        // The alias table's own contract, restated as a test because adding entries is exactly
        // when someone is tempted to make it forgiving. A near-miss must NOT resolve: silently
        // rendering the wrong diagram is far worse than the blank.
        RenderResult r = Sirentide.renderWithDiagnostics("graphh TD\n    A --> B\n");
        assertNotEquals(Outcome.OK, r.diagnostics().outcome(),
            "a near-miss header must not fuzzy-match onto `graph`");
    }
}
