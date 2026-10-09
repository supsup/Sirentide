package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.ir.XyChart;
import com.sirentide.layout.XyChartLayout;
import com.sirentide.math.LatteXMathFragmentRenderer;
import com.sirentide.parse.DslParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// F2 of Fixpoint's ruling on the cli-math-batch rebuild: the xychart label-loss check must know
/// whether a math renderer is ACTIVE.
///
/// {@link XyChartLayout#categoryLabelLosses} used to skip every `$...$` label on the theory that math
/// is never ellipsized. That holds only WITH a renderer. Without one (the default bake, `render -`
/// without `--math`) a math label is drawn through the same plain ellipsize as any other label, so
/// 30 categories labelled `$x_{i}$` drew NO category labels at all, carried no caveat, and
/// `render - --strict` exited 0. Measured on the 05e243d jar before this test existed.
///
/// The rule now: no renderer -> a math label is measured as the plain text actually drawn and its
/// loss is reported; renderer active -> it is typeset (never ellipsized), so it is not a slot loss,
/// and one that fails to typeset is the CLI's untypeset-math caveat (CliMathLabelLossTest).
class XyChartMathLabelLossTest {

    /// 30 single-series bars labelled `$x_{1}$` .. `$x_{30}$`.
    static final String MATH30;

    static {
        StringBuilder b = new StringBuilder("xychart\n");
        for (int i = 1; i <= 30; i++) {
            b.append("\"$x_{").append(i).append("}$\" : ").append(i % 7 + 1).append('\n');
        }
        MATH30 = b.toString();
    }

    @Test
    void withoutARendererThirtyMathLabelsAreNamedAsDropped() {
        RenderResult r = Sirentide.renderWithDiagnostics(MATH30);
        assertEquals(Outcome.OK, r.diagnostics().outcome(), "still a successful bake");
        assertTrue(r.diagnostics().detail().startsWith("xychart category-label drop:"),
            "the drop is named: " + r.diagnostics().detail());
        assertTrue(r.diagnostics().message().contains("30 x-axis labels"), r.diagnostics().message());
        assertTrue(r.diagnostics().detail().contains("$x_{1}$"), "named by its raw source: " + r.diagnostics().detail());
    }

    @Test
    void withoutARendererTheReplayAgreesWithTheSvgStructure() {
        // Shares no code with the replay: each single-series `<g role="bar">` holds its rect, its
        // category-label path and its value-label path, so a dropped category label leaves its group
        // one path short.
        String svg = Sirentide.render(MATH30);
        Matcher g = Pattern.compile("<g data-sirentide-role=\"bar\" data-sirentide-id=\"([^\"]*)\"[^>]*>(.*?)</g>",
            Pattern.DOTALL).matcher(svg);
        int groups = 0;
        int withoutLabel = 0;
        while (g.find()) {
            groups++;
            if (g.group(2).split("<path", -1).length - 1 == 1) {
                withoutLabel++;
            }
        }
        assertEquals(30, groups, "control: every bar group was found");
        assertEquals(30, withoutLabel, "control: the default bake really draws none of the 30 labels");
        XyChart chart = (XyChart) DslParser.parse(MATH30);
        assertEquals(withoutLabel, XyChartLayout.categoryLabelLosses(chart, null).dropped().size(),
            "the no-renderer replay names exactly the labels the SVG lacks");
    }

    @Test
    void withARendererTypesetLabelsAreNotSlotLosses() {
        RenderResult r = Sirentide.renderWithDiagnostics(MATH30, new LatteXMathFragmentRenderer());
        assertEquals(Outcome.OK, r.diagnostics().outcome());
        assertFalse(r.diagnostics().detail().contains("xychart category-label drop"),
            "a typeset label is not ellipsized, so it is not a slot loss: " + r.diagnostics().detail());
        assertTrue(r.svg().contains("<g fill="), "control: the labels really were typeset (MathBox wrappers)");
    }

    @Test
    void withARendererThatTypesetsNothingTheLabelsAreDrawnRawNotDropped() {
        // A renderer that is ACTIVE but fails every run: MathLabel degrades each to its raw `$...$`
        // source, drawn un-ellipsized. Nothing is lost to the slot, so the layout caveat stays silent;
        // naming the failed typesetting is the CLI's untypeset-math caveat, not this one.
        MathFragmentRenderer failing = (latex, size) -> Optional.empty();
        RenderResult r = Sirentide.renderWithDiagnostics(MATH30, failing);
        assertFalse(r.diagnostics().detail().contains("xychart category-label drop"), r.diagnostics().detail());
        String svg = r.svg();
        Matcher g = Pattern.compile("<g data-sirentide-role=\"bar\"[^>]*>(.*?)</g>", Pattern.DOTALL).matcher(svg);
        List<Integer> paths = new ArrayList<>();
        while (g.find()) {
            paths.add(g.group(1).split("<path", -1).length - 1);
        }
        assertEquals(30, paths.size());
        assertTrue(paths.stream().allMatch(n -> n >= 2), "every bar carries a drawn label: " + paths);
    }

    @Test
    void aMixedChartNamesOnlyTheLostLabels() {
        // A plain label that fits is not named, and a math label that fits its slot even as raw text
        // is not named either: the no-renderer rule measures what is DRAWN, it does not flag math.
        String dsl = "xychart\n\"A\" : 1\n\"$x$\" : 2\n\"B\" : 3\n";
        RenderResult r = Sirentide.renderWithDiagnostics(dsl);
        assertEquals("Rendered successfully.", r.diagnostics().message(), r.diagnostics().detail());
    }

    @Test
    void thePlayThroughChannelBranchesOnTheRendererToo() {
        // renderFramesWithDiagnostics reaches labelDropCaveat at its own call sites; each must pass the
        // renderer it laid out with, or a typeset deck would be told its labels were dropped.
        assertTrue(Sirentide.renderFramesWithDiagnostics(MATH30).diagnostics().detail()
            .startsWith("xychart category-label drop:"), "no renderer: the drop is named on the deck");
        assertFalse(Sirentide.renderFramesWithDiagnostics(MATH30, new LatteXMathFragmentRenderer())
            .diagnostics().detail().contains("xychart category-label drop"), "renderer: typeset, not dropped");
    }
}
