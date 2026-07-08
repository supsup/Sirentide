package com.sirentide.a11y;

import com.sirentide.ir.ClassBox;
import com.sirentide.ir.ClassDiagram;
import com.sirentide.ir.ClassRelation;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.Empty;
import com.sirentide.ir.ErCardinality;
import com.sirentide.ir.ErDiagram;
import com.sirentide.ir.ErEntity;
import com.sirentide.ir.ErRelation;
import com.sirentide.ir.Flowchart;
import com.sirentide.ir.FlowEdge;
import com.sirentide.ir.FlowNode;
import com.sirentide.ir.Gantt;
import com.sirentide.ir.GitGraph;
import com.sirentide.ir.GitOp;
import com.sirentide.ir.Journey;
import com.sirentide.ir.JourneySection;
import com.sirentide.ir.JourneyTask;
import com.sirentide.ir.Pie;
import com.sirentide.ir.Point;
import com.sirentide.ir.QuadrantChart;
import com.sirentide.ir.SeqMessage;
import com.sirentide.ir.Sequence;
import com.sirentide.ir.Slice;
import com.sirentide.ir.StateDiagram;
import com.sirentide.ir.Task;
import com.sirentide.ir.Timeline;
import com.sirentide.ir.XyChart;
import java.util.LinkedHashMap;
import java.util.Map;

/// Builds the deterministic {@link A11y} (title + reading-order description) for a baked SVG,
/// PURELY from the {@link Diagram} IR — no geometry, no timestamps, no randomness. Called once per
/// bake by {@link com.sirentide.api.Sirentide#render}; the string it returns is a byte-stable
/// function of the diagram, so two renders of the same DSL produce the identical `<title>`/`<desc>`.
///
/// The description reads in the diagram's own order (nodes/edges/slices/messages as declared) and
/// names each element by its LABEL text + role — the a11y equivalent of the visual reading order.
/// Flowchart, pie, and sequence get RICH per-type descriptions (nodes+edges, slices+values,
/// actors+messages); xychart/timeline/gantt/state/quadrant get a lighter type+members description;
/// {@link Empty} gets {@link A11y#NONE} (the inert shell carries no a11y, staying byte-identical to
/// the pre-a11y shell).
///
/// Bounds: every enumerated label is capped ({@link #LABEL_CAP}) and every list is capped
/// ({@link #ITEM_CAP}) with a trailing `…`, so an oversized/adversarial label can't inflate the
/// `<desc>` — the description is bounded by construction, same discipline as the emitter's byte-cap.
public final class A11yDescriber {

    private A11yDescriber() {}

    /// Max characters kept from a single label before it is ellipsized in the description.
    private static final int LABEL_CAP = 80;

    /// Max members enumerated from any one list (nodes/edges/slices/messages/…) before a trailing
    /// `…`. Keeps the `<desc>` bounded regardless of diagram size (deterministic truncation).
    private static final int ITEM_CAP = 40;

    /// Build the a11y payload for a diagram. Exhaustive over the sealed IR; a blank/degenerate
    /// diagram yields {@link A11y#NONE}.
    public static A11y describe(Diagram ir) {
        return switch (ir) {
            case Pie pie -> pie(pie);
            case Flowchart fc -> flowchart(fc, "Flowchart");
            case Sequence s -> sequence(s);
            case StateDiagram sd -> state(sd);
            case XyChart chart -> xychart(chart);
            case Timeline tl -> timeline(tl);
            case Gantt gantt -> gantt(gantt);
            case QuadrantChart q -> quadrant(q);
            case ClassDiagram cd -> classDiagram(cd);
            case ErDiagram er -> erDiagram(er);
            case com.sirentide.ir.MathBlock mb -> mathBlock(mb);
            case GitGraph gg -> gitGraph(gg);
            case Journey j -> journey(j);
            case Empty ignored -> A11y.NONE;
        };
    }

