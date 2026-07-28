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
    /// Pinned red by {@code LabelSurfacesTest.everyDiagramTypeIsAudited} — this set must reach
    /// empty before the slice can be handed over. Listing them beats returning empty silently:
    /// a caller can see that these are unchecked rather than checked-and-clean.
    static final Set<String> UNAUDITED = Set.of(
        "Pie", "XyChart", "Timeline", "Gantt", "Sequence", "QuadrantChart", "ClassDiagram",
        "ErDiagram", "GitGraph", "Journey", "Mindmap", "Sankey", "Heatmap", "TensorNetwork",
        "Knot");

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

            // --- No free display text: nothing to validate, and that is a fact about the
            // --- type rather than an unaudited gap.
            case com.sirentide.ir.Empty e -> { }                 // renders nothing
            case com.sirentide.ir.MathBlock mb -> { }            // latex, exempt by design
            case com.sirentide.ir.Snake sn -> { }                // integer quotients only
            case com.sirentide.ir.YoungDiagram y -> { }          // integer row lengths only
            case com.sirentide.ir.Dynkin d -> { }                // family char + rank
            case com.sirentide.ir.RootSystem r -> { }            // family char + rank

            // --- Not yet audited. See UNAUDITED above; a test is red until these are done.
            case com.sirentide.ir.Pie p -> { }
            case com.sirentide.ir.XyChart x -> { }
            case com.sirentide.ir.Timeline t -> { }
            case com.sirentide.ir.Gantt g -> { }
            case com.sirentide.ir.Sequence sq -> { }
            case com.sirentide.ir.QuadrantChart q -> { }
            case com.sirentide.ir.ClassDiagram c -> { }
            case com.sirentide.ir.ErDiagram er -> { }
            case com.sirentide.ir.GitGraph gg -> { }
            case com.sirentide.ir.Journey j -> { }
            case com.sirentide.ir.Mindmap mm -> { }
            case com.sirentide.ir.Sankey sk -> { }
            case com.sirentide.ir.Heatmap h -> { }
            case com.sirentide.ir.TensorNetwork tn -> { }
            case com.sirentide.ir.Knot k -> { }
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
