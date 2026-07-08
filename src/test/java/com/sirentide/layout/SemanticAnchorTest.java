package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.contract.SirentideContract;
import com.sirentide.contract.SirentideRole;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// The SEMANTIC-ANCHOR infrastructure (plan sirentide-semantic-anchor-g, contract sirentide/67): the
/// closed role enum, the deterministic charset-safe id sanitizer, the per-diagram unique-id + emit-
/// order-seq assigner, and the proof that a real FLOWCHART wraps each node/edge (and a PIE each slice)
/// in ONE `<g data-sirentide-*>`. The OTHER types stay un-anchored this slice (covered by their own
/// byte-identical goldens / shape-count tests).
class SemanticAnchorTest {

    /// Matches ONE emitted anchor group open-tag → (role, id, seq).
    private static final Pattern ANCHOR = Pattern.compile(
        "<g data-sirentide-role=\"([^\"]*)\" data-sirentide-id=\"([^\"]*)\" data-sirentide-seq=\"([^\"]*)\">");

    private record Anc(String role, String id, int seq) {}

    private static List<Anc> anchors(String svg) {
        List<Anc> out = new ArrayList<>();
        Matcher m = ANCHOR.matcher(svg);
        while (m.find()) {
            out.add(new Anc(m.group(1), m.group(2), Integer.parseInt(m.group(3))));
        }
        return out;
    }

    // -- the id SANITIZER: security-relevant, never emits a char outside [A-Za-z0-9_-] ---------------

    @Test
    void sanitizerNeverEmitsAnIllegalCharEvenForAHostileLabel() {
        // A markup-injection attempt: angle brackets, a quote, parens — ALL dropped, letters kept.
        assertEquals("scriptab", Anchor.sanitizeId("<script>a\"b"));
        // spaces + punctuation dropped, alnum/underscore/hyphen kept.
        assertEquals("Hello_World-2", Anchor.sanitizeId("Hello _World- 2!"));
        // unicode (non-ascii) is outside the charset → dropped entirely; ascii alnum kept.
        assertEquals("A1", Anchor.sanitizeId("日本A1"));
        // a label of pure illegal chars sanitizes to empty (the assigner supplies a fallback).
        assertEquals("", Anchor.sanitizeId("()[]{} \t\"'<>&"));
        assertEquals("", Anchor.sanitizeId(null));
        // truncated to 32 chars.
        assertEquals(32, Anchor.sanitizeId("x".repeat(100)).length());
        // whatever the input, the (non-empty) output ALWAYS matches the contract id pattern.
        for (String raw : List.of("<script>a\"b", "Hello _World- 2!", "café",
            "A".repeat(200), "9lives", "__", "--")) {
            String id = Anchor.sanitizeId(raw);
            if (!id.isEmpty()) {
                assertTrue(SirentideContract.ANCHOR_ID.matcher(id).matches(),
                    "sanitized id must be charset-legal: " + id + " (from " + raw + ")");
            }
        }
    }

    @Test
    void anchorConstructorRejectsAnIllegalId() {
        // The record is the last-line guard: a non-charset id can never be constructed.
        assertThrows(IllegalArgumentException.class, () -> new Anchor(SirentideRole.NODE, "a b", 0));
        assertThrows(IllegalArgumentException.class, () -> new Anchor(SirentideRole.NODE, "", 0));
        assertThrows(IllegalArgumentException.class,
            () -> new Anchor(SirentideRole.NODE, "x".repeat(33), 0));
        assertThrows(IllegalArgumentException.class, () -> new Anchor(SirentideRole.NODE, "ok", -1));
        assertThrows(IllegalArgumentException.class, () -> new Anchor(null, "ok", 0));
        // a legal one constructs fine.
        Anchor a = new Anchor(SirentideRole.EDGE, "A-B_1", 3);
        assertEquals("edge", a.role().wire());
    }

    // -- the ASSIGNER: unique ids + monotonic emit-order seq ----------------------------------------

    @Test
    void assignerUniquifiesCollidingIdsAndCountsSeqInOrder() {
        AnchorAssigner asg = new AnchorAssigner();
        Anchor a = asg.assign(SirentideRole.NODE, "Same");
        Anchor b = asg.assign(SirentideRole.NODE, "Same");
        Anchor c = asg.assign(SirentideRole.NODE, "Same");
        assertEquals("Same", a.id());
        assertEquals("Same-1", b.id());
        assertEquals("Same-2", c.id());
        assertEquals(0, a.seq());
        assertEquals(1, b.seq());
        assertEquals(2, c.seq());
        // an all-illegal label falls back to the role name (still charset-legal).
        Anchor fb = asg.assign(SirentideRole.SLICE, "()[]");
        assertEquals("slice", fb.id());
        // uniquification re-truncates so the id stays within 32 chars.
        AnchorAssigner asg2 = new AnchorAssigner();
        String long32 = "x".repeat(32);
        assertEquals(long32, asg2.assign(SirentideRole.NODE, long32).id());
        String second = asg2.assign(SirentideRole.NODE, long32).id();
        assertEquals("x".repeat(30) + "-1", second);
        assertTrue(second.length() <= 32);
    }

    // -- the closed role enum -----------------------------------------------------------------------

    @Test
    void roleEnumIsClosedAndThisSliceEmitsOnlyNodeEdgeSlice() {
        assertTrue(SirentideRole.isWire("node"));
        assertTrue(SirentideRole.isWire("edge"));
        assertTrue(SirentideRole.isWire("slice"));
        assertFalse(SirentideRole.isWire("script"));
        assertFalse(SirentideRole.isWire("fx"));
        assertFalse(SirentideRole.isWire(null));
        // Everything actually EMITTED this slice is one of the three proven roles.
        String svg = Sirentide.render("flowchart TD\n  A[Start] --> B[End]\n")
            + Sirentide.render("pie\n  \"A\" : 50\n  \"B\" : 50\n");
        for (Anc a : anchors(svg)) {
            assertTrue(List.of("node", "edge", "slice").contains(a.role()),
                "this slice emits only node/edge/slice, saw: " + a.role());
        }
    }

