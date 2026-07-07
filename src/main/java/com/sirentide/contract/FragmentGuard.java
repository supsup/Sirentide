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
    private static final Set<String> ELEMENTS = Set.of("g", "path");

    /// Attributes allowed per element.
    private static final Map<String, Set<String>> ATTRS = Map.of(
        "g", Set.of("transform", "fill"),
        "path", Set.of("d", "fill", "transform"));

    /// Matches one tag token: `<g …>`, `</g>`, `<path …/>`. The attr blob is captured loosely and
    /// validated separately; the `[^<>]*` body guarantees no nested `<`/`>` sneaks inside a tag.
    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z]+)([^<>]*?)(/?)>");

    /// One `name="value"` attribute inside a tag body.
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z:_-]+)\\s*=\\s*\"([^\"]*)\"");

    /// The `d` path-data grammar: SVG path commands + finite numbers/separators only. No letters
    /// beyond the command set, so no `url(`, no function calls.
    private static final Pattern PATH_D = Pattern.compile("[MmLlHhVvCcSsQqTtAaZz0-9eE .,+-]*");

    /// True iff `innerSvg` is a contract-clean fragment (only g/path, allowed attrs, legal values).
    /// A `null` or blank fragment is NOT clean (there is nothing to trust).
    public static boolean isClean(String innerSvg) {
        if (innerSvg == null || innerSvg.isBlank()) {
            return false;
        }
        Matcher m = TAG.matcher(innerSvg);
        int cursor = 0;
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
            if (!ELEMENTS.contains(name)) {
                return false;
            }
            if (closing) {
                // A closing tag carries no attributes.
                if (!body.isBlank()) {
                    return false;
                }
                continue;
            }
            if (!attrsClean(name, body)) {
                return false;
            }
        }
        // Trailing text after the last tag must also be bracket-free.
        String tail = innerSvg.substring(cursor);
        return tail.indexOf('<') < 0 && tail.indexOf('>') < 0;
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
            default -> false;
        };
    }
}
