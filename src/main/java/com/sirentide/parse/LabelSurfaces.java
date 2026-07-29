package com.sirentide.parse;

import com.sirentide.ir.Diagram;
import com.sirentide.ir.Flowchart;
import com.sirentide.ir.Matrix;
import com.sirentide.ir.StateDiagram;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/// Enumerates the DISPLAY-LABEL fields of a `Diagram`, and nothing else.
///
/// ## Why this type exists rather than a check at the parser call sites
///
/// Marlow's ruling (sirentide/671) scopes the label-markup policy to *visually rendered text*
/// — node, edge, row, item, legend, visible caption, cluster title — and explicitly NOT to
/// identifiers. `cap` is not that boundary: it is a generic bounding primitive shared by
/// titles, captions, series names, class names, identifiers and labels alike. Wiring the
/// validator to `cap` would reject identifiers, and the giveaway would be
/// `subgraph outer<unsafe> [Outer title]` failing — there `outer<unsafe>` is an IDENTIFIER
/// bound for {@code Anchor.sanitizeId} (which correctly yields `outerunsafe`), and
/// `Outer title` is the display label. If this class ever rejects that source, it has been
/// wired to an id or to `cap` rather than to a label surface.
///
/// So the policy needs a seam that knows what a field MEANS, which is after the parser has
/// built the IR. This class is that seam.
///
/// ## The exhaustiveness discriminator is the COMPILER
///
/// The ruling requires that omitting one label-bearing type turns a test red. The switch
/// below has **no `default` branch** over a `sealed` interface, so omitting a type does not
/// turn a test red — it **fails compilation**, which is strictly stronger and cannot be
/// skipped, silenced, or left unrun. `LabelSurfacesTest` additionally pins that the switch is
/// non-vacuous, because an exhaustive switch that returns empty everywhere would compile
/// perfectly and validate nothing.
///
/// ## Incompleteness is LOUD, not silent
///
/// Several diagram types are not yet audited for which of their string fields are display
/// text versus identifiers versus enum-ish tokens. Returning an empty list for those would be
/// a silent hole of exactly the kind this whole plan exists to remove — an instrument that
/// reports nothing and a surface that genuinely has nothing look identical.
///
/// So they are named in {@link #UNAUDITED} and a test FAILS while that set is non-empty. The
/// incompleteness is a visible, failing fact rather than a quiet absence.
public final class LabelSurfaces {

    private LabelSurfaces() {}

    /// One display label, with the stable identity the diagnostic needs.
    ///
    /// `id` is a node id where one exists, else the diagram type plus a stable index — never
    /// the label text itself, which is unbounded and attacker-influenced.
    public record Labeled(String id, String text) {}

    /// Diagram types whose display-vs-identifier field split has NOT yet been audited.
    ///
    /// Now EMPTY: every permitted type has been walked field by field. Kept (rather than
    /// deleted with its test) because it is the honest shape for this class — if a future
    /// type arrives whose fields are ambiguous, the author can park it here and the
    /// accompanying test goes red rather than the type silently contributing no labels.
    ///
    /// TWO FIELDS WERE EXCLUDED AS IDENTIFIERS, and both are judgement calls rather than
    /// obvious ones, so they are named here instead of buried:
    ///
    ///  - `GitOp.Commit.id` — rendered near the commit dot, but it is an *id* by name and by
    ///    role. Marlow's ruling says not to reject raw identifiers merely because they are
    ///    later sanitized. `GitOp.Branch.name` IS collected, because it labels a lane.
    ///  - `Knot.type` — a selector naming which knot to draw (`trefoil`, `figure8`), not
    ///    free author text. A tag-shaped value here would fail the knot lookup long before
    ///    it could reach a label surface.
    ///
    /// If either judgement is wrong the fix is one line each, and the cost of being wrong is
    /// an under-validated surface — so they are called out for review rather than assumed.
    static final Set<String> UNAUDITED = Set.of();

