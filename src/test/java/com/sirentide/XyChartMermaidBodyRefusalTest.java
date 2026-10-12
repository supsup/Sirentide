package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.Empty;
import com.sirentide.ir.XyChart;
import com.sirentide.parse.DslParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// A real Mermaid `xychart-beta` body is REFUSED and NAMED (audit 2026-10-12 finding 6; lead ruling
/// "refuse first, fail closed").
///
/// THE DEFECT. The alias table admits `xychart-beta` because it is Mermaid's own spelling, and
/// HeaderAliasRiderTest pins that with Sirentide-grammar bodies only. A body in the shape of
/// Mermaid's docs has no row this parser reads:
///
///     xychart-beta
///       title "Sales"
///       x-axis [jan, feb]
///       y-axis "Rev" 0 --> 10
///       bar [5, 6]
///
/// Every line was skipped in silence and the result was an XyChart with no bars: bare axes at
/// outcome OK. Before the alias, the same paste was a loud unknown-type error, so the alias made
/// this input quieter, not better.
///
/// THE REFUSAL FIRES ONLY WHERE THE ROW GRAMMAR ALREADY SKIPPED THE LINE. That is why the
/// controls can assert that a row which parsed before still parses to the same label and value,
/// including rows whose label happens to be a Mermaid keyword.
class XyChartMermaidBodyRefusalTest {

    private static final String MERMAID_DOCS_SHAPE =
        "xychart-beta\n title \"Sales\"\n x-axis [jan, feb]\n y-axis \"Rev\" 0 --> 10\n bar [5, 6]\n";

    private static List<String> bars(String dsl) {
        Diagram d = DslParser.parse(dsl);
        XyChart chart = assertInstanceOf(XyChart.class, d,
            "expected an XyChart to inspect, got " + d.getClass().getSimpleName() + " for: " + dsl);
        return chart.bars().stream().map(s -> s.label() + "=" + s.value()).toList();
    }

    private static void assertRefusedNaming(String dsl, String keyword, int line) {
        assertInstanceOf(Empty.class, DslParser.parse(dsl),
            "a Mermaid xychart statement must degrade the chart, never draw empty axes: " + dsl);
        DslParser.UnsupportedConstruct u = DslParser.detectUnsupportedConstruct(dsl);
        assertTrue(u != null, "the statement must be NAMED, not silently degraded: " + dsl);
        assertEquals(keyword, u.token(), "the detector's token for: " + dsl);
        assertEquals(line, u.line(), "the 1-based physical line of the statement in: " + dsl);

        RenderResult r = Sirentide.renderWithDiagnostics(dsl);
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT, r.diagnostics().outcome(),
            "a refusal must not read as success: " + r.diagnostics().message());
        assertEquals("parse", r.diagnostics().stage());
        assertEquals(line, r.diagnostics().line());
        assertTrue(r.diagnostics().message().contains("`" + keyword + "`"),
            "the author-facing message must name the `" + keyword + "` statement: "
                + r.diagnostics().message());
    }

    // -- the refusal ------------------------------------------------------------------------------

    @Test
    void theMermaidDocsShapeIsRefusedAtItsFirstStatement() {
        // The audit's own input. The first Mermaid statement is `title`, on physical line 2.
        assertRefusedNaming(MERMAID_DOCS_SHAPE, "title", 2);
    }

    @Test
    void eachMermaidStatementIsRefusedOnItsOwn() {
        // One keyword per assertion, each the only body line, so none can hide behind another.
        // The plain `xychart` header shares the grammar, so it shares the refusal.
        assertRefusedNaming("xychart-beta\nx-axis [jan, feb]\n", "x-axis", 2);
        assertRefusedNaming("xychart\ny-axis \"Rev\" 0 --> 10\n", "y-axis", 2);
        assertRefusedNaming("xychart-beta\nbar [5, 6]\n", "bar", 2);
        assertRefusedNaming("xychart-beta\nline [5, 6]\n", "line", 2);
    }

    @Test
    void aMermaidStatementWhoseQuotedTextHoldsAColonIsRefused() {
        // The SECOND skip site. This line has a `:`, so it gets past the no-colon skip, and is
        // dropped one step later for having no number after its last colon. A refusal wired only
        // to the no-colon site lets it through, and the chart renders the one real row at OK with
        // its title silently gone.
        assertRefusedNaming("xychart-beta\ntitle \"Sales: Q1\"\n\"a\" : 4\n", "title", 2);
    }

    @Test
    void aMermaidStatementBesideRealRowsIsRefusedToo() {
        // Not only the all-skipped case: a half-converted paste would otherwise render its real
        // rows and drop the Mermaid series without a word.
        assertRefusedNaming("xychart\n\"a\" : 4\nbar [5, 6]\n", "bar", 3);
    }

    // -- the controls -----------------------------------------------------------------------------

    @Test
    void sirentideRowsUnderTheMermaidHeaderParseToTheSameBarsAsBefore() {
        // THE POSITIVE CONTROL: the alias itself is untouched. Same fixture HeaderAliasRiderTest
        // renders, asserted here on the parsed labels and values.
        String dsl = "xychart-beta\n \"a\" : 4\n \"b\" : 8\n";
        assertEquals(List.of("a=4.0", "b=8.0"), bars(dsl));
        assertNull(DslParser.detectUnsupportedConstruct(dsl));
        assertEquals(Outcome.OK, Sirentide.renderWithDiagnostics(dsl).diagnostics().outcome());
    }

    @Test
    void aRowLabelledWithAMermaidKeywordIsStillARow() {
        // The keyword is consulted only for a line the row grammar could not read. Each of these
        // IS a row (a label, a colon, a number), so each parses exactly as it did. A refusal keyed
        // on the leading word alone refuses all three.
        String dsl = "xychart\nbar : 5\nline speed : 3\ntitle : 2\n";
        assertEquals(List.of("bar=5.0", "line speed=3.0", "title=2.0"), bars(dsl));
        assertNull(DslParser.detectUnsupportedConstruct(dsl));
        assertEquals(Outcome.OK, Sirentide.renderWithDiagnostics(dsl).diagnostics().outcome());
    }

    @Test
    void anUnreadableLineThatIsNotAMermaidStatementKeepsItsSilentSkip() {
        // SCOPE, pinned so it is a decision. Only a line led by a Mermaid xychart keyword is
        // refused. Other unreadable lines, and a keyword standing alone with nothing after it,
        // are skipped as they always were, and the rows around them are unchanged.
        String dsl = "xychart\n\"a\" : 4\nthis is not a row\nbar\n\"b\" : 8\n";
        assertEquals(List.of("a=4.0", "b=8.0"), bars(dsl));
        assertNull(DslParser.detectUnsupportedConstruct(dsl));
    }
}
