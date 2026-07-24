package com.sirentide.contract;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// The defensive containment scan at the math-fragment seam (RFC sirentide/39). A
/// {@link com.sirentide.api.MathFragment} is FOREIGN markup (produced by a math renderer, not the
/// font oracle), so before Sirentide embeds it in its own output it must prove the fragment stays
/// inside the emitter contract: ONLY `g`/`path` elements, ONLY the `d`/`fill`/`transform`
/// attributes, `fill` a legal colour, `transform` a strict numeric grammar. Anything else — any
/// other element or attribute, `url(...)`, `<script`, `href`, `on*` — fails the scan and the
/// fragment is treated exactly like a render failure (raw-`$…$`-text fallback).
///
/// Allowlist by construction, not a denylist: a token is clean only if it MATCHES the permitted
/// shape, so an unanticipated hostile construct fails closed. The scanner is a small hand-rolled
/// tag tokenizer (zero-dep: no XML library).
public final class FragmentGuard {

    private FragmentGuard() {}

    /// Elements a fragment may contain.
    // {g, path, rect}: LatteX emits fraction bars + radical bars as <rect> (SvgEmitter rule-emit),
    // so a fragment for $\frac{a}{b}$ / $\sqrt{2}$ carries a rect — admit it or the commonest label
    // math degrades to text (math-in-labels S2; the /docs sanitizer already allows rect).
    private static final Set<String> ELEMENTS = Set.of("g", "path", "rect");

    /// Attributes allowed per element.
    private static final Map<String, Set<String>> ATTRS = Map.of(
        "g", Set.of("transform", "fill"),
        "path", Set.of("d", "fill", "transform"),
        // rect (fraction/radical bar): numeric geometry + optional legal fill (LatteX 0.4.0+ stamps
        // a fill on the bar for a \color'd fraction, so a colored bar-in-a-label stays colored).
        "rect", Set.of("x", "y", "width", "height", "fill"));

