package com.sirentide.contract;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/// The Sirentide emitter-output ALLOWLIST, as shared constants — the single source of truth the
/// emitter (producer), the ContainmentTest (build-failing drift guard), and the Stafficy sanitizer
/// (consumer) all pin to. Derived from docs/sirentide-output-contract.md's M1 alphabet, narrowed
/// to *what the emitter actually emits today* (svg/path/rect/line only — no g/polyline/polygon/
/// circle/ellipse yet; those widen when SvgEmitter emits them, per the milestone growth ledger).
///
/// This is the LatteX-S8 mechanism the output contract calls for: producer ⊆ contract ⊆ sanitizer,
/// guarded by a real allowlist test rather than a fragile denylist spot-check.
public final class SirentideContract {

    private SirentideContract() {}

    /// The XML namespace every Sirentide SVG root carries.
    public static final String SVG_NS = "http://www.w3.org/2000/svg";

    /// The elements the emitter may emit. Grows only when SvgEmitter emits a new one (reviewed,
    /// per docs/sirentide-output-contract.md milestone ledger). `g` joined at the math-in-labels
    /// milestone (RFC sirentide/39): SvgEmitter emits `<g transform="…">` to place an inline-math
    /// fragment (a MathBox) on the label baseline. It carries ONLY a numeric `transform` (and, on
    /// the fragment's own inner elements, `fill`) — no `role`/`data-*` yet; those ride the later
    /// anchor-layer widening with Confluence's value-scrub + Lattice sign-off (Conf /41).
    public static final Set<String> ALLOWED_ELEMENTS = Set.of("svg", "path", "rect", "line", "g");

    /// Per-element allowed attribute set. NOTE: `xmlns` is emitted on the root `<svg>` but is
    /// missing from docs/sirentide-output-contract.md's M1 attribute table — it is added here so
    /// the allowlist matches real output. FOLLOW-UP for Confluence (contract-doc owner): add
    /// `xmlns` to the doc's geometry/identity table so doc ⊇ constants.
    public static final Map<String, Set<String>> ALLOWED_ATTRS = Map.of(
        // M1 (labels milestone) widening: `width`/`height` join `viewBox` on the root <svg>. A
        // viewBox-only root collapses inside the Stafficy /docs sanitizer; intrinsic width/height
        // fix that. Both are geometry scalars (finite-numeric), so the value grammar is unchanged.
        "svg", Set.of("xmlns", "viewBox", "width", "height"),
        // math-in-labels S2 widening: `path` carries a numeric-grammar `transform`. Real LatteX
        // places every glyph with its own `translate(...) scale(...)` on the <path> (glyph outlines
        // are emitted in font units, then scaled/positioned per-glyph) — so a baked-math fragment's
        // paths carry transforms. FragmentGuard already permits path/transform and the output-contract
        // doc's geometry row already lists it; this reconciles the containment allowlist to match
        // (same drift-class as F1's `g fill`). TRANSFORM stays the numeric-only grammar (no url/rotate).
        "path", Set.of("d", "fill", "transform"),
        "rect", Set.of("x", "y", "width", "height", "fill"),
        "line", Set.of("x1", "y1", "x2", "y2", "stroke", "stroke-width"),
        // math-in-labels widening: `g` carries a numeric-grammar `transform` (see TRANSFORM) and
        // an optional `fill` (F1, Conf pins sirentide/51): the MathBox wrapper stamps the label's
        // contrast fill so `currentColor` math inherits it (not black-on-dark), and the guard
        // already permits inner-element `fill` — this reconciles contract == guard == doc-intent.
        "g", Set.of("transform", "fill"));

