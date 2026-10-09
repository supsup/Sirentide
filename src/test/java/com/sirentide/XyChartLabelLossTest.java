package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.ir.XyChart;
import com.sirentide.layout.XyChartLayout;
import com.sirentide.parse.DslParser;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// An xychart category label that does not fit its column slot is NAMED, not silent (plan c880b12e).
///
/// Before this, {@link XyChartLayout} ellipsized each category label to its slot and, when not even
/// the ellipsis fitted, drew NOTHING: the Barker autocorrelation chart (25 lags) silently lost every
/// two-character label (-12..-1, 10..12) while `render --strict` exited 0. Found by Fixpoint's showcase
/// audit (sirentide/1118), reproduced by Confluence before filing. The labels are glyph outlines, not
/// `<text>`, so no text search of the SVG can see the loss; the signal has to come from the layout.
class XyChartLabelLossTest {

    /// The audit's chart, verbatim in shape: 25 single-series bars, labels -12..12.
    static final String BARKER;

    static {
        StringBuilder b = new StringBuilder("xychart\n");
        int[] r = {1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 13, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1};
        for (int lag = -12; lag <= 12; lag++) {
            b.append('"').append(lag).append("\" : ").append(r[lag + 12]).append('\n');
        }
        BARKER = b.toString();
    }

    @Test
    void theBarkerChartNamesEveryDroppedLagLabelOnAnOkCaveat() {
        RenderResult r = Sirentide.renderWithDiagnostics(BARKER);
        assertEquals(Outcome.OK, r.diagnostics().outcome(), "the bake still SUCCEEDED (OK, with caveat)");
        String msg = r.diagnostics().message();
        assertTrue(msg.contains("15 x-axis labels"), "the caveat counts the drops: " + msg);
        for (String lag : List.of("-12", "-1", "10", "12")) {
            assertTrue(msg.contains(lag), "the caveat names dropped lag " + lag + ": " + msg);
        }
        assertTrue(r.diagnostics().detail().startsWith("xychart category-label drop:"),
            "the detail crumb records the drop: " + r.diagnostics().detail());
    }

    @Test
    void theReplayAgreesWithWhatTheSvgActuallyDrew() {
        // The replay must not drift from the layout. Cross-check it against the SVG STRUCTURE, which
        // shares no code with the replay: in the single-series bar path each `<g role="bar">` holds
        // its rect, its category-label path and its value-label path, so a dropped category label
        // leaves its group ONE path short.
        String svg = Sirentide.render(BARKER);
        Matcher g = Pattern.compile("<g data-sirentide-role=\"bar\" data-sirentide-id=\"([^\"]*)\"[^>]*>(.*?)</g>",
            Pattern.DOTALL).matcher(svg);
        List<String> drawnWithoutLabel = new ArrayList<>();
        int groups = 0;
        while (g.find()) {
            groups++;
            int paths = g.group(2).split("<path", -1).length - 1;
            if (paths == 1) {
                drawnWithoutLabel.add(g.group(1));
            }
        }
        assertEquals(25, groups, "control: every bar group was found");
        XyChart chart = (XyChart) DslParser.parse(BARKER);
        assertEquals(drawnWithoutLabel, XyChartLayout.categoryLabelLosses(chart).dropped(),
            "the replay names exactly the bars the SVG drew without a label");
        assertEquals(15, drawnWithoutLabel.size(), "control: the drop really happens (15 of 25)");
    }

    @Test
    void theCaveatDoesNotAlterTheSvgBytes() {
        assertEquals(Sirentide.render(BARKER), Sirentide.renderWithDiagnostics(BARKER).svg(),
            "renderWithDiagnostics(barker).svg() is byte-identical to render(barker)");
    }

    @Test
    void aTruncatedLabelIsNamedAsShortened() {
        // A label wider than its slot that still fits an ellipsis is drawn SHORTENED ("Wednesd…"), so
        // the author cannot read it in full either: named separately from a full drop.
        String dsl = "xychart\n\"Monday\" : 1\n\"Wednesday afternoon session\" : 2\n\"Fri\" : 3";
        RenderResult r = Sirentide.renderWithDiagnostics(dsl);
        assertEquals(Outcome.OK, r.diagnostics().outcome());
        assertTrue(r.diagnostics().message().contains("shortened"), r.diagnostics().message());
        assertTrue(r.diagnostics().message().contains("Wednesday afternoon session"), r.diagnostics().message());
        assertFalse(r.diagnostics().message().contains("Monday"), "a label that fits is not named");
    }

    @Test
    void aMultiSeriesLineChartWithCrowdedCategoriesIsCoveredToo() {
        StringBuilder b = new StringBuilder("xychart line\n");
        for (int i = 100; i < 160; i++) {
            b.append('"').append(i).append("\" : ").append(i % 7).append(' ').append(i % 5).append('\n');
        }
        RenderResult r = Sirentide.renderWithDiagnostics(b.toString());
        assertTrue(r.diagnostics().detail().startsWith("xychart category-label drop:"), r.diagnostics().detail());
    }

    @Test
    void aComfortableChartCarriesNoCaveat() {
        RenderResult r = Sirentide.renderWithDiagnostics("xychart\n\"A\" : 1\n\"B\" : 2\n\"C\" : 3");
        assertEquals("Rendered successfully.", r.diagnostics().message(),
            "a chart that loses no label keeps the plain OK message");
    }
}