    /// Every display label in `diagram`, in a stable order.
    ///
    /// The switch is exhaustive over the sealed `Diagram` hierarchy WITHOUT a default, so a
    /// new permitted type breaks this file at compile time and forces an explicit decision.
    public static List<Labeled> of(Diagram diagram) {
        List<Labeled> out = new ArrayList<>();
        switch (diagram) {
            case Flowchart f -> flowchart(f, "", out);
            // A state diagram IS a flowchart in the IR, so it inherits the same surfaces
            // rather than a parallel copy that could drift.
            case StateDiagram s -> flowchart(s.graph(), "state.", out);
            case Matrix m -> matrix(m, out);

            case com.sirentide.ir.Pie p -> slices(p.slices(), "pie", out);
            case com.sirentide.ir.Timeline t -> slices(t.events(), "timeline", out);
            case com.sirentide.ir.XyChart x -> {
                slices(x.bars(), "xychart.bar", out);
                // seriesNames IS the legend, which Marlow's list names explicitly.
                for (int i = 0; i < x.seriesNames().size(); i++) {
                    add(out, "xychart.series[" + i + "]", x.seriesNames().get(i));
                }
            }
            case com.sirentide.ir.Gantt g -> {
                for (int i = 0; i < g.tasks().size(); i++) {
                    add(out, "gantt.task[" + i + "]", g.tasks().get(i).label());
                }
            }
            case com.sirentide.ir.Sequence sq -> {
                // actors ARE the displayed lifeline captions. SeqMessage.from/to and
                // SeqLifecycle.actor merely REFERENCE them, so validating the actor list
                // covers the text exactly once instead of three times.
                for (int i = 0; i < sq.actors().size(); i++) {
                    add(out, "sequence.actor[" + i + "]", sq.actors().get(i));
                }
                for (int i = 0; i < sq.messages().size(); i++) {
                    add(out, "sequence.message[" + i + "]", sq.messages().get(i).label());
                }
                for (int i = 0; i < sq.notes().size(); i++) {
                    add(out, "sequence.note[" + i + "]", sq.notes().get(i).text());
                }
                for (int i = 0; i < sq.blocks().size(); i++) {
                    // .kind is a keyword (alt/opt/loop); .label is the author's text.
                    var blk = sq.blocks().get(i);
                    add(out, "sequence.block[" + i + "]", blk.label());
                    // Dividers inside a block carry their own author text -- also missed on
                    // the first pass, same cause as the heatmap legend.
                    for (int d = 0; d < blk.dividers().size(); d++) {
                        add(out, "sequence.block[" + i + "].divider[" + d + "]",
                            blk.dividers().get(d).label());
                    }
                }
            }
            case com.sirentide.ir.QuadrantChart q -> {
                add(out, "quadrant.xLo", q.xLo());
                add(out, "quadrant.xHi", q.xHi());
                add(out, "quadrant.yLo", q.yLo());
                add(out, "quadrant.yHi", q.yHi());
                String[] ql = q.quadrantLabels();
                for (int i = 0; ql != null && i < ql.length; i++) {
                    add(out, "quadrant.label[" + i + "]", ql[i]);
                }
                for (int i = 0; i < q.points().size(); i++) {
                    add(out, "quadrant.point[" + i + "]", q.points().get(i).label());
                }
            }
            case com.sirentide.ir.ClassDiagram c -> {
                for (var box : c.classes()) {
                    add(out, "class:" + box.name(), box.name());
                    for (int i = 0; i < box.attributes().size(); i++) {
                        add(out, "class:" + box.name() + ".attr[" + i + "]", box.attributes().get(i));
                    }
                    for (int i = 0; i < box.methods().size(); i++) {
                        add(out, "class:" + box.name() + ".method[" + i + "]", box.methods().get(i));
                    }
                }
                for (var rel : c.relations()) {
                    // left/right name existing classes -> references, not new display text.
                    add(out, "class-rel:" + rel.left() + "->" + rel.right(), rel.label());
                }
            }
            case com.sirentide.ir.ErDiagram er -> {
                for (var ent : er.entities()) {
                    add(out, "er:" + ent.name(), ent.name());
                    for (int i = 0; i < ent.attributes().size(); i++) {
                        var a = ent.attributes().get(i);
                        // type and name both render inside the entity box; key is a marker.
                        add(out, "er:" + ent.name() + ".attr[" + i + "].type", a.type());
                        add(out, "er:" + ent.name() + ".attr[" + i + "].name", a.name());
                    }
                }
                for (var rel : er.relations()) {
                    add(out, "er-rel:" + rel.left() + "->" + rel.right(), rel.label());
                }
            }
            case com.sirentide.ir.GitGraph gg -> {
                for (int i = 0; i < gg.ops().size(); i++) {
                    // Branch.name labels a lane and IS display text. Commit.id is excluded
                    // as an identifier -- see UNAUDITED for that judgement call.
                    if (gg.ops().get(i) instanceof com.sirentide.ir.GitOp.Branch b) {
                        add(out, "gitgraph.branch[" + i + "]", b.name());
                    }
                }
            }
            case com.sirentide.ir.Journey j -> {
                add(out, "journey.title", j.title());
                for (int si = 0; si < j.sections().size(); si++) {
                    var sec = j.sections().get(si);
                    add(out, "journey.section[" + si + "]", sec.name());
                    for (int ti = 0; ti < sec.tasks().size(); ti++) {
                        var task = sec.tasks().get(ti);
                        add(out, "journey.section[" + si + "].task[" + ti + "]", task.name());
                        for (int ai = 0; ai < task.actors().size(); ai++) {
                            add(out, "journey.section[" + si + "].task[" + ti + "].actor[" + ai + "]",
                                task.actors().get(ai));
                        }
                    }
                }
            }
            case com.sirentide.ir.Mindmap mm -> mindmap(mm.root(), "mindmap", out);
            case com.sirentide.ir.Sankey sk -> {
                for (int i = 0; i < sk.flows().size(); i++) {
                    // In a sankey the endpoint NAMES are the rendered node labels; there is
                    // no separate label field, so these are display text, not references.
                    var flow = sk.flows().get(i);
                    add(out, "sankey.flow[" + i + "].source", flow.source());
                    add(out, "sankey.flow[" + i + "].target", flow.target());
                }
            }
            case com.sirentide.ir.Heatmap h -> {
                // lowLabel/highLabel are the SCALE LEGEND captions. I missed these on the
                // first pass and only caught them by reading the full record signature
                // rather than the first line -- an omission the compiler cannot see,
                // because a missing FIELD is not a missing CASE.
                add(out, "heatmap.lowLabel", h.lowLabel());
                add(out, "heatmap.highLabel", h.highLabel());
                for (int i = 0; i < h.columns().size(); i++) {
                    add(out, "heatmap.column[" + i + "]", h.columns().get(i));
                }
                for (int r = 0; r < h.rows().size(); r++) {
                    var row = h.rows().get(r);
                    add(out, "heatmap.row[" + r + "]", row.label());
                    for (int c = 0; c < row.cells().size(); c++) {
                        add(out, "heatmap.cell[" + r + "][" + c + "]", row.cells().get(c).text());
                    }
                }
            }
            case com.sirentide.ir.TensorNetwork tn -> {
                for (int i = 0; i < tn.cores().size(); i++) {
                    add(out, "tensor.core[" + i + "]", tn.cores().get(i));
                }
            }

            // --- No free display text. A fact about the type, not an unaudited gap.
            case com.sirentide.ir.Empty e -> { }                 // renders nothing
            case com.sirentide.ir.MathBlock mb -> { }            // latex, exempt by design
            case com.sirentide.ir.Snake sn -> { }                // integer quotients only
            case com.sirentide.ir.YoungDiagram y -> { }          // integer row lengths only
            case com.sirentide.ir.Dynkin d -> { }                // family char + rank
            case com.sirentide.ir.RootSystem r -> { }            // family char + rank
            case com.sirentide.ir.Knot k -> { }                  // selector, see UNAUDITED
        }
        return out;
    }

