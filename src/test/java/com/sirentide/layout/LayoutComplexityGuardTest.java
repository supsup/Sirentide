package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.contract.SirentideRole;
import com.sirentide.ir.Empty;
import com.sirentide.ir.Sankey;
import com.sirentide.ir.SankeyFlow;
import com.sirentide.ir.SeqNote;
import com.sirentide.ir.Sequence;
import com.sirentide.parse.DslParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Red-first discriminators for the three bounded-complexity fixes in plan
/// 18863d64-4f81-4970-bb50-30caf9e20e35. These assert deterministic operation counts and exact
/// allow/deny boundaries instead of relying on wall-clock timing.
class LayoutComplexityGuardTest {

    @Test
    void duplicateAnchorSuffixSearchUsesOneSetProbePerAssignedAnchor() {
        AnchorAssigner assigner = new AnchorAssigner();
        Anchor last = null;
        for (int i = 0; i < 4_000; i++) {
            last = assigner.assign(SirentideRole.NODE, "duplicate");
        }

        assertEquals("duplicate-3999", last.id(), "suffix order remains byte-for-byte deterministic");
        assertEquals(4_000, assigner.uniquenessProbeCount(),
            "a remembered suffix cursor claims each duplicate in one set probe; restarting at -1 "
                + "would take 8,002,000 probes");
    }

    @Test
    void anchorCursorStillProbesCrossBaseTruncationCollisionsInLegacyOrder() {
        AnchorAssigner assigner = new AnchorAssigner();
        String prefix = "x".repeat(30);
        String first = prefix + "aa";
        String second = prefix + "bb";

        assertEquals(first, assigner.assign(SirentideRole.NODE, first).id());
        assertEquals(prefix + "-1", assigner.assign(SirentideRole.NODE, first).id());
        assertEquals(second, assigner.assign(SirentideRole.NODE, second).id());
        assertEquals(prefix + "-2", assigner.assign(SirentideRole.NODE, second).id());
        assertEquals(prefix + "-3", assigner.assign(SirentideRole.NODE, first).id(),
            "a suffix occupied later by another truncated base is skipped exactly as before");
        assertEquals(7, assigner.uniquenessProbeCount(),
            "five successful claims plus the two real cross-base collisions");
    }

    @Test
    void sequenceNoteBucketingInspectsEachNoteExactlyOnceAndKeepsBucketOrder() {
        List<SeqNote> notes = new ArrayList<>();
        for (int i = 0; i < 800; i++) {
            notes.add(new SeqNote("over", List.of("A"), "note-" + i, i % 20));
        }

        SequenceLayout.NoteBuckets buckets = SequenceLayout.bucketNotes(notes, 800);

        assertEquals(800, buckets.inspectedNotes(),
            "800 notes across 801 message boundaries require 800 inspections, not 640,800");
        assertEquals(40, buckets.at(7).size(), "all notes for one boundary share a bucket");
        assertEquals(List.of("note-7", "note-27", "note-47"),
            buckets.at(7).stream().limit(3).map(SeqNote::text).toList(),
            "declaration order is stable within a bucket");
        assertEquals("note-787", buckets.at(7).getLast().text(), "the stable final member");
        assertTrue(buckets.at(799).isEmpty(), "a boundary with no notes stays allocation-light");
    }

    @Test
    void sequenceNoteCapAcceptsTheBoundaryAndRejectsTheFirstExcessNoteLoudly() {
        assertEquals(DslParser.MAX_DATA_ROWS, DslParser.MAX_SEQUENCE_NOTES,
            "notes share the existing sequence time-axis row bound");
        StringBuilder source = new StringBuilder("sequence\nA ->> B : register\n");
        for (int i = 0; i < DslParser.MAX_SEQUENCE_NOTES; i++) {
            source.append("note over A : n\n");
        }

        Sequence atCap = assertInstanceOf(Sequence.class, DslParser.parse(source.toString()));
        assertEquals(DslParser.MAX_SEQUENCE_NOTES, atCap.notes().size(),
            "the exact note boundary remains accepted");

        List<SeqNote> directOverflow = new ArrayList<>(atCap.notes());
        directOverflow.add(new SeqNote("over", List.of("A"), "direct excess", 1));
        Sequence direct = new Sequence(atCap.actors(), atCap.messages(), atCap.textColor(),
            atCap.nodeColor(), atCap.bodyHadContent(), atCap.blocks(), directOverflow,
            atCap.lifecycles());
        IllegalStateException directFailure = assertThrows(IllegalStateException.class,
            () -> SequenceLayout.layout(direct),
            "direct IR callers receive the same loud note bound as the DSL path");
        assertTrue(directFailure.getMessage().contains("MAX_LAYOUT_WORK"));

        source.append("note over A : excess\n");
        String overCap = source.toString();
        assertInstanceOf(Empty.class, DslParser.parse(overCap),
            "the first excess valid note rejects the whole diagram instead of silently omitting it");
        RenderResult result = Sirentide.renderWithDiagnostics(overCap);
        assertEquals(Outcome.PARSE_ERROR, result.diagnostics().outcome(),
            "the cap breach must be visible on the diagnostics channel");
        assertEquals("parse", result.diagnostics().stage());
        assertEquals(Sirentide.render(overCap), result.svg(),
            "diagnostics do not alter the guarded inert-shell bake");
    }

