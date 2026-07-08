package com.sirentide.layout;

import com.sirentide.contract.SirentideContract;
import com.sirentide.contract.SirentideRole;

/// A semantic ANCHOR stamped on the group of shapes that draw ONE logical diagram element (plan
/// sirentide-semantic-anchor-g). It carries the CLOSED, sanitizer-safe attribute set the emitter
/// writes onto the element's `<g>` wrapper: a {@link SirentideRole} (the closed role enum), a
/// page-local deterministic `id` matching `^[A-Za-z0-9_-]{1,32}$`, and a non-negative emit-order
/// `seq`. Invariants are enforced in the compact constructor so a malformed anchor can NEVER reach
/// the sink — the emitter appends id/role/seq RAW (no escaping), relying on these guarantees.
public record Anchor(SirentideRole role, String id, int seq) {

    public Anchor {
        if (role == null) {
            throw new IllegalArgumentException("anchor role must not be null");
        }
        if (id == null || !SirentideContract.ANCHOR_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                "anchor id must match " + SirentideContract.ANCHOR_ID.pattern() + ", got: " + id);
        }
        if (seq < 0) {
            throw new IllegalArgumentException("anchor seq must be non-negative, got: " + seq);
        }
    }

    /// Sanitize a raw element id/label to the anchor-id charset `[A-Za-z0-9_-]`, truncated to 32
    /// chars. SECURITY-RELEVANT (this is why the anchor layer was gated): this is the gate that
    /// guarantees a hostile label (a `<script>a"b`, unicode, quotes, angle brackets, whitespace) can
    /// NEVER place a character outside the charset into the `<g>` attribute — every illegal char is
    /// DROPPED, never escaped or substituted. May return "" when the raw string has no legal char;
    /// the caller supplies a fallback + page-local uniqueness (see {@link AnchorAssigner}). The
    /// result is NOT guaranteed unique — it is guaranteed CHARSET-legal and length-bounded.
    public static String sanitizeId(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), 32));
        for (int i = 0; i < raw.length() && sb.length() < 32; i++) {
            char c = raw.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
