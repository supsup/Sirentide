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
    /// per docs/sirentide-output-contract.md milestone ledger).
    public static final Set<String> ALLOWED_ELEMENTS = Set.of("svg", "path", "rect", "line");

    /// Per-element allowed attribute set. NOTE: `xmlns` is emitted on the root `<svg>` but is
    /// missing from docs/sirentide-output-contract.md's M1 attribute table — it is added here so
    /// the allowlist matches real output. FOLLOW-UP for Confluence (contract-doc owner): add
    /// `xmlns` to the doc's geometry/identity table so doc ⊇ constants.
    public static final Map<String, Set<String>> ALLOWED_ATTRS = Map.of(
        "svg", Set.of("xmlns", "viewBox"),
        "path", Set.of("d", "fill"),
        "rect", Set.of("x", "y", "width", "height", "fill"),
        "line", Set.of("x1", "y1", "x2", "y2", "stroke", "stroke-width"));

    /// The presentation-colour grammar: a 6-digit hex, `currentColor`, or `none`. Exactly the set
    /// the sanitizer preserves; anything else (url(), rgb(), named colours, expressions) is a
    /// containment violation.
    public static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}|currentColor|none");

    /// True iff `v` is a contract-legal fill/stroke value.
    public static boolean isColor(String v) {
        return v != null && COLOR.matcher(v).matches();
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
            // geometry scalars: x, y, width, height, x1, y1, x2, y2, stroke-width
            default -> isFiniteNumber(value);
        };
    }
}