    @Test
    void sankeyRelaxationAllowsTheExactWorkBudgetAndRejectsTheNextProbe() {
        List<SankeyFlow> cycle = cycle(3);
        Map<String, Integer> index = index(cycle);

        SankeyLayout.ColumnRelaxation exact = SankeyLayout.relaxColumns(cycle, index, 9);
        assertEquals(9, exact.work());
        assertArrayEquals(new int[] {2, 2, 2}, exact.columns(),
            "the in-budget cycle keeps the legacy n-pass then clamp result");

        IllegalStateException exceeded = assertThrows(IllegalStateException.class,
            () -> SankeyLayout.relaxColumns(cycle, index, 8));
        assertTrue(exceeded.getMessage().contains("MAX_LAYOUT_WORK"),
            "the deterministic budget breach is nameable by diagnostics");
    }

    @Test
    void sankeyProductionBudgetIsExactAndItsDiagnosticIsKnownNotARendererBug() {
        assertDoesNotThrow(() -> SankeyLayout.layout(new Sankey(cycle(500))),
            "500^2 edge inspections sit exactly on the production budget");
        assertThrows(IllegalStateException.class,
            () -> SankeyLayout.layout(new Sankey(cycle(501))),
            "the first inspection past the production budget aborts before quadratic work continues");

        String overBudget = sankeyDsl(501);
        RenderResult result = Sirentide.renderWithDiagnostics(overBudget);
        assertEquals(Outcome.OUTPUT_CAP_EXCEEDED, result.diagnostics().outcome(),
            "a known layout budget is a bounded-cap degrade, not RENDER_BUG");
        assertEquals("layout", result.diagnostics().stage());
        assertTrue(result.diagnostics().detail().contains("MAX_LAYOUT_WORK"));
        assertEquals(Sirentide.render(overBudget), result.svg(),
            "the diagnostic bake remains byte-identical to the guarded render");
    }

    @Test
    void reverseDeclaredAcyclicChainAlsoTripsTheWorkGuardLoudly() {
        List<SankeyFlow> smallDag = reverseDeclaredChain(4);
        Map<String, Integer> smallIndex = index(smallDag);
        SankeyLayout.ColumnRelaxation exact = SankeyLayout.relaxColumns(smallDag, smallIndex, 12);
        assertEquals(12, exact.work(), "four passes over three reverse-declared edges");
        assertArrayEquals(new int[] {2, 3, 1, 0}, exact.columns(),
            "the in-budget DAG retains its exact legacy longest-path columns");
        assertThrows(IllegalStateException.class,
            () -> SankeyLayout.relaxColumns(smallDag, smallIndex, 11),
            "the next required edge inspection crosses the supplied exact budget");

        List<SankeyFlow> overBudgetDag = reverseDeclaredChain(501);
        assertThrows(IllegalStateException.class,
            () -> SankeyLayout.layout(new Sankey(overBudgetDag)),
            "an acyclic graph is still bounded when declaration order requires more than 250k work");
        String dsl = sankeyDsl(overBudgetDag);
        RenderResult result = Sirentide.renderWithDiagnostics(dsl);
        assertEquals(Outcome.OUTPUT_CAP_EXCEEDED, result.diagnostics().outcome());
        assertEquals("layout", result.diagnostics().stage());
        assertTrue(result.diagnostics().message().contains("Reduce the number of rows or annotations"),
            "guidance stays neutral to whether ordering or topology drove the work");
        assertEquals(Sirentide.render(dsl), result.svg(), "the acyclic cap degrade is byte-identical");
    }

    @Test
    void inBudgetCycleRetainsTheLegacyByteOutput() throws Exception {
        String dsl = "sankey\nA,B,1\nB,C,1\nC,A,1\n";
        String svg = Sirentide.render(dsl);
        String digest = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(svg.getBytes(StandardCharsets.UTF_8)));

        assertEquals("4a68eb2f734fbde5751b3f62a169d3122394beb4f2fa60c105306b0597df722a",
            digest, "hash captured from untouched origin/main before the work-budget refactor");
        assertEquals(svg, Sirentide.render(dsl), "the legacy cycle output remains deterministic");
    }

    private static List<SankeyFlow> cycle(int nodes) {
        List<SankeyFlow> flows = new ArrayList<>(nodes);
        for (int i = 0; i < nodes; i++) {
            flows.add(new SankeyFlow("n" + i, "n" + ((i + 1) % nodes), 1));
        }
        return flows;
    }

    private static Map<String, Integer> index(List<SankeyFlow> flows) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (SankeyFlow flow : flows) {
            index.putIfAbsent(flow.source(), index.size());
            index.putIfAbsent(flow.target(), index.size());
        }
        return index;
    }

    private static List<SankeyFlow> reverseDeclaredChain(int nodes) {
        List<SankeyFlow> flows = new ArrayList<>(nodes - 1);
        for (int i = nodes - 2; i >= 0; i--) {
            flows.add(new SankeyFlow("n" + i, "n" + (i + 1), 1));
        }
        return flows;
    }

    private static String sankeyDsl(int nodes) {
        return sankeyDsl(cycle(nodes));
    }

    private static String sankeyDsl(List<SankeyFlow> flows) {
        StringBuilder source = new StringBuilder("sankey\n");
        for (SankeyFlow flow : flows) {
            source.append(flow.source()).append(',').append(flow.target()).append(",1\n");
        }
        return source.toString();
    }
}