    /// User journey: "User journey \"My working day\" with 2 sections and 5 tasks. Go to work: Make tea
    /// scored 5 (Me), Commute scored 3 (Me, Cat), …. Do work: Code scored 5 (Me), …." Sections in
    /// declaration order; each task names its satisfaction score + the actors that take part.
    /// Deterministic + bounded (labels + lists capped, same discipline as every other type).
    private static A11y journey(Journey j) {
        int sectionCount = j.sections().size();
        int taskCount = 0;
        for (JourneySection s : j.sections()) {
            taskCount += s.tasks().size();
        }
        StringBuilder d = new StringBuilder("User journey");
        if (j.title() != null && !j.title().isBlank()) {
            d.append(" \"").append(label(j.title())).append('"');
        }
        d.append(" with ").append(sectionCount).append(sectionCount == 1 ? " section" : " sections")
            .append(" and ").append(taskCount).append(taskCount == 1 ? " task" : " tasks").append('.');
        int shownSections = Math.min(sectionCount, ITEM_CAP);
        for (int i = 0; i < shownSections; i++) {
            JourneySection s = j.sections().get(i);
            d.append(' ').append(label(s.name())).append(':');
            int tc = s.tasks().size();
            int shownTasks = Math.min(tc, ITEM_CAP);
            for (int k = 0; k < shownTasks; k++) {
                JourneyTask t = s.tasks().get(k);
                d.append(k == 0 ? " " : ", ").append(label(t.name())).append(" scored ").append(t.score());
                if (!t.actors().isEmpty()) {
                    d.append(" (");
                    for (int m = 0; m < t.actors().size(); m++) {
                        if (m > 0) {
                            d.append(", ");
                        }
                        d.append(label(t.actors().get(m)));
                    }
                    d.append(')');
                }
            }
            d.append(tc > shownTasks ? ", …." : ".");
        }
        return new A11y("User journey", d.toString());
    }

