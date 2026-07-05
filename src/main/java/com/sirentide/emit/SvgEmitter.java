package com.sirentide.emit;

import com.sirentide.layout.GlyphRun;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.Shape;
import com.sirentide.layout.Wedge;

/// Pure emit: a laid-out scene → SVG string, using ONLY the sirentide-output-contract alphabet
/// (svg/g/path + geometry/fill; docs/DESIGN.md §4/§5). Contract-clean by construction — no
/// script/style/foreignObject/on*/href ever. Numbers are formatted deterministically (§6).
public final class SvgEmitter {

    private SvgEmitter() {}

    public static String emit(LaidOut laid) {
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
            .append(fmt(laid.width())).append(' ').append(fmt(laid.height())).append("\">");
        for (Shape shape : laid.shapes()) {
            appendShape(sb, shape);
        }
        sb.append("</svg>");
        return sb.toString();
    }

    private static void appendShape(StringBuilder sb, Shape shape) {
        switch (shape) {
            case Wedge w -> appendWedge(sb, w);
            case GlyphRun g -> sb.append("<path d=\"").append(g.pathD())
                .append("\" fill=\"").append(g.fill()).append("\"/>");
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
        sb.append("\" fill=\"").append(w.fill()).append("\"/>");
    }

    /// Deterministic, locale-independent number formatting: 3 decimal places, integer when
    /// whole. Byte-identical bakes depend on this (docs/DESIGN.md §6).
    private static String fmt(double v) {
        double r = Math.round(v * 1000.0) / 1000.0;
        return r == Math.rint(r) && !Double.isInfinite(r)
            ? Long.toString((long) r)
            : Double.toString(r);
    }
}
