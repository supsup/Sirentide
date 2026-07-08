package com.sirentide.emit;

import com.sirentide.a11y.A11y;
import com.sirentide.contract.SirentideContract;
import com.sirentide.ir.Theme;
import com.sirentide.layout.Anchor;
import com.sirentide.layout.GlyphRun;
import com.sirentide.layout.Group;
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

    /// Back-compat overload: emit with NO accessibility payload (the inert/degenerate path and the
    /// direct-emit tests). Byte-identical to the pre-a11y output.
    public static String emit(LaidOut laid) {
        return emit(laid, A11y.NONE);
    }

    /// Emit a laid-out scene to SVG, carrying a deterministic accessibility payload: a root
    /// `role="img"`, a `<title>` (the diagram type/name), and a `<desc>` (the reading-order
    /// description built from the IR by {@link com.sirentide.a11y.A11yDescriber}). `<title>`/`<desc>`
    /// are the ONE place text lives as real text in Sirentide's output — they are not rendered
    /// visually, so their content is XML-escaped here rather than baked to glyph paths. A BLANK
    /// payload ({@link A11y#isBlank}) emits none of it, so the empty/inert shell is unchanged.
    public static String emit(LaidOut laid, A11y a11y) {
        return emit(laid, a11y, Theme.DEFAULT);
    }

    /// Emit under a {@link Theme} (config `%% theme:`). Adds TWO self-contained-readability touches so
    /// the SVG reads on ANY host page, without a media query or a second render:
    ///   - a THEME BACKGROUND `<rect>` covering the viewBox (drawn FIRST, under everything) in the
    ///     theme's {@link Theme#background()} — so a dark-page diagram carries its own surface instead
    ///     of letting the page show through. Skipped when the theme has no background ({@link
    ///     Theme#DEFAULT} stays transparent).
    ///   - a `currentColor` RESOLVE at every fill/stroke sink ({@link Theme#resolve}) so page-inheriting
    ///     structural text/lines take the theme's explicit {@link Theme#foreground()} — self-contained,
    ///     not dependent on the host page's text colour.
    /// Under {@link Theme#DEFAULT} both are no-ops (no bg rect + identity resolve), so this is
    /// BYTE-IDENTICAL to the two-arg {@code emit} — the whole point of option A (themes are additive).
    public static String emit(LaidOut laid, A11y a11y, Theme theme) {
        if (theme == null) {
            theme = Theme.DEFAULT;
        }
        StringBuilder sb = new StringBuilder();
        // Emit explicit width/height ALONGSIDE the viewBox. A viewBox-only root collapses/overlaps
        // inside the Stafficy /docs MD->HTML sanitizer (Confluence-flagged); intrinsic width/height
        // give it a concrete box. Added to SirentideContract's svg allowlist as an M1 widening.
        boolean a11yOn = a11y != null && !a11y.isBlank();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"")
            .append(fmt(laid.width())).append("\" height=\"").append(fmt(laid.height()))
            .append("\" viewBox=\"0 0 ")
            .append(fmt(laid.width())).append(' ').append(fmt(laid.height())).append('"');
        // role="img" is the standard SVG a11y hook: it makes the graphic an atomic image node whose
        // accessible name/description come from the <title>/<desc> below. Skipped (with them) for the
        // inert shell so a bare/failed bake stays byte-identical. The contract allows role only here.
        if (a11yOn) {
            sb.append(" role=\"img\"");
        }
        sb.append('>');
        if (a11yOn) {
            // <title> = the short accessible name; <desc> = the long reading-order description. Both
            // are the top-level diagram's a11y text (NEVER emitted inside a math fragment / nested).
            if (!a11y.title().isBlank()) {
                sb.append("<title>").append(xmlEscape(a11y.title())).append("</title>");
            }
            if (!a11y.desc().isBlank()) {
                sb.append("<desc>").append(xmlEscape(a11y.desc())).append("</desc>");
            }
        }
        // Self-contained background: a themed <rect> covering the whole viewBox, drawn BEFORE every
        // shape so it sits underneath. Only for a theme that carries a background (DEFAULT is null →
        // transparent, byte-identical). A plain in-alphabet <rect> with a contract-clean hex fill.
        if (theme.background() != null) {
            sb.append("<rect x=\"0\" y=\"0\" width=\"").append(fmt(laid.width()))
                .append("\" height=\"").append(fmt(laid.height()))
                .append("\" fill=\"").append(color(theme.background())).append("\"/>");
        }
        for (Shape shape : laid.shapes()) {
            appendShape(sb, shape, theme);
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

    private static void appendShape(StringBuilder sb, Shape shape, Theme theme) {
        switch (shape) {
            case Wedge w -> appendWedge(sb, w, theme);
            case GlyphRun g -> sb.append("<path d=\"").append(g.pathD())
                .append("\" fill=\"").append(color(theme.resolve(g.fill()))).append("\"/>");
            // Path mirrors GlyphRun exactly — a contract-clean `d` string + a colour-sink-validated
            // fill (flowchart arrowheads / future rounded nodes). Same <path> emission, same fill guard.
            case Path p -> sb.append("<path d=\"").append(p.d())
                .append("\" fill=\"").append(color(theme.resolve(p.fill()))).append("\"/>");
            case Rect r -> sb.append("<rect x=\"").append(fmt(r.x())).append("\" y=\"").append(fmt(r.y()))
                .append("\" width=\"").append(fmt(r.width())).append("\" height=\"").append(fmt(r.height()))
                .append("\" fill=\"").append(color(theme.resolve(r.fill()))).append("\"/>");
            case Line l -> sb.append("<line x1=\"").append(fmt(l.x1())).append("\" y1=\"").append(fmt(l.y1()))
                .append("\" x2=\"").append(fmt(l.x2())).append("\" y2=\"").append(fmt(l.y2()))
                .append("\" stroke=\"").append(color(theme.resolve(l.stroke())))
                .append("\" stroke-width=\"").append(fmt(l.strokeWidth())).append("\"/>");
            // An inline-math fragment: place it on the label baseline with a numeric translate and
            // embed its already-contract-clean inner markup verbatim (FragmentGuard ran at layout
            // time). The transform stays inside SirentideContract.TRANSFORM's numeric grammar; the
            // fill stamps the label's contrast colour so a currentColor fragment inherits it (F2).
            case MathBox b -> sb.append("<g fill=\"").append(color(theme.resolve(b.fill())))
                .append("\" transform=\"translate(").append(fmt(b.x())).append(' ')
                .append(fmt(b.y())).append(")\">").append(b.innerSvg()).append("</g>");
            // A semantic anchor group (plan sirentide-semantic-anchor-g): wrap the element's shapes
            // in a `<g>` carrying ONLY the closed data-sirentide-role/id/seq set — additive, no
            // geometry. Members are re-dispatched through appendShape, so the inner emission is
            // byte-identical to their ungrouped form.
            case Group grp -> appendGroup(sb, grp, theme);
        }
    }

    /// Emit a logical element's shapes wrapped in a semantic `<g>` carrying ONLY the closed anchor
    /// attribute set (`data-sirentide-role`/`-id`/`-seq`; plan sirentide-semantic-anchor-g). The role
    /// is the closed enum's wire string; the id was charset-sanitized to `[A-Za-z0-9_-]{1,32}` and the
    /// seq range-checked when the {@link Anchor} was built — so NONE of the three can smuggle a quote,
    /// angle bracket, or foreign attribute, and they are appended RAW (no escaping needed). The `<g>`
    /// carries NO transform/fill/style: the members' geometry is byte-identical to their ungrouped
    /// emission, so a grouped diagram differs from its ungrouped self only by these `<g>`/`</g>` tags.
    private static void appendGroup(StringBuilder sb, Group grp, Theme theme) {
        Anchor a = grp.anchor();
        sb.append("<g data-sirentide-role=\"").append(a.role().wire())
            .append("\" data-sirentide-id=\"").append(a.id())
            .append("\" data-sirentide-seq=\"").append(a.seq()).append("\">");
        for (Shape m : grp.members()) {
            appendShape(sb, m, theme);
        }
        sb.append("</g>");
    }

    private static void appendWedge(StringBuilder sb, Wedge w, Theme theme) {
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
        sb.append("\" fill=\"").append(color(theme.resolve(w.fill()))).append("\"/>");
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

    /// XML-escape a11y TEXT for `<title>`/`<desc>`: `&`, `<`, `>`, `"` become entities so a label
    /// containing markup metacharacters (`A < B`, `a & b`, a stray `"`) can never break the SVG or
    /// smuggle an element. `&` is escaped FIRST so an already-produced entity isn't double-escaped.
    /// This is the containment guarantee for the one text-as-text seam in the output.
    private static String xmlEscape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> out.append(c);
            }
        }
        return out.toString();
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
