package com.sirentide.emit;

import com.sirentide.contract.SirentideContract;
import com.sirentide.layout.GlyphRun;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.Line;
import com.sirentide.layout.MathBox;
import com.sirentide.layout.Path;
import com.sirentide.layout.Rect;
import com.sirentide.layout.Shape;
import com.sirentide.layout.Wedge;

/// Pure emit: a laid-out scene → SVG string, using ONLY the sirentide-output-contract alphabet
/// (svg/path/rect/line + geometry/fill; docs/DESIGN.md §4/§5). Contract-clean by construction — no
/// script/style/foreignObject/on*/href ever. Numbers are formatted deterministically (§6).
public final class SvgEmitter {

    private SvgEmitter() {}

    /// Incremental output cap: the buffer is bounded DURING construction, not just checked after.
    /// MUST stay in lockstep with {@link com.sirentide.api.Sirentide#MAX_OUTPUT_BYTES} (duplicated
    /// here rather than imported to avoid an api↔emit package cycle). Without this bound a runaway
    /// layout could build a multi-GB StringBuilder before the post-emit check in `render` ever ran
    /// (H2: a legal timeline of MAX_DATA_ROWS × MAX_LABEL_LEN labels reached ~5.9 GB and OOM'd).
    static final int MAX_OUTPUT_BYTES = 5_000_000;   // 5 MB of SVG — mirrors Sirentide.MAX_OUTPUT_BYTES

    public static String emit(LaidOut laid) {
        StringBuilder sb = new StringBuilder();
        // Emit explicit width/height ALONGSIDE the viewBox. A viewBox-only root collapses/overlaps
        // inside the Stafficy /docs MD->HTML sanitizer (Confluence-flagged); intrinsic width/height
        // give it a concrete box. Added to SirentideContract's svg allowlist as an M1 widening.
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"")
            .append(fmt(laid.width())).append("\" height=\"").append(fmt(laid.height()))
            .append("\" viewBox=\"0 0 ")
            .append(fmt(laid.width())).append(' ').append(fmt(laid.height())).append("\">");
        for (Shape shape : laid.shapes()) {
            appendShape(sb, shape);
            // Bound the buffer as it grows: a cheap sb.length() compare after each shape stops a
            // runaway layout before it can allocate a multi-GB document. Thrown as a RuntimeException
            // so Sirentide.render catches it and degrades to the inert shell (never propagates a bake).
            if (sb.length() > MAX_OUTPUT_BYTES) {
                throw new IllegalStateException(
                    "Sirentide emitter: output exceeded MAX_OUTPUT_BYTES (" + MAX_OUTPUT_BYTES + ")");
            }
        }
        sb.append("</svg>");
        return sb.toString();
    }

    private static void appendShape(StringBuilder sb, Shape shape) {
        switch (shape) {
            case Wedge w -> appendWedge(sb, w);
            case GlyphRun g -> sb.append("<path d=\"").append(g.pathD())
                .append("\" fill=\"").append(color(g.fill())).append("\"/>");
            // Path mirrors GlyphRun exactly — a contract-clean `d` string + a colour-sink-validated
            // fill (flowchart arrowheads / future rounded nodes). Same <path> emission, same fill guard.
            case Path p -> sb.append("<path d=\"").append(p.d())
                .append("\" fill=\"").append(color(p.fill())).append("\"/>");
            case Rect r -> sb.append("<rect x=\"").append(fmt(r.x())).append("\" y=\"").append(fmt(r.y()))
                .append("\" width=\"").append(fmt(r.width())).append("\" height=\"").append(fmt(r.height()))
                .append("\" fill=\"").append(color(r.fill())).append("\"/>");
            case Line l -> sb.append("<line x1=\"").append(fmt(l.x1())).append("\" y1=\"").append(fmt(l.y1()))
                .append("\" x2=\"").append(fmt(l.x2())).append("\" y2=\"").append(fmt(l.y2()))
                .append("\" stroke=\"").append(color(l.stroke()))
                .append("\" stroke-width=\"").append(fmt(l.strokeWidth())).append("\"/>");
            // An inline-math fragment: place it on the label baseline with a numeric translate and
            // embed its already-contract-clean inner markup verbatim (FragmentGuard ran at layout
            // time). The transform value stays inside SirentideContract.TRANSFORM's numeric grammar.
            case MathBox b -> sb.append("<g transform=\"translate(").append(fmt(b.x())).append(' ')
                .append(fmt(b.y())).append(")\">").append(b.innerSvg()).append("</g>");
        }
    }

    private static void appendWedge(StringBuilder sb, Wedge w) {
        double x0 = w.cx() + w.r() * Math.cos(w.a0());
        double y0 = w.cy() + w.r() * Math.sin(w.a0());
        double sweep = w.a1() - w.a0();

        sb.append("<path d=\"");
        if (sweep >= 2 * Math.PI - 1e-6) {
            // A single full-circle slice: a centre-anchored wedge degenerates at 2π, so draw the
            // disc as two semicircle arcs (no centre point).
            double xm = w.cx() + w.r() * Math.cos(w.a0() + Math.PI);
            double ym = w.cy() + w.r() * Math.sin(w.a0() + Math.PI);
            sb.append("M ").append(fmt(x0)).append(' ').append(fmt(y0))
                .append(" A ").append(fmt(w.r())).append(' ').append(fmt(w.r()))
                .append(" 0 1 1 ").append(fmt(xm)).append(' ').append(fmt(ym))
                .append(" A ").append(fmt(w.r())).append(' ').append(fmt(w.r()))
                .append(" 0 1 1 ").append(fmt(x0)).append(' ').append(fmt(y0)).append(" Z");
        } else {
            double x1 = w.cx() + w.r() * Math.cos(w.a1());
            double y1 = w.cy() + w.r() * Math.sin(w.a1());
            int largeArc = sweep > Math.PI ? 1 : 0;
            sb.append("M ").append(fmt(w.cx())).append(' ').append(fmt(w.cy()))
                .append(" L ").append(fmt(x0)).append(' ').append(fmt(y0))
                .append(" A ").append(fmt(w.r())).append(' ').append(fmt(w.r()))
                .append(" 0 ").append(largeArc).append(" 1 ")
                .append(fmt(x1)).append(' ').append(fmt(y1)).append(" Z");
        }
        sb.append("\" fill=\"").append(color(w.fill())).append("\"/>");
    }

    /// The sink's last-line-of-defense on presentation colour: fill/stroke values MUST match the
    /// contract's `#hex | currentColor | none` grammar. A violation is an internal invariant break
    /// (a layout bug feeding a bad colour), NOT user input — so fail LOUD naming the value, rather
    /// than silently emitting a sanitizer-stripped or injection-carrying attribute.
    private static String color(String c) {
        if (!SirentideContract.isColor(c)) {
            throw new IllegalStateException(
                "Sirentide emitter: non-contract fill/stroke value reached the sink: \"" + c + "\"");
        }
        return c;
    }

    /// Deterministic, locale-independent number formatting: 3 decimal places, integer when
    /// whole. Byte-identical bakes depend on this (docs/DESIGN.md §6).
    private static String fmt(double v) {
        // Last line of defense against a non-finite geometry leak: S4 rejects NaN/Infinity at
        // parse, but if one still reaches the emitter, clamp to 0 rather than emit the literal
        // "Infinity"/"NaN" (which is not valid SVG numeric syntax and breaks the bake silently).
        if (!Double.isFinite(v)) {
            v = 0.0;
        }
        double r = Math.round(v * 1000.0) / 1000.0;
        return r == Math.rint(r)
            ? Long.toString((long) r)
            : Double.toString(r);
    }
}