    /// Matches one tag token: `<g …>`, `</g>`, `<path …/>`. The attr blob is captured loosely and
    /// validated separately; the `[^<>]*` body guarantees no nested `<`/`>` sneaks inside a tag.
    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z]+)([^<>]*?)(/?)>");

    /// One `name="value"` attribute inside a tag body.
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z:_-]+)\\s*=\\s*\"([^\"]*)\"");

    /// The `d` path-data grammar: SVG path commands + finite numbers/separators only. No letters
    /// beyond the command set, so no `url(`, no function calls.
    private static final Pattern PATH_D = Pattern.compile("[MmLlHhVvCcSsQqTtAaZz0-9eE .,+-]*");

    /// A single finite decimal — the numeric grammar for a rect's x/y/width/height (no functions,
    /// no url(), no expressions). Matches the deterministic form the emitter's fmt() produces.
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    /// A contract-clean fragment longer than this is treated as NOT clean, so the caller degrades to
    /// the raw `$…$` source (robustness plan fe8c5bbc #6): {@link #isClean} validates the fragment
    /// SHAPE but not its LENGTH, so a legitimately clean-but-enormous fragment would otherwise be
    /// held whole. A real inline-math fragment (rendered from a ≤MAX_LABEL_LEN source) never
    /// approaches this ceiling — it only bites a pathological one. 64 KiB.
    public static final int MAX_FRAGMENT_LEN = 65_536;

    /// The font-size multiplier that bounds a fragment's box metrics (SIR-08). A single inline-math
    /// fragment's width/height/depth is a small multiple of the font size (a tall matrix is ~10 em; a
    /// long fraction a few dozen em). This ceiling — 10 000 em — is astronomically wider than any
    /// legitimate inline fragment yet decisively rejects the DoS metrics an untrusted renderer can
    /// emit: a NaN/Inf/1e308 extent (a ~9.2e15-px viewBox that OOMs a rasterizer). At a 16 px font the
    /// cap is 160 000 px, whose viewBox area (~2.5e10) is ~5 orders of magnitude under the attack and
    /// ~15 under 1e308, while leaving a real fragment (≤ ~50 em) four orders of headroom. Multiplying
    /// by the font size keeps the bound proportional so it never rejects a large-font-size fragment.
    public static final double MAX_METRIC_EM = 10_000;

    /// True iff a fragment's box metrics are trustworthy (SIR-08): every value FINITE (not NaN/±Inf),
    /// width/height/depth NON-NEGATIVE, and each extent within a font-size-derived ceiling
    /// ({@link #MAX_METRIC_EM} × the font size). A violating metric is treated exactly like a
    /// contract-violating structure — the caller degrades the fragment to its raw `$…$` source text
    /// (see {@link com.sirentide.layout.MathLabel}). The font-size floor of 1.0 keeps a degenerate
    /// tiny/zero font size from collapsing the ceiling to zero and wrongly rejecting a valid fragment.
    public static boolean metricsClean(double widthPx, double heightPx, double depthPx, double fontSizePx) {
        if (!Double.isFinite(widthPx) || !Double.isFinite(heightPx) || !Double.isFinite(depthPx)) {
            return false;
        }
        if (widthPx < 0 || heightPx < 0 || depthPx < 0) {
            return false;
        }
        double limit = MAX_METRIC_EM * Math.max(1.0, fontSizePx);
        return widthPx <= limit && heightPx <= limit && depthPx <= limit;
    }

    /// True iff `innerSvg` is a contract-clean fragment (only g/path, allowed attrs, legal values).
    /// A `null` or blank fragment is NOT clean (there is nothing to trust).
    public static boolean isClean(String innerSvg) {
        if (innerSvg == null || innerSvg.isBlank()) {
            return false;
        }
        if (innerSvg.length() > MAX_FRAGMENT_LEN) {
            return false;   // #6: don't trust an oversize fragment whole — degrade to raw $…$
        }
        Matcher m = TAG.matcher(innerSvg);
        int cursor = 0;
        // SIR-02: a name stack proves STRUCTURE, not just token shape. Every open tag pushes; a close
        // must match the top and pop; at EOF the stack must be empty. This makes the fragment
        // self-contained — it can neither leave a tag open (unbalanced) nor close a tag it did not
        // open (a `</g>` that would close the emitter's OWN wrapper group = containment escape). A
        // foreign `<path>` therefore can never draw outside the emitter's translate/fill wrapper.
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        boolean sawElement = false;
        while (m.find()) {
            // Any character BETWEEN tags that is a bracket means malformed/hostile markup.
            String between = innerSvg.substring(cursor, m.start());
            if (between.indexOf('<') >= 0 || between.indexOf('>') >= 0) {
                return false;
            }
            cursor = m.end();

            boolean closing = !m.group(1).isEmpty();
            String name = m.group(2).toLowerCase(java.util.Locale.ROOT);
            String body = m.group(3);
            boolean selfClose = !m.group(4).isEmpty();
            if (!ELEMENTS.contains(name)) {
                return false;
            }
            sawElement = true;
            if (closing) {
                // A closing tag carries no attributes and can NOT also be self-closed (`</g/>`).
                if (selfClose || !body.isBlank()) {
                    return false;
                }
                // Must close the innermost still-open element: non-empty stack whose top matches.
                // An empty stack here means the fragment is closing a tag it never opened — the
                // emitter-wrapper escape. A mismatch means crossed/interleaved nesting.
                if (stack.isEmpty() || !stack.peek().equals(name)) {
                    return false;
                }
                stack.pop();
                continue;
            }
            if (!attrsClean(name, body)) {
                return false;
            }
            // A self-closed element (`<path …/>`) is balanced by itself; a plain open tag pushes.
            if (!selfClose) {
                stack.push(name);
            }
        }
        // Trailing text after the last tag must also be bracket-free.
        String tail = innerSvg.substring(cursor);
        if (tail.indexOf('<') >= 0 || tail.indexOf('>') >= 0) {
            return false;
        }
        // Every opened element must have been closed, and a trusted fragment carries ≥1 element.
        return sawElement && stack.isEmpty();
    }

    private static boolean attrsClean(String element, String body) {
        Set<String> allowed = ATTRS.get(element);
        Matcher a = ATTR.matcher(body);
        int cursor = 0;
        while (a.find()) {
            // Reject stray non-whitespace between attributes (valueless/malformed attrs).
            if (!body.substring(cursor, a.start()).isBlank()) {
                return false;
            }
            cursor = a.end();
            String name = a.group(1).toLowerCase(java.util.Locale.ROOT);
            String value = a.group(2);
            if (!allowed.contains(name)) {
                return false;
            }
            if (!valueClean(name, value)) {
                return false;
            }
        }
        // Whatever remains after the last attribute must be whitespace only.
        return body.substring(cursor).isBlank();
    }

    private static boolean valueClean(String name, String value) {
        return switch (name) {
            case "fill" -> SirentideContract.isColor(value);
            case "transform" -> SirentideContract.TRANSFORM.matcher(value).matches();
            case "d" -> PATH_D.matcher(value).matches();
            // rect geometry scalars: a single finite decimal, same numeric class the emitter formats.
            case "x", "y", "width", "height" -> NUMBER.matcher(value).matches();
            default -> false;
        };
    }
}
