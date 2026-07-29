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
    /// One display label, with the stable identity the diagnostic needs and the rendering
    /// capability of the surface it came from.
    ///
    /// `id` is a node id where one exists, else the diagram type plus a stable index — never
    /// the label text itself, which is unbounded and attacker-influenced.
    ///
    /// ## mathAware is the fix for a defect the first version shipped
    ///
    /// `LabelMarkup` used to skip every `MathRun` GLOBALLY, which I described as exempting
    /// inline math "structurally rather than by a special case". That reasoning was wrong in a
    /// way that reopened the original defect: it assumed every display surface RENDERS math.
    /// Most do not. Marlow's discriminators (sirentide/680): `%% caption: unsafe $<br/>$`,
    /// `gitGraph commit id: "$<br/>$"` and `mindmap root $<br/>$` all came back OK/emit with a
    /// non-inert SVG, because on those surfaces `$…$` has no math semantics and the WHOLE
    /// authored string is emitted as plain glyph text. Wrapping the tag in dollars restored the
    /// exact failure this plan exists to close.
    ///
    /// So the exemption now follows RENDERING SEMANTICS, and the default is PLAIN — a surface
    /// is treated as math-capable only where the API documents it. Getting this wrong in the
    /// plain direction over-rejects loudly; getting it wrong in the math direction re-opens a
    /// silent bypass, so the default fails closed.
    public record Labeled(String id, String text, boolean mathAware) {}

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
    ///  - ~~`GitOp.Commit.id`~~ — I excluded this and was WRONG; it is now collected. Marlow
    ///    overturned it at sirentide/676 with three citations: the IR contract calls it the
    ///    optional author label, the parser documents it as an optional id label, and
    ///    GitGraphLayout emits it as glyph paths. Kept here as a record that the
    ///    identifier-vs-label judgement failed in the UNDER-validating direction.
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

            case com.sirentide.ir.Pie p -> slices(nz(p.slices()), "pie", out);
            case com.sirentide.ir.Timeline t -> slices(nz(t.events()), "timeline", out);
            case com.sirentide.ir.XyChart x -> {
                slices(nz(x.bars()), "xychart.bar", out);
                // seriesNames IS the legend, which Marlow's list names explicitly.
                for (int i = 0; i < nz(x.seriesNames()).size(); i++) {
                    add(out, "xychart.series[" + i + "]", nz(x.seriesNames()).get(i));
                }
            }
            case com.sirentide.ir.Gantt g -> {
                for (int i = 0; i < nz(g.tasks()).size(); i++) {
                    add(out, "gantt.task[" + i + "]", nz(g.tasks()).get(i).label());
                }
            }
            case com.sirentide.ir.Sequence sq -> {
                // actors ARE the displayed lifeline captions. SeqMessage.from/to and
                // SeqLifecycle.actor merely REFERENCE them, so validating the actor list
                // covers the text exactly once instead of three times.
                for (int i = 0; i < nz(sq.actors()).size(); i++) {
                    add(out, "sequence.actor[" + i + "]", nz(sq.actors()).get(i));
                }
                for (int i = 0; i < nz(sq.messages()).size(); i++) {
                    add(out, "sequence.message[" + i + "]", nz(sq.messages()).get(i).label());
                }
                for (int i = 0; i < nz(sq.notes()).size(); i++) {
                    add(out, "sequence.note[" + i + "]", nz(sq.notes()).get(i).text());
                }
                for (int i = 0; i < nz(sq.blocks()).size(); i++) {
                    // .kind is a keyword (alt/opt/loop); .label is the author's text.
                    var blk = nz(sq.blocks()).get(i);
                    add(out, "sequence.block[" + i + "]", blk.label());
                    // Dividers inside a block carry their own author text -- also missed on
                    // the first pass, same cause as the heatmap legend.
                    for (int d = 0; d < nz(blk.dividers()).size(); d++) {
                        add(out, "sequence.block[" + i + "].divider[" + d + "]",
                            nz(blk.dividers()).get(d).label());
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
                for (int i = 0; i < nz(q.points()).size(); i++) {
                    add(out, "quadrant.point[" + i + "]", nz(q.points()).get(i).label());
                }
            }
            case com.sirentide.ir.ClassDiagram c -> {
                for (var box : nz(c.classes())) {
                    add(out, "class:" + box.name(), box.name());
                    for (int i = 0; i < nz(box.attributes()).size(); i++) {
                        add(out, "class:" + box.name() + ".attr[" + i + "]", nz(box.attributes()).get(i));
                    }
                    for (int i = 0; i < nz(box.methods()).size(); i++) {
                        add(out, "class:" + box.name() + ".method[" + i + "]", nz(box.methods()).get(i));
                    }
                }
                for (var rel : nz(c.relations())) {
                    // left/right name existing classes -> references, not new display text.
                    add(out, "class-rel:" + rel.left() + "->" + rel.right(), rel.label());
                }
            }
            case com.sirentide.ir.ErDiagram er -> {
                for (var ent : nz(er.entities())) {
                    add(out, "er:" + ent.name(), ent.name());
                    for (int i = 0; i < nz(ent.attributes()).size(); i++) {
                        var a = nz(ent.attributes()).get(i);
                        // type and name both render inside the entity box; key is a marker.
                        add(out, "er:" + ent.name() + ".attr[" + i + "].type", a.type());
                        add(out, "er:" + ent.name() + ".attr[" + i + "].name", a.name());
                    }
                }
                for (var rel : nz(er.relations())) {
                    add(out, "er-rel:" + rel.left() + "->" + rel.right(), rel.label());
                }
            }
            case com.sirentide.ir.GitGraph gg -> {
                for (int i = 0; i < nz(gg.ops()).size(); i++) {
                    var op = nz(gg.ops()).get(i);
                    // Branch.name labels a lane and IS display text.
                    if (op instanceof com.sirentide.ir.GitOp.Branch b) {
                        add(out, "gitgraph.branch[" + i + "]", b.name());
                    }
                    // Commit.id IS display text too. I excluded it as an identifier and
                    // Marlow overturned that with evidence (sirentide/676): the IR contract
                    // calls it the optional author LABEL, the parser documents it as an
                    // optional id label, and GitGraphLayout emits it as glyph paths. It
                    // doubles as an anchor identity, but a field being an identity does not
                    // exempt the text it visibly renders -- which is precisely the direction
                    // I warned was under-validated, and was.
                    if (op instanceof com.sirentide.ir.GitOp.Commit c2) {
                        add(out, "gitgraph.commit[" + i + "]", c2.id());
                    }
                }
            }
            case com.sirentide.ir.Journey j -> {
                add(out, "journey.title", j.title());
                for (int si = 0; si < nz(j.sections()).size(); si++) {
                    var sec = nz(j.sections()).get(si);
                    add(out, "journey.section[" + si + "]", sec.name());
                    for (int ti = 0; ti < nz(sec.tasks()).size(); ti++) {
                        var task = nz(sec.tasks()).get(ti);
                        add(out, "journey.section[" + si + "].task[" + ti + "]", task.name());
                        for (int ai = 0; ai < nz(task.actors()).size(); ai++) {
                            add(out, "journey.section[" + si + "].task[" + ti + "].actor[" + ai + "]",
                                nz(task.actors()).get(ai));
                        }
                    }
                }
            }
            case com.sirentide.ir.Mindmap mm -> mindmap(mm.root(), "mindmap", out);
            case com.sirentide.ir.Sankey sk -> {
                for (int i = 0; i < nz(sk.flows()).size(); i++) {
                    // In a sankey the endpoint NAMES are the rendered node labels; there is
                    // no separate label field, so these are display text, not references.
                    var flow = nz(sk.flows()).get(i);
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
                for (int i = 0; i < nz(h.columns()).size(); i++) {
                    add(out, "heatmap.column[" + i + "]", nz(h.columns()).get(i));
                }
                for (int r = 0; r < nz(h.rows()).size(); r++) {
                    var row = nz(h.rows()).get(r);
                    add(out, "heatmap.row[" + r + "]", row.label());
                    for (int c = 0; c < nz(row.cells()).size(); c++) {
                        add(out, "heatmap.cell[" + r + "][" + c + "]", nz(row.cells()).get(c).text());
                    }
                }
            }
            case com.sirentide.ir.TensorNetwork tn -> {
                for (int i = 0; i < nz(tn.cores()).size(); i++) {
                    add(out, "tensor.core[" + i + "]", nz(tn.cores()).get(i));
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
        for (int i = 0; i < nz(f.nodes()).size(); i++) {
            var n = nz(f.nodes()).get(i);
            // A node has a real id, so use it: a stable identity a human can find in source.
            // THE one documented math surface: `$…$` in a flowchart node label.
            addMathAware(out, prefix + "node:" + n.id(), n.label());
        }
        for (int i = 0; i < nz(f.edges()).size(); i++) {
            var e = nz(f.edges()).get(i);
            // Edges have no id of their own; from->to is stable and human-locatable.
            add(out, prefix + "edge:" + e.from() + "->" + e.to(), e.label());
        }
        for (int i = 0; i < nz(f.clusters()).size(); i++) {
            var c = nz(f.clusters()).get(i);
            add(out, prefix + "cluster:" + c.id(), c.title());   // TITLE only, never c.id()
        }
    }

    /// Slice-backed diagrams (pie, timeline, xychart bars) share one shape, so they share
    /// one walker rather than three copies that could drift apart.
    private static void slices(List<com.sirentide.ir.Slice> slices, String kind, List<Labeled> out) {
        for (int i = 0; i < nz(slices).size(); i++) {
            var sl = nz(slices).get(i);
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
        for (int i = 0; i < nz(node.children()).size(); i++) {
            mindmap(nz(node.children()).get(i), path + "." + i, out);
        }
    }

    /// Matrix display surfaces: column headers (the legend), row labels, and cell text.
    private static void matrix(Matrix m, List<Labeled> out) {
        for (int i = 0; i < nz(m.columns()).size(); i++) {
            add(out, "matrix.column[" + i + "]", nz(m.columns()).get(i));
        }
        for (int r = 0; r < nz(m.rows()).size(); r++) {
            var row = nz(m.rows()).get(r);
            add(out, "matrix.row[" + r + "]", row.label());
            for (int c = 0; c < nz(row.cells()).size(); c++) {
                add(out, "matrix.cell[" + r + "][" + c + "]", nz(row.cells()).get(c).text());
            }
        }
    }

    /// Null-safe list access. Several IR records keep their list fields NULLABLE by design --
    /// `XyChart.series`/`seriesNames` are the legacy single-series path, and a null there is a
    /// legitimate diagram, not a malformed one.
    ///
    /// The first version of this walker called `.size()` on them directly. That threw an NPE
    /// which `Sirentide.render` dutifully caught and degraded to the INERT SHELL -- so a
    /// perfectly legal chart silently rendered as an empty box. 29 tests across 12 suites went
    /// red at once, which is the only reason it was not shipped: the vacuity battery used
    /// fully-populated fixtures and could not see it.
    ///
    /// The lesson is narrower than "handle nulls": a validator that DEGRADES THE THING IT
    /// VALIDATES is worse than no validator, because the failure looks exactly like the
    /// renderer's own inert-shell degrade.
    private static <T> List<T> nz(List<T> list) {
        return list == null ? List.of() : list;
    }

    /// PLAIN-only surface: the whole authored string is scanned. This is the default because
    /// only one surface in the product documents math support.
    private static void add(List<Labeled> out, String id, String text) {
        addLabel(out, id, text, false);
    }

    /// MATH-AWARE surface. `Sirentide.render(dsl, math)` documents `$…$` as a FLOWCHART NODE
    /// LABEL feature specifically, and `MatrixLayout` states cells are a closed verdict
    /// vocabulary with no math — so this is deliberately narrow rather than "anything that
    /// accepts a MathFragmentRenderer parameter". Several layouts take that parameter and
    /// ignore it (GitGraphLayout and MindmapLayout both say so in their javadoc), which is
    /// exactly the false signal that would re-introduce the bypass.
    private static void addMathAware(List<Labeled> out, String id, String text) {
        addLabel(out, id, text, true);
    }

    private static void addLabel(List<Labeled> out, String id, String text, boolean mathAware) {
        if (text != null && !text.isEmpty()) {
            out.add(new Labeled(id, text, mathAware));
        }
    }
}