    // -- the FLOWCHART proof (nodes + edges) --------------------------------------------------------

    /// MUTANT SENTINEL (receipt #4): a flowchart with 2 nodes + 1 edge must emit exactly 3 anchored
    /// `<g>`s — one per edge, one per node — with role ∈ {node,edge}, ids matching the id pattern, and
    /// seq 0..N contiguous in emit order (edge first, then nodes). DROP the `<g>` wrapper in
    /// FlowchartLayout (emit the shapes bare) and this assertion falls to 0 groups → RED.
    @Test
    void flowchartEmitsThreeAnchorGroupsForTwoNodesAndOneEdge() {
        List<Anc> a = anchors(Sirentide.render("flowchart TD\n  A[Start] --> B[End]\n"));
        assertEquals(3, a.size(), "2 nodes + 1 edge → 3 anchor groups, got " + a);
        // roles ∈ {node, edge}; exactly one edge + two nodes.
        long edges = a.stream().filter(x -> x.role().equals("edge")).count();
        long nodes = a.stream().filter(x -> x.role().equals("node")).count();
        assertEquals(1, edges, "one edge anchor");
        assertEquals(2, nodes, "two node anchors");
        // ids all match the contract pattern.
        for (Anc x : a) {
            assertTrue(SirentideContract.ANCHOR_ID.matcher(x.id()).matches(), "legal id: " + x.id());
        }
        // seq is 0..2 contiguous.
        List<Integer> seqs = a.stream().map(Anc::seq).sorted().toList();
        assertEquals(List.of(0, 1, 2), seqs, "seq is 0..N contiguous, got " + seqs);
        // the edge is emitted first (seq 0), the human ids are the labels / from-to.
        assertTrue(a.stream().anyMatch(x -> x.role().equals("edge") && x.id().equals("A-B")),
            "edge id is from-to (A-B): " + a);
        assertTrue(a.stream().anyMatch(x -> x.role().equals("node") && x.id().equals("Start")), "node Start: " + a);
        assertTrue(a.stream().anyMatch(x -> x.role().equals("node") && x.id().equals("End")), "node End: " + a);
    }

    @Test
    void aNodeLabelWithSpacesAndSymbolsYieldsALegalSanitizedId() {
        List<Anc> a = anchors(Sirentide.render("flowchart TD\n  A[Hello World!] --> B[x]\n"));
        Anc hello = a.stream().filter(x -> x.role().equals("node")).findFirst().orElseThrow();
        assertEquals("HelloWorld", hello.id(), "spaces + '!' stripped to a legal id");
        assertTrue(SirentideContract.ANCHOR_ID.matcher(hello.id()).matches());
    }

    @Test
    void twoSameLabelNodesGetUniqueIds() {
        List<Anc> a = anchors(Sirentide.render("flowchart TD\n  A[Same] --> B[Same]\n"));
        List<String> nodeIds = a.stream().filter(x -> x.role().equals("node")).map(Anc::id).toList();
        assertEquals(2, nodeIds.size());
        assertNotEquals(nodeIds.get(0), nodeIds.get(1), "same label → distinct ids, got " + nodeIds);
        assertTrue(nodeIds.contains("Same") && nodeIds.contains("Same-1"),
            "collision resolved by an index suffix, got " + nodeIds);
    }

    /// A hostile node label can NEVER place an illegal char into the emitted `<g>` id — end to end
    /// through the real render path (this is the security property the layer was gated on).
    @Test
    void aHostileNodeLabelCannotBreakOutOfTheAnchorAttribute() {
        String svg = Sirentide.render("flowchart TD\n  A[<script>a\"b] --> B[ok]\n");
        for (Anc x : anchors(svg)) {
            assertTrue(SirentideContract.ANCHOR_ID.matcher(x.id()).matches(),
                "emitted id stays charset-legal for a hostile label: " + x.id());
        }
        assertFalse(svg.contains("<script>"), "the label never reaches the output as markup: " + svg);
    }

    // -- the PIE proof (slices) ---------------------------------------------------------------------

    @Test
    void pieEmitsOneSliceAnchorPerDrawnSlice() {
        List<Anc> a = anchors(Sirentide.render("pie\n  \"Reviews\" : 40\n  \"Builds\" : 30\n  \"Docs\" : 30\n"));
        assertEquals(3, a.size(), "3 slices → 3 slice anchors, got " + a);
        assertTrue(a.stream().allMatch(x -> x.role().equals("slice")), "all role=slice: " + a);
        assertEquals(List.of(0, 1, 2), a.stream().map(Anc::seq).sorted().toList());
        assertTrue(a.stream().anyMatch(x -> x.id().equals("Reviews")), "slice id from label: " + a);
    }

    @Test
    void pieSkipsNonPositiveSlicesAndDoesNotConsumeSeqForThem() {
        // A negative slice is not drawn → no anchor, no seq consumed (seq stays 0..drawn-1).
        List<Anc> a = anchors(Sirentide.render("pie\n  \"A\" : 10\n  \"Refund\" : -5\n  \"B\" : 10\n"));
        assertEquals(2, a.size(), "only the 2 positive slices are anchored, got " + a);
        assertEquals(List.of(0, 1), a.stream().map(Anc::seq).sorted().toList());
    }
}