    /// The presentation-colour INPUT grammar: a 6-digit hex, a 3-digit shorthand hex, `currentColor`,
    /// or `none`. Exactly the set the sanitizer preserves; anything else (url(), rgb(), named colours,
    /// expressions) is a containment violation. NOTE: 3-digit `#rgb` is accepted for INPUT only — it
    /// is normalized (expanded) to `#rrggbb` by {@link #normalizeColor} before it ever reaches the
    /// emitter, so the emitted wire form is ALWAYS 6-digit (canonical, sanitizer-safe regardless of
    /// whether the /docs sanitizer accepts the short form).
    public static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}|#[0-9a-fA-F]{3}|currentColor|none");

    /// The `transform` value grammar admitted on `<g>` (and on math-fragment inner elements via
    /// {@link FragmentGuard}): one or more `translate(...)`/`scale(...)`/`matrix(...)` whose args
    /// are ONLY finite decimals, separated by commas/whitespace. NUMERIC-ONLY BY CONSTRUCTION —
    /// no `url(...)`, no `rotate(...cx cy...)` reference tricks, no expressions — so a transform can
    /// carry placement/scale but never a script hook or an external reference (RFC sirentide/39,
    /// Conf /41: transform stays a producer-contract numeric grammar). A single finite number is
    /// `-?\d+(\.\d+)?`; args are number(sep number)*; the whole value is one-or-more such ops.
    public static final Pattern TRANSFORM = Pattern.compile(
        "\\s*((translate|scale|matrix)\\(\\s*-?\\d+(\\.\\d+)?(\\s*[, ]\\s*-?\\d+(\\.\\d+)?)*\\s*\\)\\s*)+");

    /// True iff `v` is a contract-legal fill/stroke INPUT value (accepts both hex widths).
    public static boolean isColor(String v) {
        return v != null && COLOR.matcher(v).matches();
    }

    /// True iff `v` is a HEX colour (`#rgb` or `#rrggbb`) — the subset legal as a per-ITEM fill.
    /// Per-item colours (a pie wedge / bar / dot / gantt bar) must resolve to a concrete swatch:
    /// `currentColor` and `none` are meaningless there (a `none` wedge is invisible; a `currentColor`
    /// wedge has no concrete value for the on-slice contrast calc — the H1 blank-pie footgun). They
    /// stay legal only for the off-slice text `color=` header (see {@link #isColor}).
    public static boolean isHexColor(String v) {
        return v != null && v.startsWith("#") && isColor(v);
    }

    /// Canonicalizes a contract-legal colour to its emitted wire form: a 3-digit `#rgb` shorthand
    /// EXPANDS to `#rrggbb` (so the emitter never carries a short hex); a 6-digit hex, `currentColor`,
    /// and `none` pass through unchanged. `null` → `null`. Call this on any colour before it reaches
    /// the IR/emitter so every emitted fill is a canonical 6-digit hex.
    public static String normalizeColor(String v) {
        if (v != null && v.length() == 4 && v.charAt(0) == '#' && isColor(v)) {
            char r = v.charAt(1), g = v.charAt(2), b = v.charAt(3);
            return new StringBuilder(7).append('#')
                .append(r).append(r).append(g).append(g).append(b).append(b).toString();
        }
        return v;
    }

    /// True iff `v` parses to a finite (non-NaN, non-Infinity) number.
    public static boolean isFiniteNumber(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        try {
            return Double.isFinite(Double.parseDouble(v.trim()));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /// True iff `v` is a `viewBox`: exactly four finite numbers.
    public static boolean isViewBox(String v) {
        if (v == null) {
            return false;
        }
        String[] parts = v.trim().split("\\s+");
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            if (!isFiniteNumber(p)) {
                return false;
            }
        }
        return true;
    }

    /// True iff `v` is a legal path-data string: only path command letters, digits, and numeric
    /// punctuation. Rejects anything that could smuggle a url(), an expression, or markup.
    public static boolean isPathData(String v) {
        if (v == null || v.isEmpty()) {
            return false;
        }
        for (int i = 0; i < v.length(); i++) {
            if (!isPathChar(v.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPathChar(char c) {
        return (c >= '0' && c <= '9')
            || Character.isWhitespace(c)
            || c == '.' || c == '-' || c == '+' || c == ',' || c == 'e' || c == 'E'
            || "MLHVCSQTAZmlhvcsqtaz".indexOf(c) >= 0;
    }

    /// Validates a single attribute value against the constraint for its attribute name. Used by
    /// the ContainmentTest to assert every geometry value is finite-numeric / valid path-data and
    /// every fill/stroke matches the colour grammar.
    public static boolean attributeValueValid(String attr, String value) {
        return switch (attr) {
            case "fill", "stroke" -> isColor(value);
            case "d" -> isPathData(value);
            case "viewBox" -> isViewBox(value);
            case "xmlns" -> SVG_NS.equals(value);
            case "transform" -> TRANSFORM.matcher(value).matches();
            // geometry scalars: x, y, width, height, x1, y1, x2, y2, stroke-width
            default -> isFiniteNumber(value);
        };
    }
}
