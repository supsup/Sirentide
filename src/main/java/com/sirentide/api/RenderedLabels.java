package com.sirentide.api;

import com.sirentide.ir.ClassBox;
import com.sirentide.ir.ClassDiagram;
import com.sirentide.ir.ClassRelation;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.Divider;
import com.sirentide.ir.Dynkin;
import com.sirentide.ir.Empty;
import com.sirentide.ir.ErAttribute;
import com.sirentide.ir.ErDiagram;
import com.sirentide.ir.ErEntity;
import com.sirentide.ir.ErRelation;
import com.sirentide.ir.FlowCluster;
import com.sirentide.ir.FlowEdge;
import com.sirentide.ir.FlowNode;
import com.sirentide.ir.Flowchart;
import com.sirentide.ir.Gantt;
import com.sirentide.ir.GitGraph;
import com.sirentide.ir.GitOp;
import com.sirentide.ir.Heatmap;
import com.sirentide.ir.Journey;
import com.sirentide.ir.JourneySection;
import com.sirentide.ir.JourneyTask;
import com.sirentide.ir.Knot;
import com.sirentide.ir.MathBlock;
import com.sirentide.ir.Matrix;
import com.sirentide.ir.Mindmap;
import com.sirentide.ir.MindmapNode;
import com.sirentide.ir.Pie;
import com.sirentide.ir.Point;
import com.sirentide.ir.QuadrantChart;
import com.sirentide.ir.RootSystem;
import com.sirentide.ir.Sankey;
import com.sirentide.ir.SankeyFlow;
import com.sirentide.ir.SeqBlock;
import com.sirentide.ir.SeqMessage;
import com.sirentide.ir.SeqNote;
import com.sirentide.ir.Sequence;
import com.sirentide.ir.Slice;
import com.sirentide.ir.Snake;
import com.sirentide.ir.StateDiagram;
import com.sirentide.ir.Task;
import com.sirentide.ir.TensorNetwork;
import com.sirentide.ir.Timeline;
import com.sirentide.ir.XyChart;
import com.sirentide.ir.YoungDiagram;
import com.sirentide.parse.LabelRuns;

/// The text that ACTUALLY BAKES AS PLAIN GLYPHS for a parsed diagram — the corpus font-coverage
/// diagnostics must scan (plan 933eed50, Marlow sirentide/706 Finding 1). The old scan read the RAW
/// DSL, so comments, `accDescr` and syntax produced coverage warnings about glyphs that do not
/// exist. This walk mirrors the LAYOUT layer's real emission per type, field-verified against each
/// `*Layout.java` (per-field citations in the plan record):
///
///   - The switch is SEALED WITH NO DEFAULT: when diagram type 25 joins `Diagram`, this fails to
///     COMPILE until its rendered fields are declared here — the traversal cannot silently go
///     stale, which is the "additive about the funnel" failure this class refuses to rebuild.
///   - Math runs (`$…$`) leave the glyph path at `MathLabel` ONLY when a math renderer is live;
///     with `math == null` the degrade re-materializes `"$latex$"` through `textPathD`
///     (MathLabel.java degrade branch), so `mathLive` gates whether latex text joins the corpus.
///     Sites the layout never splits (Journey, Mindmap, Sankey, Matrix, Heatmap, GitGraph,
///     TensorNetwork, captions, Sequence notes/blocks/dividers, cluster titles, Pie legends,
///     XyChart legend names, Timeline valueLabels) contribute RAW text regardless.
///   - Derived always-ASCII text (formatted numbers, `…` ellipses, static fallbacks like
///     `"Series N"`/`"main"`) is deliberately excluded: it cannot fall outside coverage.
///
/// KNOWN APPROXIMATION, documented rather than hidden: a Pie slice label bakes through the
/// math-split path inside a big slice but RAW on the small-slice leader line and in the legend.
/// Which path fires is a layout decision invisible to the IR, so with a live math renderer and no
/// legend this walk treats slice labels as split — a `$…$` slice label that happens to take the
/// leader path could under-warn. The legend-on case (labels always raw) is handled exactly.
final class RenderedLabels {

    private RenderedLabels() {
    }

