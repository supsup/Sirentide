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
    void roleEnumIsClosedAndFlowchartPieEmitNodeEdgeSlice() {
        assertTrue(SirentideRole.isWire("node"));
        assertTrue(SirentideRole.isWire("edge"));
        assertTrue(SirentideRole.isWire("slice"));
        assertFalse(SirentideRole.isWire("script"));
        assertFalse(SirentideRole.isWire("fx"));
        assertFalse(SirentideRole.isWire(null));
        // Flowchart + pie emit exactly node/edge/slice (the slice-1 types, unchanged by slice 2).
        String svg = Sirentide.render("flowchart TD\n  A[Start] --> B[End]\n")
            + Sirentide.render("pie\n  \"A\" : 50\n  \"B\" : 50\n");
        for (Anc a : anchors(svg)) {
            assertTrue(List.of("node", "edge", "slice").contains(a.role()),
                "flowchart/pie emit only node/edge/slice, saw: " + a.role());
        }
    }

    /// Slice-2 CLOSED-SET guard: render one diagram of EVERY anchored type and assert every emitted
    /// `data-sirentide-role` is a member of the closed {@link SirentideRole} enum — producer ⊆ contract
    /// for the whole role vocabulary, not just the flowchart/pie subset above.
    @Test
    void everyAnchoredTypeEmitsOnlyRolesInTheClosedEnum() {
        List<String> corpus = List.of(
            "flowchart TD\n  A[Start] --> B[End]\n",
            "pie\n  \"A\" : 50\n  \"B\" : 50\n",
            "sequence\n  Alice ->> Bob : hi\n  Bob -->> Alice : ok\n",
            "state\n  [*] --> Idle\n  Idle --> Running : go\n  Running --> [*]\n",
            "quadrant\n  \"Feature A\" : [0.3, 0.6]\n  \"Feature B\" : [0.7, 0.2]\n",
            "xychart\n  \"Mon\" : 5\n  \"Tue\" : 8\n",
            "xychart line\n  series: R, C\n  \"Q1\" : 5 3\n  \"Q2\" : 8 6\n",
            "gantt\n  \"Design\" : 0-3\n  \"Build\" : 3-8\n",
            "timeline\n  \"Founded\" : 2020\n  \"Launch\" : 2023\n",
            "classDiagram\n  class Animal {\n    +eat() void\n  }\n  Animal <|-- Dog : inherits\n",
            "erDiagram\n  CUSTOMER ||--o{ ORDER : places\n",
            "gitGraph\n  commit\n  branch develop\n  checkout develop\n  commit\n  checkout main\n"
                + "  merge develop\n");
        for (String dsl : corpus) {
            for (Anc a : anchors(Sirentide.render(dsl))) {
                assertTrue(SirentideRole.isWire(a.role()),
                    "role " + a.role() + " is not in the closed enum (dsl: " + dsl + ")");
            }
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

    // -- slice-2 per-type proofs (the OTHER 9 label/element-bearing types) --------------------------

    /// Every anchor's id matches the contract charset and every seq is 0..N-1 contiguous — the shared
    /// invariant asserted for each per-type render below.
    private static void assertWellFormed(List<Anc> a, int expected) {
        assertEquals(expected, a.size(), "expected " + expected + " anchor groups, got " + a);
        for (Anc x : a) {
            assertTrue(SirentideContract.ANCHOR_ID.matcher(x.id()).matches(), "legal id: " + x.id());
        }
        List<Integer> seqs = a.stream().map(Anc::seq).sorted().toList();
        List<Integer> want = new ArrayList<>();
        for (int i = 0; i < expected; i++) {
            want.add(i);
        }
        assertEquals(want, seqs, "seq is 0..N-1 contiguous, got " + seqs);
    }

    private static long countRole(List<Anc> a, String role) {
        return a.stream().filter(x -> x.role().equals(role)).count();
    }

    /// MUTANT SENTINEL (receipt #3): a sequence with 2 actors + 2 messages must emit exactly 4 anchor
    /// `<g>`s — one per message (role=message, id=from-to) + one per actor (role=actor, id=name) — with
    /// seq 0..3 contiguous (messages first). DROP the message `<g>` wrapper in SequenceLayout (emit the
    /// message shapes bare) and the message-count assertion falls to 0 → RED. Named + observed.
    @Test
    void sequenceEmitsAnchorGroupPerActorAndMessage() {
        List<Anc> a = anchors(Sirentide.render(
            "sequence\n  Alice ->> Bob : hi\n  Bob -->> Alice : ok\n"));
        assertWellFormed(a, 4);
        assertEquals(2, countRole(a, "message"), "two message anchors: " + a);
        assertEquals(2, countRole(a, "actor"), "two actor anchors: " + a);
        assertTrue(a.stream().anyMatch(x -> x.role().equals("message") && x.id().equals("Alice-Bob")),
            "message id is from-to (Alice-Bob): " + a);
        assertTrue(a.stream().anyMatch(x -> x.role().equals("actor") && x.id().equals("Alice")),
            "actor id is the name (Alice): " + a);
        // Messages emit before actors → messages carry the lower seq range.
        int maxMsgSeq = a.stream().filter(x -> x.role().equals("message")).mapToInt(Anc::seq).max().orElse(-1);
        int minActorSeq = a.stream().filter(x -> x.role().equals("actor")).mapToInt(Anc::seq).min().orElse(-1);
        assertTrue(maxMsgSeq < minActorSeq, "messages seq before actors: " + a);
    }

    /// The NOTE anchor (M2 enrichment): a sequence with 2 actors + 1 message + 1 `note over A,B` emits
    /// exactly ONE `role="note"` anchor (id = sanitized note text) in addition to the message + actor
    /// anchors. A `create`/`destroy` adds NO anchor (it only modifies the lifeline). Proves the note box
    /// is wrapped in `<g data-sirentide-role="note">` and its id derives from the text.
    @Test
    void noteEmitsRoleNoteAnchorAndCreateDestroyAddNone() {
        List<Anc> a = anchors(Sirentide.render(
            "sequence\n  Alice ->> Bob : hi\n  note over Alice,Bob : shared note\n"
                + "  create participant Carol\n  Bob ->> Carol : spawn\n  destroy Carol\n"));
        // 2 messages (Alice-Bob, Bob-Carol) + 3 actors (Alice, Bob, Carol) + 1 note = 6 anchors.
        // create/destroy add NONE.
        assertEquals(1, countRole(a, "note"), "exactly one note anchor: " + a);
        assertEquals(2, countRole(a, "message"), "two message anchors: " + a);
        assertEquals(3, countRole(a, "actor"), "three actor anchors: " + a);
        assertTrue(a.stream().anyMatch(x -> x.role().equals("note") && x.id().equals("sharednote")),
            "note id is the sanitized text (sharednote): " + a);
        // every anchor id is charset-legal + role is in the closed enum.
        for (Anc x : a) {
            assertTrue(SirentideContract.ANCHOR_ID.matcher(x.id()).matches(), "legal id: " + x.id());
            assertTrue(SirentideRole.isWire(x.role()), "role in closed enum: " + x.role());
        }
    }

    /// State diagram reuses the flowchart engine: states → node, transitions → edge. `[*]` start/end
    /// pseudostates carry empty labels, so their id falls back to the `__start__`/`__end__` DSL id.
    @Test
    void stateEmitsNodeAndEdgeAnchors() {
        List<Anc> a = anchors(Sirentide.render(
            "state\n  [*] --> Idle\n  Idle --> Running : go\n  Running --> [*]\n"));
        // 4 states ([*]-start, Idle, Running, [*]-end) + 3 transitions.
        assertWellFormed(a, 7);
        assertEquals(4, countRole(a, "node"), "four state nodes: " + a);
        assertEquals(3, countRole(a, "edge"), "three transition edges: " + a);
        assertTrue(a.stream().anyMatch(x -> x.role().equals("node") && x.id().equals("Idle")),
            "state Idle is anchored: " + a);
    }

    @Test
    void quadrantEmitsPointAnchors() {
        List<Anc> a = anchors(Sirentide.render(
            "quadrant\n  \"Feature A\" : [0.3, 0.6]\n  \"Feature B\" : [0.7, 0.2]\n"));
        assertWellFormed(a, 2);
        assertEquals(2, countRole(a, "point"), "two point anchors: " + a);
        assertTrue(a.stream().anyMatch(x -> x.id().equals("FeatureA")), "point id from label: " + a);
    }

    @Test
    void xychartEmitsBarAnchorPerBar() {
        List<Anc> a = anchors(Sirentide.render("xychart\n  \"Mon\" : 5\n  \"Tue\" : 8\n  \"Wed\" : 3\n"));
        assertWellFormed(a, 3);
        assertEquals(3, countRole(a, "bar"), "one bar anchor per bar: " + a);
        assertTrue(a.stream().anyMatch(x -> x.id().equals("Mon")), "bar id from category: " + a);
    }

    @Test
    void xychartLineEmitsBarAnchorPerPresentPoint() {
        // 2 series × 2 categories, all present → 4 point discs, each role=bar (id = category, uniquified).
        List<Anc> a = anchors(Sirentide.render(
            "xychart line\n  series: R, C\n  \"Q1\" : 5 3\n  \"Q2\" : 8 6\n"));
        assertWellFormed(a, 4);
        assertEquals(4, countRole(a, "bar"), "one bar anchor per present point: " + a);
    }

    @Test
    void ganttEmitsBarAnchorPerTask() {
        List<Anc> a = anchors(Sirentide.render("gantt\n  \"Design\" : 0-3\n  \"Build\" : 3-8\n"));
        assertWellFormed(a, 2);
        assertEquals(2, countRole(a, "bar"), "one bar anchor per task: " + a);
        assertTrue(a.stream().anyMatch(x -> x.id().equals("Design")), "bar id from task label: " + a);
    }

    @Test
    void timelineEmitsEventAnchorPerEvent() {
        List<Anc> a = anchors(Sirentide.render(
            "timeline\n  \"Founded\" : 2020\n  \"Series A\" : 2021\n  \"Launch\" : 2023\n"));
        assertWellFormed(a, 3);
        assertEquals(3, countRole(a, "event"), "one event anchor per event: " + a);
        assertTrue(a.stream().anyMatch(x -> x.id().equals("Founded")), "event id from label: " + a);
    }

    @Test
    void classEmitsClassAndEdgeAnchors() {
        List<Anc> a = anchors(Sirentide.render(
            "classDiagram\n  class Animal {\n    +eat() void\n  }\n  Animal <|-- Dog : inherits\n"));
        // Animal + auto-vivified Dog = 2 classes; 1 relation.
        assertWellFormed(a, 3);
        assertEquals(2, countRole(a, "class"), "two class anchors: " + a);
        assertEquals(1, countRole(a, "edge"), "one relation edge: " + a);
        assertTrue(a.stream().anyMatch(x -> x.role().equals("edge") && x.id().equals("Animal-Dog")),
            "edge id is left-right (Animal-Dog): " + a);
    }

    @Test
    void erEmitsEntityAndEdgeAnchors() {
        List<Anc> a = anchors(Sirentide.render(
            "erDiagram\n  CUSTOMER ||--o{ ORDER : places\n"));
        // CUSTOMER + ORDER auto-vivified = 2 entities; 1 relation.
        assertWellFormed(a, 3);
        assertEquals(2, countRole(a, "entity"), "two entity anchors: " + a);
        assertEquals(1, countRole(a, "edge"), "one relation edge: " + a);
        assertTrue(a.stream().anyMatch(x -> x.role().equals("edge") && x.id().equals("CUSTOMER-ORDER")),
            "edge id is left-right (CUSTOMER-ORDER): " + a);
    }

    /// gitGraph: every commit dot is wrapped in a `<g role="commit">` (id = the commit id, else its
    /// branch name, uniquified) and every drawn branch lane in a `<g role="branch">` (id = branch
    /// name). Two commits on main + one on develop + a merge commit = 4 commit anchors; main + develop
    /// = 2 branch anchors. Proves a commit emits role="commit" (receipt #2).
    @Test
    void gitGraphEmitsCommitAndBranchAnchors() {
        List<Anc> a = anchors(Sirentide.render(
            "gitGraph\n  commit\n  commit id: \"fix\"\n  branch develop\n  checkout develop\n"
                + "  commit\n  checkout main\n  merge develop\n"));
        // 3 explicit commits + 1 merge commit = 4 commit anchors; main + develop = 2 branch anchors.
        assertWellFormed(a, 6);
        assertEquals(4, countRole(a, "commit"), "four commit anchors (3 commits + 1 merge): " + a);
        assertEquals(2, countRole(a, "branch"), "two branch anchors (main + develop): " + a);
        assertTrue(a.stream().anyMatch(x -> x.role().equals("commit") && x.id().equals("fix")),
            "the id-labeled commit anchors under its id (fix): " + a);
        assertTrue(a.stream().anyMatch(x -> x.role().equals("branch") && x.id().equals("develop")),
            "the develop branch lane is anchored: " + a);
        // Branch anchors emit before commit anchors → branches carry the lower seq range.
        int maxBranchSeq = a.stream().filter(x -> x.role().equals("branch")).mapToInt(Anc::seq).max().orElse(-1);
        int minCommitSeq = a.stream().filter(x -> x.role().equals("commit")).mapToInt(Anc::seq).min().orElse(-1);
        assertTrue(maxBranchSeq < minCommitSeq, "branches seq before commits: " + a);
    }
}