    /// Git graph: "Git graph with 4 commits across 2 branches. Branches: main, develop. Commits:
    /// (main), \"fix\" (main), (develop), (main); …. Merges: develop into main; …". The op list is
    /// REPLAYED with the SAME inert rules the layout uses (a commit before any branch → implicit main;
    /// an unknown-branch checkout/merge, a duplicate branch, a self-merge, a merge of an empty branch →
    /// all dropped) so the desc reflects exactly what is drawn — branches that actually received a
    /// commit, commits in declaration order, and the valid merges. Deterministic + bounded.
    private static A11y gitGraph(GitGraph gg) {
        java.util.LinkedHashSet<String> declared = new java.util.LinkedHashSet<>();
        declared.add("main");
        java.util.Set<String> hasCommit = new java.util.HashSet<>();
        String current = "main";
        java.util.List<String[]> commits = new java.util.ArrayList<>();   // {branch, idOrNull}
        java.util.List<String[]> merges = new java.util.ArrayList<>();    // {source, target}
        for (GitOp op : gg.ops()) {
            switch (op) {
                case GitOp.Commit c -> {
                    hasCommit.add(current);
                    commits.add(new String[] {current, c.id()});
                }
                case GitOp.Branch b -> {
                    if (!declared.contains(b.name())) {
                        declared.add(b.name());
                        current = b.name();
                    }
                }
                case GitOp.Checkout co -> {
                    if (declared.contains(co.name())) {
                        current = co.name();
                    }
                }
                case GitOp.Merge mg -> {
                    if (declared.contains(mg.name()) && !mg.name().equals(current)
                        && hasCommit.contains(mg.name())) {
                        hasCommit.add(current);
                        commits.add(new String[] {current, null});
                        merges.add(new String[] {mg.name(), current});
                    }
                }
            }
        }
        java.util.List<String> branches = new java.util.ArrayList<>();
        for (String b : declared) {
            if (hasCommit.contains(b)) {
                branches.add(b);
            }
        }
        int commitCount = commits.size();
        int branchCount = branches.size();
        StringBuilder d = new StringBuilder("Git graph with ")
            .append(commitCount).append(commitCount == 1 ? " commit" : " commits")
            .append(" across ").append(branchCount).append(branchCount == 1 ? " branch" : " branches")
            .append('.');
        if (branchCount > 0) {
            d.append(" Branches: ");
            int shown = Math.min(branchCount, ITEM_CAP);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    d.append(", ");
                }
                d.append(label(branches.get(i)));
            }
            d.append(branchCount > shown ? ", …." : ".");
        }
        if (commitCount > 0) {
            d.append(" Commits: ");
            int shown = Math.min(commitCount, ITEM_CAP);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    d.append(", ");
                }
                String[] c = commits.get(i);
                if (c[1] != null && !c[1].isBlank()) {
                    d.append('"').append(label(c[1])).append("\" ");
                }
                d.append('(').append(label(c[0])).append(')');
            }
            d.append(commitCount > shown ? ", …." : ".");
        }
        int mergeCount = merges.size();
        if (mergeCount > 0) {
            d.append(" Merges: ");
            int shown = Math.min(mergeCount, ITEM_CAP);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    d.append("; ");
                }
                d.append(label(merges.get(i)[0])).append(" into ").append(label(merges.get(i)[1]));
            }
            d.append(mergeCount > shown ? "; …." : ".");
        }
        return new A11y("Git graph", d.toString());
    }

    /// ER diagram: "Entity-relationship diagram with N entities and M relationships. Entities:
    /// CUSTOMER, ORDER. Relationships: CUSTOMER (exactly one) to ORDER (zero or many) labeled
    /// \"places\"; …". Entities in first-seen order; each relationship reads BOTH ends' cardinalities
    /// (the marker's meaning) and whether it is identifying.
    private static A11y erDiagram(ErDiagram er) {
        int entityCount = er.entities().size();
        int relCount = er.relations().size();
        StringBuilder d = new StringBuilder("Entity-relationship diagram with ")
            .append(entityCount).append(entityCount == 1 ? " entity" : " entities")
            .append(" and ").append(relCount).append(relCount == 1 ? " relationship" : " relationships")
            .append('.');
        if (entityCount > 0) {
            d.append(" Entities: ");
            int shown = Math.min(entityCount, ITEM_CAP);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    d.append(", ");
                }
                ErEntity e = er.entities().get(i);
                d.append(label(e.name()));
                if (e.hasAttributes()) {
                    d.append(" (").append(e.attributes().size())
                        .append(e.attributes().size() == 1 ? " attribute)" : " attributes)");
                }
            }
            d.append(entityCount > shown ? ", …." : ".");
        }
        if (relCount > 0) {
            d.append(" Relationships: ");
            int shown = Math.min(relCount, ITEM_CAP);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    d.append("; ");
                }
                ErRelation r = er.relations().get(i);
                d.append(label(r.left())).append(" (").append(cardinality(r.leftCard())).append(") to ")
                    .append(label(r.right())).append(" (").append(cardinality(r.rightCard())).append(")");
                d.append(r.identifying() ? ", identifying" : ", non-identifying");
                if (r.label() != null && !r.label().isBlank()) {
                    d.append(" labeled \"").append(label(r.label())).append('"');
                }
            }
            d.append(relCount > shown ? "; …." : ".");
        }
        return new A11y("Entity-relationship diagram", d.toString());
    }

    /// A crow-foot cardinality spoken in words (the marker combo's meaning).
    private static String cardinality(ErCardinality c) {
        return switch (c) {
            case ZERO_OR_ONE -> "zero or one";
            case EXACTLY_ONE -> "exactly one";
            case ZERO_OR_MANY -> "zero or many";
            case ONE_OR_MANY -> "one or many";
        };
    }

    /// Class diagram: "Class diagram with N classes and M relations. Classes: Animal, Dog. Relations:
    /// Dog inherits from Animal; Animal composed of Collar; …". Classes in first-seen order; each
    /// relation read with a kind-specific verb naming the correct direction (the marker's meaning).
    private static A11y classDiagram(ClassDiagram cd) {
        int classCount = cd.classes().size();
        int relCount = cd.relations().size();
        StringBuilder d = new StringBuilder("Class diagram with ")
            .append(classCount).append(classCount == 1 ? " class" : " classes")
            .append(" and ").append(relCount).append(relCount == 1 ? " relation" : " relations")
            .append('.');
        if (classCount > 0) {
            d.append(" Classes: ");
            int shown = Math.min(classCount, ITEM_CAP);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    d.append(", ");
                }
                d.append(label(cd.classes().get(i).name()));
            }
            d.append(classCount > shown ? ", …." : ".");
        }
        if (relCount > 0) {
            d.append(" Relations: ");
            int shown = Math.min(relCount, ITEM_CAP);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    d.append("; ");
                }
                ClassRelation r = cd.relations().get(i);
                d.append(relationPhrase(r));
                if (r.label() != null && !r.label().isBlank()) {
                    d.append(" labeled \"").append(label(r.label())).append('"');
                }
            }
            d.append(relCount > shown ? "; …." : ".");
        }
        return new A11y("Class diagram", d.toString());
    }

    /// One relation as a natural phrase, keyed to the UML meaning of its marker (the whole/parent-side
    /// kinds read "child ← parent"; the arrow-side kinds read "source → target").
    private static String relationPhrase(ClassRelation r) {
        String left = label(r.left());
        String right = label(r.right());
        return switch (r.kind()) {
            case INHERITANCE -> right + " inherits from " + left;
            case COMPOSITION -> left + " is composed of " + right;
            case AGGREGATION -> left + " aggregates " + right;
            case ASSOCIATION -> left + " is associated with " + right;
            case DEPENDENCY -> left + " depends on " + right;
        };
    }

    /// Math block: a GENERIC, non-empty description — "Display math expression." — that does NOT
    /// dump the raw LaTeX source into the desc. This mirrors {@link #label}, which strips `$…$` math
    /// from every other type's desc: the moat is that math renders VISUALLY to baked glyph paths and
    /// no LaTeX source leaks into the output text, so verbalizing `\sum_{i=1}^{n}` to a screen reader
    /// would be noise AND a leak. A bare `mathblock` (empty body) still reads as an empty math block.
    /// RESIDUAL: a mathspeak translation (speaking the equation) is a deliberate follow-up.
    private static A11y mathBlock(com.sirentide.ir.MathBlock mb) {
        boolean empty = mb.latex() == null || mb.latex().isBlank();
        String desc = empty ? "Empty math block." : "Display math expression.";
        return new A11y("Math block", desc);
    }

    // ---- rich types -------------------------------------------------------------------------

    /// Pie: "Pie chart with N slices: Reviews 40, Builds 30, Docs 30." Values format with the same
    /// integer-when-whole rule the emitter uses, so the desc reads like the on-slice labels.
    private static A11y pie(Pie pie) {
        int n = pie.slices().size();
        StringBuilder d = new StringBuilder("Pie chart with ").append(n)
            .append(n == 1 ? " slice" : " slices");
        if (n > 0) {
            d.append(": ");
            int shown = Math.min(n, ITEM_CAP);
            for (int i = 0; i < shown; i++) {
                Slice s = pie.slices().get(i);
                if (i > 0) {
                    d.append(", ");
                }
                d.append(label(s.label())).append(' ').append(num(s.value()));
            }
            if (n > shown) {
                d.append(", …");
            }
        }
        d.append('.');
        return new A11y("Pie chart", d.toString());
    }

    /// Flowchart: "Flowchart with N nodes and M edges. Nodes: A, B, C. A leads to B labeled \"yes\";
    /// …". Nodes and edges are read in declaration order; each edge resolves its endpoints to their
    /// display labels. Shared with the state diagram (which reuses the flowchart engine).
    private static A11y flowchart(Flowchart fc, String typeWord) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (FlowNode node : fc.nodes()) {
            labels.put(node.id(), node.label());
        }
        int nodeCount = fc.nodes().size();
        int edgeCount = fc.edges().size();
        StringBuilder d = new StringBuilder(typeWord).append(" with ")
            .append(nodeCount).append(nodeCount == 1 ? " node" : " nodes")
            .append(" and ").append(edgeCount).append(edgeCount == 1 ? " edge" : " edges").append('.');
        if (nodeCount > 0) {
            d.append(" Nodes: ");
            int shown = Math.min(nodeCount, ITEM_CAP);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    d.append(", ");
                }
                d.append(label(nodeLabel(labels, fc.nodes().get(i).id(), fc.nodes().get(i).label())));
            }
            d.append(nodeCount > shown ? ", …." : ".");
        }
        if (edgeCount > 0) {
            d.append(' ');
            int shown = Math.min(edgeCount, ITEM_CAP);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    d.append("; ");
                }
                FlowEdge e = fc.edges().get(i);
                d.append(label(nodeLabel(labels, e.from(), e.from())))
                    .append(" leads to ")
                    .append(label(nodeLabel(labels, e.to(), e.to())));
                if (e.label() != null && !e.label().isBlank()) {
                    d.append(" labeled \"").append(label(e.label())).append('"');
                }
            }
            d.append(edgeCount > shown ? "; …." : ".");
        }
        return new A11y(typeWord, d.toString());
    }

    /// Sequence: "Sequence diagram with N actors: Alice, Bob. Messages: Alice to Bob: Request;
    /// Bob to Alice: Token (reply); …". Actors in first-seen order, messages in declaration order,
    /// each tagged call/reply and self where relevant.
    private static A11y sequence(Sequence s) {
        int actorCount = s.actors().size();
        int msgCount = s.messages().size();
        StringBuilder d = new StringBuilder("Sequence diagram with ")
            .append(actorCount).append(actorCount == 1 ? " actor" : " actors");
        if (actorCount > 0) {
            d.append(": ");
            int shown = Math.min(actorCount, ITEM_CAP);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    d.append(", ");
                }
                d.append(label(s.actors().get(i)));
            }
            d.append(actorCount > shown ? ", …" : "");
        }
        d.append('.');
        if (msgCount > 0) {
            d.append(" Messages: ");
            int shown = Math.min(msgCount, ITEM_CAP);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    d.append("; ");
                }
                SeqMessage m = s.messages().get(i);
                d.append(label(m.from())).append(" to ").append(label(m.to()));
                if (m.label() != null && !m.label().isBlank()) {
                    d.append(": ").append(label(m.label()));
                }
                if (m.reply()) {
                    d.append(" (reply)");
                }
            }
            d.append(msgCount > shown ? "; …." : ".");
        }
        // Notes (M2 enrichment): "Notes: right of Bob: Bob thinks; over Alice, Bob: a shared note."
        // Appended only when notes exist, so a note-free sequence's desc is byte-identical to before.
        int noteCount = s.notes().size();
        if (noteCount > 0) {
            d.append(" Notes: ");
            int shown = Math.min(noteCount, ITEM_CAP);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    d.append("; ");
                }
                com.sirentide.ir.SeqNote note = s.notes().get(i);
                d.append(notePosition(note.position())).append(' ');
                for (int k = 0; k < note.actors().size(); k++) {
                    if (k > 0) {
                        d.append(", ");
                    }
                    d.append(label(note.actors().get(k)));
                }
                d.append(": ").append(label(note.text()));
            }
            d.append(noteCount > shown ? "; …." : ".");
        }
        return new A11y("Sequence diagram", d.toString());
    }

    /// The note-position keyword spoken naturally in the desc.
    private static String notePosition(String pos) {
        return switch (pos) {
            case "over" -> "over";
            case "left" -> "left of";
            case "right" -> "right of";
            default -> pos;
        };
    }

    /// State diagram: reuses the flowchart description over its wrapped graph, mapping the
    /// `__start__`/`__end__` pseudostates to readable "start"/"end" so the desc reads naturally.
    private static A11y state(StateDiagram sd) {
        Flowchart g = sd.graph();
        // Re-label the pseudostates so the shared flowchart describer reads "start"/"end", not the
        // internal ids. A tiny relabel-and-delegate; geometry is untouched (this is desc-only).
        java.util.List<FlowNode> nodes = new java.util.ArrayList<>();
        for (FlowNode n : g.nodes()) {
            nodes.add(new FlowNode(n.id(), pseudo(n.id(), n.label()), n.shape(), n.color()));
        }
        Flowchart relabeled = new Flowchart(nodes, g.edges(), g.direction(), g.textColor(), g.nodeColor());
        A11y base = flowchart(relabeled, "State diagram");
        return new A11y("State diagram", base.desc());
    }

    private static String pseudo(String id, String label) {
        return switch (id) {
            case "__start__" -> "start";
            case "__end__" -> "end";
            default -> label;
        };
    }

    // ---- lighter types (type + members) -----------------------------------------------------

    /// Xychart: names the render mode (bar/line/scatter) and enumerates the categories.
    private static A11y xychart(XyChart chart) {
        String mode = switch (chart.mode()) {
            case "line" -> "Line chart";
            case "scatter" -> "Scatter chart";
            default -> "Bar chart";
        };
        int n = chart.bars().size();
        StringBuilder d = new StringBuilder(mode).append(" with ").append(n)
            .append(n == 1 ? " category" : " categories");
        appendSliceLabels(d, chart.bars(), n);
        return new A11y(mode, d.toString());
    }

    /// Timeline: enumerates the events (label + value/year) in order.
    private static A11y timeline(Timeline tl) {
        int n = tl.events().size();
        StringBuilder d = new StringBuilder("Timeline with ").append(n)
            .append(n == 1 ? " event" : " events");
        if (n > 0) {
            d.append(": ");
            int shown = Math.min(n, ITEM_CAP);
            for (int i = 0; i < shown; i++) {
                Slice s = tl.events().get(i);
                if (i > 0) {
                    d.append(", ");
                }
                d.append(label(s.label())).append(' ')
                    .append(s.valueLabel() != null ? label(s.valueLabel()) : num(s.value()));
            }
            d.append(n > shown ? ", …" : "");
        }
        d.append('.');
        return new A11y("Timeline", d.toString());
    }

    /// Gantt: enumerates the tasks (name + start–end span) in order.
    private static A11y gantt(Gantt gantt) {
        int n = gantt.tasks().size();
        StringBuilder d = new StringBuilder("Gantt chart with ").append(n)
            .append(n == 1 ? " task" : " tasks");
        if (n > 0) {
            d.append(": ");
            int shown = Math.min(n, ITEM_CAP);
            for (int i = 0; i < shown; i++) {
                Task t = gantt.tasks().get(i);
                if (i > 0) {
                    d.append(", ");
                }
                d.append(label(t.label())).append(' ').append(num(t.start()))
                    .append(" to ").append(num(t.end()));
            }
            d.append(n > shown ? ", …" : "");
        }
        d.append('.');
        return new A11y("Gantt chart", d.toString());
    }

    /// Quadrant chart: names the axis ends (when present) and enumerates the plotted points.
    private static A11y quadrant(QuadrantChart q) {
        int n = q.points().size();
        StringBuilder d = new StringBuilder("Quadrant chart");
        if (q.xLo() != null || q.xHi() != null) {
            d.append(", x-axis ").append(label(orEmpty(q.xLo()))).append(" to ")
                .append(label(orEmpty(q.xHi())));
        }
        if (q.yLo() != null || q.yHi() != null) {
            d.append(", y-axis ").append(label(orEmpty(q.yLo()))).append(" to ")
                .append(label(orEmpty(q.yHi())));
        }
        d.append(", with ").append(n).append(n == 1 ? " point" : " points");
        if (n > 0) {
            d.append(": ");
            int shown = Math.min(n, ITEM_CAP);
            for (int i = 0; i < shown; i++) {
                Point p = q.points().get(i);
                if (i > 0) {
                    d.append(", ");
                }
                d.append(label(p.label()));
            }
            d.append(n > shown ? ", …" : "");
        }
        d.append('.');
        return new A11y("Quadrant chart", d.toString());
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static void appendSliceLabels(StringBuilder d, java.util.List<Slice> items, int n) {
        if (n > 0) {
            d.append(": ");
            int shown = Math.min(n, ITEM_CAP);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    d.append(", ");
                }
                d.append(label(items.get(i).label()));
            }
            d.append(n > shown ? ", …" : "");
        }
        d.append('.');
    }

    private static String nodeLabel(Map<String, String> labels, String id, String fallback) {
        String l = labels.get(id);
        return l != null ? l : fallback;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /// Clean a label for the description: drop inline `$…$` math spans (raw LaTeX like `$E=mc^2$` or
    /// `$\frac vr$` is NOISE to a screen reader — the math renders VISUALLY to baked glyph paths, so
    /// the desc names only the textual portion; this also keeps the math-baking moat intact: no LaTeX
    /// source leaks into the output text), collapse whitespace/newlines to single spaces, and cap the
    /// length (ellipsized) so no single label can inflate the `<desc>`. Deterministic. RESIDUAL: the
    /// math itself is not VERBALIZED in the desc (a mathspeak translation is a follow-up).
    private static String label(String raw) {
        if (raw == null) {
            return "";
        }
        // Strip balanced `$…$` inline-math spans (non-greedy, single-line by construction — labels
        // are one line). A lone unmatched `$` is left as-is (it is not a math span).
        String noMath = raw.replaceAll("\\$[^$]*\\$", "");
        String flat = noMath.replaceAll("\\s+", " ").trim();
        if (flat.length() > LABEL_CAP) {
            return flat.substring(0, LABEL_CAP) + "…";
        }
        return flat;
    }

    /// Format a numeric value the way the emitter formats geometry: integer when whole, else a
    /// plain decimal. Keeps the desc's numbers reading like the diagram's own value labels.
    private static String num(double v) {
        if (!Double.isFinite(v)) {
            return "0";
        }
        double r = Math.round(v * 1000.0) / 1000.0;
        return r == Math.rint(r) ? Long.toString((long) r) : Double.toString(r);
    }
}
