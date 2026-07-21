package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.contract.SirentideContract;
import com.sirentide.emit.SvgEmitter;
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
                + "  merge develop\n",
            "journey\n  title Day\n  section Go\n    Make tea: 5: Me\n    Commute: 3: Me, Cat\n",
            "mindmap\n  root Root\n    Origins\n      History\n    Tools\n      Mermaid\n",
            "sankey\n  Coal,Electricity,25\n  Gas,Electricity,15\n  Electricity,Homes,20\n"
                + "  Electricity,Industry,20\n",
            "matrix\n  cols: snapshot, bare\n  \"ID1 claim-on-no-signal\" : pass, fail\n"
                + "  \"PC1 soft-intent\" : partial, na\n",
            "tensornetwork\n  mps A B C D\n",
            "tensornetwork\n  mpo A B C\n",
            "knot\n  type: trefoil\n",
            "knot\n  type: figure8\n",
            "knot\n  type: unknot\n");
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

    /// journey (receipt #2): every task's point + name + actor labels wrap in ONE `<g role="task">`
    /// (id = the task name, uniquified), seq 0..N-1 in declaration order across all sections. A journey
    /// with 2 sections × (2 + 1) = 3 tasks → exactly 3 task anchors; the section brackets, satisfaction
    /// line, and axes stay bare. Proves a task emits role="task". DROP the `<g>` wrapper in
    /// JourneyLayout (emit the shapes bare) and the count falls to 0 → RED.
    @Test
    void journeyEmitsATaskAnchorPerTask() {
        List<Anc> a = anchors(Sirentide.render(
            "journey\n  title Day\n  section Go to work\n    Make tea: 5: Me\n    Commute: 3: Me, Cat\n"
                + "  section Do work\n    Code: 5: Me\n"));
        assertWellFormed(a, 3);
        assertEquals(3, countRole(a, "task"), "one task anchor per task: " + a);
        assertTrue(a.stream().anyMatch(x -> x.role().equals("task") && x.id().equals("Maketea")),
            "task id is the sanitized task name (Maketea): " + a);
        assertTrue(a.stream().anyMatch(x -> x.role().equals("task") && x.id().equals("Commute")),
            "the multi-actor task is anchored (Commute): " + a);
    }

    /// mindmap (receipt #2): every tree node's box + label wraps in ONE `<g role="node">` (id = the
    /// node text, uniquified) and every parent→child connector in ONE `<g role="edge">` (id =
    /// parent-child). A tree with 4 nodes (root + 3) → 4 node anchors + 3 edge anchors; edges emit
    /// before nodes (lower seq range). Proves a mindmap node emits role="node". DROP the node `<g>`
    /// wrapper in MindmapLayout and the node count falls to 0 → RED.
    @Test
    void mindmapEmitsANodeAnchorPerNodeAndAnEdgePerConnector() {
        List<Anc> a = anchors(Sirentide.render(
            "mindmap\n  root Root idea\n    Origins\n    Tools\n      Mermaid\n"));
        // 4 nodes (Root idea, Origins, Tools, Mermaid) + 3 edges (Root-Origins, Root-Tools, Tools-Mermaid).
        assertWellFormed(a, 7);
        assertEquals(4, countRole(a, "node"), "four node anchors: " + a);
        assertEquals(3, countRole(a, "edge"), "three edge anchors (one per connector): " + a);
        assertTrue(a.stream().anyMatch(x -> x.role().equals("node") && x.id().equals("Rootidea")),
            "the root node anchors under its sanitized text (Rootidea): " + a);
        assertTrue(a.stream().anyMatch(x -> x.role().equals("edge") && x.id().equals("Rootidea-Origins")),
            "an edge id is parent-child (Rootidea-Origins): " + a);
        // Edges emit before nodes → edges carry the lower seq range.
        int maxEdgeSeq = a.stream().filter(x -> x.role().equals("edge")).mapToInt(Anc::seq).max().orElse(-1);
        int minNodeSeq = a.stream().filter(x -> x.role().equals("node")).mapToInt(Anc::seq).min().orElse(-1);
        assertTrue(maxEdgeSeq < minNodeSeq, "edges seq before nodes: " + a);
    }

    /// sankey (receipt #2): every derived node's bar + label wraps in ONE `<g role="node">` (id = the
    /// node name, uniquified) and every flow band in ONE `<g role="flow">` (id = source-target). The
    /// Coal/Gas → Electricity → Homes/Industry graph has 5 derived nodes + 4 flows → 4 flow anchors + 5
    /// node anchors; flows emit before nodes (lower seq range). Proves a sankey node emits role="node"
    /// AND a band emits the NEW role="flow". DROP the node `<g>` wrapper in SankeyLayout and the node
    /// count falls to 0 → RED.
    @Test
    void sankeyEmitsANodeAnchorPerNodeAndAFlowAnchorPerBand() {
        List<Anc> a = anchors(Sirentide.render(
            "sankey\n  Coal,Electricity,25\n  Gas,Electricity,15\n  Electricity,Homes,20\n"
                + "  Electricity,Industry,20\n"));
        // 5 nodes (Coal, Electricity, Gas, Homes, Industry) + 4 flows.
        assertWellFormed(a, 9);
        assertEquals(5, countRole(a, "node"), "five node anchors: " + a);
        assertEquals(4, countRole(a, "flow"), "four flow anchors (one per band): " + a);
        assertTrue(a.stream().anyMatch(x -> x.role().equals("node") && x.id().equals("Electricity")),
            "the middle node anchors under its name (Electricity): " + a);
        assertTrue(a.stream().anyMatch(x -> x.role().equals("flow") && x.id().equals("Coal-Electricity")),
            "a flow id is source-target (Coal-Electricity): " + a);
        // "flow" is a member of the closed role enum.
        assertTrue(SirentideRole.isWire("flow"), "flow is a closed-enum role");
        // Flows emit before nodes → flows carry the lower seq range.
        int maxFlowSeq = a.stream().filter(x -> x.role().equals("flow")).mapToInt(Anc::seq).max().orElse(-1);
        int minNodeSeq = a.stream().filter(x -> x.role().equals("node")).mapToInt(Anc::seq).min().orElse(-1);
        assertTrue(maxFlowSeq < minNodeSeq, "flows seq before nodes: " + a);
    }

    /// matrix (plan sirentide-matrix-semantic-anchors): every DATA (verdict) cell wraps in ONE
    /// `<g role="cell">` (id = its `r<row>c<col>` coordinate) in ROW-MAJOR reading order; the header
    /// band + row-label column are structural and stay bare. A 2-row × 3-col matrix → exactly 2·3 = 6
    /// cell anchors, seq 0..5 contiguous top-to-bottom, left-to-right. Proves a matrix cell emits the
    /// NEW role="cell". DROP the `<g>` wrapper in MatrixLayout (emit the cell shapes bare) and the
    /// count falls to 0 → RED.
    @Test
    void matrixEmitsACellAnchorPerDataCellInRowMajorOrder() {
        List<Anc> a = anchors(Sirentide.render(
            "matrix\n  cols: snapshot, bare, notes\n  \"row one\" : pass, fail, partial\n"
                + "  \"row two\" : na, pass, fail\n"));
        // 2 rows × 3 columns → 6 data-cell anchors (header + row-label column are NOT anchored).
        assertWellFormed(a, 6);
        assertEquals(6, countRole(a, "cell"), "one cell anchor per data cell (N·M): " + a);
        assertTrue(SirentideRole.isWire("cell"), "cell is a closed-enum role");
        // Ids are coordinate-derived (r<row>c<col>), NOT the cell/row/col text.
        for (Anc x : a) {
            assertTrue(x.id().matches("r\\d+c\\d+"), "cell id is its coordinate (r<row>c<col>): " + x.id());
        }
        assertTrue(a.stream().anyMatch(x -> x.id().equals("r0c0")), "top-left cell is r0c0: " + a);
        assertTrue(a.stream().anyMatch(x -> x.id().equals("r1c2")), "bottom-right cell is r1c2: " + a);
        // ROW-MAJOR seq: the first row's three cells (r0c0..r0c2) carry seq 0..2, the second row 3..5.
        assertEquals(0, seqOf(a, "r0c0"));
        assertEquals(1, seqOf(a, "r0c1"));
        assertEquals(2, seqOf(a, "r0c2"));
        assertEquals(3, seqOf(a, "r1c0"));
        assertEquals(4, seqOf(a, "r1c1"));
        assertEquals(5, seqOf(a, "r1c2"));
    }

    private static int seqOf(List<Anc> a, String id) {
        return a.stream().filter(x -> x.id().equals(id)).mapToInt(Anc::seq).findFirst().orElseThrow();
    }

    /// tensornetwork (review sir330 BLOCKER 1): every tensor CORE's disc + dangling leg(s) + in-disc
    /// label wraps in ONE `<g role="node">` (id = the core label, uniquified) and every virtual BOND in
    /// ONE `<g role="edge">` (id = the adjacent core labels, `left-right`). A 4-core `mps A B C D` chain
    /// → 3 bond edges + 4 core nodes = 7 anchor groups, seq 0..6 contiguous; bonds emit before cores so
    /// they carry the lower seq range. Proves the type is no longer emitting bare, un-anchored shapes
    /// (the defect: roleAnchors=0 / seqAnchors=0). DROP the `<g>` wrappers in TensorNetworkLayout (emit
    /// the shapes bare) and every count below falls to 0 → RED.
    @Test
    void tensorNetworkEmitsNodeAndEdgeAnchors() {
        List<Anc> a = anchors(Sirentide.render("tensornetwork\n  mps A B C D\n"));
        assertWellFormed(a, 7);
        assertEquals(4, countRole(a, "node"), "four core node anchors: " + a);
        assertEquals(3, countRole(a, "edge"), "three bond edge anchors (N-1): " + a);
        // Core ids are the labels; bond ids are the adjacent-core pair (left-right).
        for (String coreId : List.of("A", "B", "C", "D")) {
            assertTrue(a.stream().anyMatch(x -> x.role().equals("node") && x.id().equals(coreId)),
                "core " + coreId + " is anchored under its label: " + a);
        }
        assertTrue(a.stream().anyMatch(x -> x.role().equals("edge") && x.id().equals("A-B")),
            "a bond id is the adjacent core pair (A-B): " + a);
        assertTrue(a.stream().anyMatch(x -> x.role().equals("edge") && x.id().equals("C-D")),
            "the last bond id is C-D: " + a);
        // Bonds emit before cores → bonds carry the lower seq range (the play order plays bonds first).
        int maxEdgeSeq = a.stream().filter(x -> x.role().equals("edge")).mapToInt(Anc::seq).max().orElse(-1);
        int minNodeSeq = a.stream().filter(x -> x.role().equals("node")).mapToInt(Anc::seq).min().orElse(-1);
        assertTrue(maxEdgeSeq < minNodeSeq, "bonds (edges) seq before cores (nodes): " + a);
        // The role vocabulary is the CLOSED graph one — no new role was invented for this type.
        for (Anc x : a) {
            assertTrue(List.of("node", "edge").contains(x.role()),
                "tensornetwork emits only the closed node/edge roles, saw: " + x.role());
        }
    }

    /// tensornetwork MPO: each core gains a SECOND (operator) UP leg, but that leg lives INSIDE the
    /// core's node group, so the anchor counts are unchanged — a 3-core `mpo A B C` still has exactly 3
    /// node anchors + 2 bond edges = 5 groups. Proves the operator leg does not spawn a stray anchor.
    @Test
    void tensorNetworkMpoAnchorsMatchMpsShape() {
        List<Anc> a = anchors(Sirentide.render("tensornetwork\n  mpo A B C\n"));
        assertWellFormed(a, 5);
        assertEquals(3, countRole(a, "node"), "three core node anchors (mpo): " + a);
        assertEquals(2, countRole(a, "edge"), "two bond edge anchors (mpo): " + a);
    }

    /// tensornetwork DUPLICATE-LABEL id stability (review sir330): two cores that share a label must
    /// still get DISTINCT, deterministic anchor ids — the assigner uniquifies `A` → `A`, `A-1`. Without
    /// per-diagram uniquification a narrator/consumer could not address the second core. `mps A A B`
    /// → cores A, A-1, B (3 nodes) + bonds A-A, A-B... wait: adjacent pairs are (A,A) and (A,B), so the
    /// bond ids are A-A and A-B — themselves uniquified if they collided (they don't here).
    @Test
    void tensorNetworkDuplicateCoreLabelsGetDistinctIds() {
        List<Anc> a = anchors(Sirentide.render("tensornetwork\n  mps A A B\n"));
        assertWellFormed(a, 5);   // 3 cores + 2 bonds
        List<String> nodeIds = a.stream().filter(x -> x.role().equals("node")).map(Anc::id).sorted().toList();
        assertEquals(3, nodeIds.size(), "three core nodes: " + a);
        assertEquals(List.of("A", "A-1", "B"), nodeIds,
            "the two same-label cores get distinct deterministic ids (A, A-1): " + nodeIds);
        // Every node id is unique (the uniquification invariant, stated positively).
        assertEquals(nodeIds.size(), nodeIds.stream().distinct().count(), "core ids are all distinct");
    }

    /// A hostile row/column/cell label can NEVER place an illegal char into an emitted matrix cell
    /// `<g>` id — the ids are coordinate-derived, so they are structurally immune, and the render path
    /// also never leaks the raw markup into the output. The security property mirrored for matrix.
    @Test
    void aHostileMatrixLabelCannotBreakOutOfTheAnchorAttribute() {
        String svg = Sirentide.render(
            "matrix\n  cols: <script>a\"b, ok\n  \"<img src=x onerror=alert(1)>\" : pass, fail\n");
        List<Anc> a = anchors(svg);
        assertFalse(a.isEmpty(), "the matrix still emits cell anchors: " + a);
        for (Anc x : anchors(svg)) {
            assertTrue(SirentideContract.ANCHOR_ID.matcher(x.id()).matches(),
                "emitted id stays charset-legal for a hostile label: " + x.id());
            assertTrue(x.id().matches("r\\d+c\\d+"),
                "the id is coordinate-derived, never the hostile text: " + x.id());
        }
        // The hostile label never reaches the output as live MARKUP — it only survives XML-escaped
        // inside the a11y `<desc>` text (`&lt;img … onerror …&gt;`), which is inert. No `<` opens a tag.
        assertFalse(svg.contains("<script>"), "the label never reaches the output as markup: " + svg);
        assertFalse(svg.contains("<img"), "no element injection from the label: " + svg);
        // NON-VACUITY (Lattice sirentide/215 follow-up 1): also assert the hostile
        // labels ARE present XML-ESCAPED, so this pins "the label went through the
        // parser + escaping sink" — not merely "no live tag", which a label silently
        // dropped would also satisfy.
        assertTrue(svg.contains("&lt;script&gt;"),
            "the <script> label survives XML-escaped (reached the sink, not dropped): " + svg);
        assertTrue(svg.contains("&lt;img") && svg.contains("onerror"),
            "the <img … onerror> label is present but inert-escaped, pinning parser-through-sink coverage: " + svg);
    }

    /// knot (plan sirentide-knot-diagram-primitive): the knot shadow is a 4-valent graph whose strand
    /// ARCS (the maximal runs between under-gaps) are the {@link SirentideRole#EDGE} anchors; crossings
    /// are its implicit, un-anchored nodes. A trefoil has 3 crossings → 3 gaps → exactly 3 strand arcs
    /// → 3 `<g role="edge">` groups, seq 0..2 contiguous — so {@code renderFrames} plays it through in
    /// >1 frame. DROP the `<g>` wrapper in KnotDiagramLayout (emit the arcs bare) and the count falls to
    /// 0 → RED.
    @Test
    void knotEmitsAnEdgeAnchorPerStrandArcAndPlaysThroughMultipleFrames() {
        List<Anc> a = anchors(Sirentide.render("knot\n  type: trefoil\n"));
        assertWellFormed(a, 3);
        assertEquals(3, countRole(a, "edge"), "one edge anchor per strand arc (trefoil: 3): " + a);
        for (Anc x : a) {
            assertTrue(List.of("edge").contains(x.role()),
                "the knot emits only the closed edge role, saw: " + x.role());
        }
        // seq 0..2 → renderFrames yields 3 distinct play-through frames (>1) for a multi-crossing knot.
        List<String> frames = Sirentide.renderFrames("knot\n  type: trefoil\n");
        assertEquals(3, frames.size(), "trefoil plays through 3 frames (one per strand arc)");
        // The figure-eight (4 crossings → 4 arcs) plays through 4 frames.
        assertEquals(4, Sirentide.renderFrames("knot\n  type: figure8\n").size(),
            "figure-eight plays through 4 frames");
        // The unknot (0 crossings) is a single un-broken loop → exactly 1 anchor group, 1 frame.
        List<Anc> u = anchors(Sirentide.render("knot\n  type: unknot\n"));
        assertEquals(1, u.size(), "unknot: one edge anchor (the whole loop): " + u);
        assertEquals(1, Sirentide.renderFrames("knot\n  type: unknot\n").size(),
            "the unknot has no play-through steps → a single frame");
    }

    // -- seq WIRE value saturates at 4 digits to match the /docs contract bound ^[0-9]{1,4}$ ---------
    //    (Lattice S3 vocab review, sirentide/105: the emitter's true seq is unbounded for the in-process
    //     play-through; only the emitted attribute is clamped so it survives constrainSirentideWrappers.)

    @Test
    void emittedSeqSaturatesAtFourDigitsToMatchTheDocsBound() {
        // A group whose TRUE seq is 5 digits — a >9999-element diagram, past MAX_DATA_ROWS scale.
        LaidOut laid = new LaidOut(100, 100, List.of(
            new Group(new Anchor(SirentideRole.NODE, "big", 12345),
                List.of(new Rect(0, 0, 10, 10, "#4a5568")))));
        String svg = SvgEmitter.emit(laid);
        assertTrue(svg.contains("data-sirentide-seq=\"9999\""),
            "seq wire must saturate at 9999: " + svg);
        assertFalse(svg.contains("12345"), "the un-clamped 5-digit seq must not reach the wire: " + svg);
        Matcher m = Pattern.compile("data-sirentide-seq=\"([^\"]*)\"").matcher(svg);
        assertTrue(m.find());
        assertTrue(m.group(1).matches("[0-9]{1,4}"), "seq out of /docs bound: " + m.group(1));
        // A normal seq passes through unchanged.
        LaidOut small = new LaidOut(100, 100, List.of(
            new Group(new Anchor(SirentideRole.NODE, "ok", 7),
                List.of(new Rect(0, 0, 10, 10, "#4a5568")))));
        assertTrue(SvgEmitter.emit(small).contains("data-sirentide-seq=\"7\""));
    }
}