    /// Flowchart display surfaces: node label, edge label, cluster TITLE.
    ///
    /// Note what is absent. `FlowNode.id`, `FlowEdge.from` and `FlowEdge.to`, and
    /// `FlowCluster.id` are identifiers and are deliberately NOT collected — they are bound
    /// for {@code Anchor.sanitizeId}, and rejecting them here is the precise failure Marlow
    /// named in ruling point 2.
    private static void flowchart(Flowchart f, String prefix, List<Labeled> out) {
        for (int i = 0; i < f.nodes().size(); i++) {
            var n = f.nodes().get(i);
            // A node has a real id, so use it: a stable identity a human can find in source.
            add(out, prefix + "node:" + n.id(), n.label());
        }
        for (int i = 0; i < f.edges().size(); i++) {
            var e = f.edges().get(i);
            // Edges have no id of their own; from->to is stable and human-locatable.
            add(out, prefix + "edge:" + e.from() + "->" + e.to(), e.label());
        }
        for (int i = 0; i < f.clusters().size(); i++) {
            var c = f.clusters().get(i);
            add(out, prefix + "cluster:" + c.id(), c.title());   // TITLE only, never c.id()
        }
    }

    /// Slice-backed diagrams (pie, timeline, xychart bars) share one shape, so they share
    /// one walker rather than three copies that could drift apart.
    private static void slices(List<com.sirentide.ir.Slice> slices, String kind, List<Labeled> out) {
        for (int i = 0; i < slices.size(); i++) {
            var sl = slices.get(i);
            add(out, kind + "[" + i + "]", sl.label());
            add(out, kind + "[" + i + "].valueLabel", sl.valueLabel());
        }
    }

    /// Mindmap nodes nest arbitrarily, so the walk is recursive and the identity encodes the
    /// PATH — a bare index would not locate a node three levels down.
    private static void mindmap(com.sirentide.ir.MindmapNode node, String path, List<Labeled> out) {
        if (node == null) {
            return;
        }
        add(out, path, node.text());
        for (int i = 0; i < node.children().size(); i++) {
            mindmap(node.children().get(i), path + "." + i, out);
        }
    }

    /// Matrix display surfaces: column headers (the legend), row labels, and cell text.
    private static void matrix(Matrix m, List<Labeled> out) {
        for (int i = 0; i < m.columns().size(); i++) {
            add(out, "matrix.column[" + i + "]", m.columns().get(i));
        }
        for (int r = 0; r < m.rows().size(); r++) {
            var row = m.rows().get(r);
            add(out, "matrix.row[" + r + "]", row.label());
            for (int c = 0; c < row.cells().size(); c++) {
                add(out, "matrix.cell[" + r + "][" + c + "]", row.cells().get(c).text());
            }
        }
    }

    private static void add(List<Labeled> out, String id, String text) {
        if (text != null && !text.isEmpty()) {
            out.add(new Labeled(id, text));
        }
    }
}