    /// Collect every source-derived string that reaches plain-glyph emission, `\n`-joined.
    /// `caption` is `DiagramConfig.caption` (bakes as glyphs for every type, never math-split);
    /// `DiagramConfig.title` is a11y-only and deliberately absent.
    static String collect(Diagram ir, String caption, boolean mathLive) {
        StringBuilder out = new StringBuilder();
        switch (ir) {
            case Empty ignored -> { }
            case Pie p -> {
                for (Slice s : p.slices()) {
                    // legend rows and leader labels are raw; only the inside-slice path splits
                    if (p.legend() || !mathLive) {
                        raw(out, s.label());
                    } else {
                        split(out, s.label(), true);
                    }
                }
            }
            case XyChart x -> {
                for (Slice b : x.bars()) {
                    split(out, b.label(), mathLive);   // categories split; values are derived digits
                }
                if (x.seriesNames() != null) {          // null → the derived "Series N" fallback
                    for (String name : x.seriesNames()) {
                        raw(out, name);                 // legend names never split
                    }
                }
            }
            case Timeline t -> {
                for (Slice e : t.events()) {
                    split(out, e.label(), mathLive);    // top label splits
                    raw(out, e.valueLabel());           // bottom label is always plain
                }
            }
            case Gantt g -> {
                for (Task task : g.tasks()) {
                    split(out, task.label(), mathLive);
                }
            }
            case Flowchart fc -> collectFlowchart(out, fc, mathLive);
            case StateDiagram sd -> collectFlowchart(out, sd.graph(), mathLive);  // pure delegate
            case Sequence sq -> {
                for (String actor : sq.actors()) {
                    split(out, actor, mathLive);
                }
                for (SeqMessage m : sq.messages()) {
                    split(out, m.label(), mathLive);
                }
                for (SeqNote n : sq.notes()) {
                    raw(out, n.text());                 // notes never split
                }
                for (SeqBlock b : sq.blocks()) {
                    raw(out, b.label());                // block labels never split; kind is a
                    for (Divider d : b.dividers()) {    // parser-closed ASCII vocabulary
                        raw(out, d.label());            // else/and divider labels, always plain
                    }
                }
            }
            case QuadrantChart q -> {
                split(out, q.xLo(), mathLive);
                split(out, q.xHi(), mathLive);
                split(out, q.yLo(), mathLive);
                split(out, q.yHi(), mathLive);
                for (String ql : q.quadrantLabels()) {
                    split(out, ql, mathLive);
                }
                for (Point pt : q.points()) {
                    split(out, pt.label(), mathLive);
                }
            }
            case ClassDiagram cd -> {
                for (ClassBox box : cd.classes()) {
                    split(out, box.name(), mathLive);
                    for (String a : box.attributes()) {
                        split(out, a, mathLive);
                    }
                    for (String m : box.methods()) {
                        split(out, m, mathLive);
                    }
                }
                for (ClassRelation rel : cd.relations()) {
                    split(out, rel.label(), mathLive);
                }
            }
            case ErDiagram er -> {
                for (ErEntity e : er.entities()) {
                    split(out, e.name(), mathLive);
                    for (ErAttribute a : e.attributes()) {
                        // display() concatenates type + name + key; all three bake
                        split(out, a.display(), mathLive);
                    }
                }
                for (ErRelation rel : er.relations()) {
                    split(out, rel.label(), mathLive);
                }
            }
            case MathBlock mb -> {
                // Success path: latex goes to a MathBox, never plain glyphs. Degrade path
                // (no renderer): the RAW latex bakes through textPathD. A renderer-level
                // FragmentGuard rejection also degrades; that runtime branch is invisible
                // here, so a guarded-out fragment could under-warn — documented boundary.
                if (!mathLive) {
                    raw(out, mb.latex());
                }
            }
            case GitGraph gg -> {
                for (GitOp op : gg.ops()) {
                    switch (op) {
                        case GitOp.Commit c -> raw(out, c.id());
                        case GitOp.Branch b -> raw(out, b.name());
                        case GitOp.Checkout ignored -> { }   // replay lookup only
                        case GitOp.Merge ignored -> { }      // replay lookup only
                    }
                }
            }
            case Journey j -> {                              // Journey never splits
                raw(out, j.title());
                for (JourneySection sec : j.sections()) {
                    raw(out, sec.name());
                    for (JourneyTask task : sec.tasks()) {
                        raw(out, task.name());
                        for (String actor : task.actors()) {
                            raw(out, actor);
                        }
                    }
                }
            }
            case Mindmap mm -> collectMindmap(out, mm.root());
            case Sankey sk -> {
                for (SankeyFlow f : sk.flows()) {
                    raw(out, f.source());
                    raw(out, f.target());               // values are geometry, never text
                }
            }
            case Matrix mx -> {
                for (String col : mx.columns()) {
                    raw(out, col);
                }
                for (Matrix.Row row : mx.rows()) {
                    raw(out, row.label());
                    for (Matrix.Cell cell : row.cells()) {
                        raw(out, cell.text());
                    }
                }
            }
            case Heatmap hm -> {
                for (String col : hm.columns()) {
                    raw(out, col);
                }
                for (Heatmap.Row row : hm.rows()) {
                    raw(out, row.label());
                    for (Heatmap.Cell cell : row.cells()) {
                        raw(out, cell.text());
                    }
                }
                raw(out, hm.lowLabel());
                raw(out, hm.highLabel());
            }
            case TensorNetwork tn -> {
                for (String core : tn.cores()) {
                    raw(out, core);                     // dispatched without math — never splits
                }
            }
            // Geometry-only types: verified zero text-emission sites in their layouts.
            case Snake ignored -> { }
            case YoungDiagram ignored -> { }
            case Knot ignored -> { }
            case Dynkin ignored -> { }
            case RootSystem ignored -> { }
        }
        raw(out, caption);                              // captions bake for every type, never split
        return out.toString();
    }

    private static void collectFlowchart(StringBuilder out, Flowchart fc, boolean mathLive) {
        for (FlowNode n : fc.nodes()) {
            split(out, n.label(), mathLive);
        }
        for (FlowEdge e : fc.edges()) {
            split(out, e.label(), mathLive);
        }
        for (FlowCluster c : fc.clusters()) {
            raw(out, c.title());                        // cluster titles never split
        }
    }

    private static void collectMindmap(StringBuilder out, MindmapNode node) {
        raw(out, node.text());
        for (MindmapNode child : node.children()) {
            collectMindmap(out, child);
        }
    }

    /// A label emitted through the MathLabel seam: with a live renderer only the plain Text runs
    /// bake; without one the WHOLE label (including `$…$` delimiters and latex) degrades to glyphs.
    private static void split(StringBuilder out, String label, boolean mathLive) {
        if (label == null || label.isEmpty()) {
            return;
        }
        if (!mathLive) {
            raw(out, label);
            return;
        }
        for (LabelRuns.Run run : LabelRuns.split(label)) {
            if (run instanceof LabelRuns.Text t) {
                raw(out, t.s());
            }
        }
    }

    private static void raw(StringBuilder out, String s) {
        if (s == null || s.isEmpty()) {
            return;
        }
        if (out.length() > 0) {
            out.append('\n');
        }
        out.append(s);
    }
}
