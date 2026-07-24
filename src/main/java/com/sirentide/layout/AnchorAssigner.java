package com.sirentide.layout;

import com.sirentide.contract.SirentideRole;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
    // The next suffix not yet tried FOR THIS sanitized base. Once a candidate has been tried it stays
    // occupied forever (`used` only grows), so restarting at `-1` cannot discover a newly-free id; it
    // only repeats old failed probes. Map lookup order is irrelevant (we never iterate it), preserving
    // deterministic emit order and ids.
    private final Map<String, Integer> nextSuffixByBase = new HashMap<>();
    private long uniquenessProbeCount = 0;
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
        Integer nextSuffix = nextSuffixByBase.get(base);
        if (nextSuffix == null && claim(id)) {
            // Remember that the bare id is permanently occupied. The next collision can begin at -1
            // without probing the bare id again.
            nextSuffixByBase.put(base, 1);
        } else {
            int k = nextSuffix != null ? nextSuffix : 1;
            while (true) {
                String suffix = "-" + k++;
                String head = base.length() + suffix.length() > 32
                    ? base.substring(0, 32 - suffix.length())
                    : base;
                id = head + suffix;
                if (claim(id)) {
                    nextSuffixByBase.put(base, k);
                    break;
                }
            }
        }
        return new Anchor(role, id, seq++);
    }

    /// One exact uniqueness-set probe. Package-private count is a deterministic complexity receipt;
    /// it is deliberately not wall-clock instrumentation.
    private boolean claim(String id) {
        uniquenessProbeCount++;
        return used.add(id);
    }

    long uniquenessProbeCount() {
        return uniquenessProbeCount;
    }
}
