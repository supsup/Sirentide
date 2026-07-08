package com.sirentide.layout;

import com.sirentide.contract.SirentideRole;
import java.util.HashSet;
import java.util.Set;

/// Per-diagram anchor factory: turns a raw element id/label into a deterministic {@link Anchor} with
/// a page-local UNIQUE id and a monotonically increasing emit-order `seq`. Sanitizes via
/// {@link Anchor#sanitizeId}, falls back to the role's wire name when the raw string has no legal
/// char, and resolves id collisions by appending `-<k>` (re-truncated so the id stays within the
/// 32-char bound). ONE instance per rendered diagram → `seq` runs 0..N in emit order and ids are
/// unique within that diagram. Fully deterministic (no random/timestamp): identical input → identical
/// anchors, byte-identical bakes (DESIGN §6).
final class AnchorAssigner {

    private final Set<String> used = new HashSet<>();
    private int seq = 0;

    /// Mint the next anchor: sanitize `rawBase`, fall back to the role name if empty, uniquify, and
    /// stamp the next emit-order `seq`. Every returned id is charset-legal, length-bounded, and unique
    /// within this diagram.
    Anchor assign(SirentideRole role, String rawBase) {
        String base = Anchor.sanitizeId(rawBase);
        if (base.isEmpty()) {
            base = role.wire();   // always charset-legal (lowercase ascii), keeps the id non-empty
        }
        String id = base;
        int k = 1;
        while (!used.add(id)) {
            String suffix = "-" + k++;
            String head = base.length() + suffix.length() > 32
                ? base.substring(0, 32 - suffix.length())
                : base;
            id = head + suffix;
        }
        return new Anchor(role, id, seq++);
    }
}
