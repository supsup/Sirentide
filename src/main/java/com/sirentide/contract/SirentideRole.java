package com.sirentide.contract;

import java.util.HashSet;
import java.util.Set;

/// The CLOSED set of semantic-anchor ROLES a Sirentide `<g data-sirentide-role>` may carry (plan
/// sirentide-semantic-anchor-g, contract sirentide/67 — the queryable/narratable "spin" foundation).
/// ONLY these wire strings may ever reach the emitter's `data-sirentide-role` attribute; the
/// {@link SirentideContract} validator + the ContainmentTest pin producer ⊆ this set, exactly like
/// the element/attribute allowlists.
///
/// This slice EMITS only {@link #NODE}/{@link #EDGE} (flowchart) and {@link #SLICE} (pie). The
/// remaining values are RESERVED so the later per-type slices (sequence actors/messages, bar-chart
/// bars, subgraph clusters, class boxes, scatter points, chart axes) can widen the EMITTER without
/// re-touching the closed contract — the allowlist already admits them. Adding a role here (not at a
/// call site) is the single reviewed choke point that keeps the role vocabulary closed.
public enum SirentideRole {
    NODE("node"),
    EDGE("edge"),
    SLICE("slice"),
    // -- reserved for follow-up per-type slices (NOT emitted yet; admitted by the contract so the
    // later slices are an emitter-only change) --------------------------------------------------
    ACTOR("actor"),
    MESSAGE("message"),
    BAR("bar"),
    CLUSTER("cluster"),
    CLASS("class"),
    POINT("point"),
    AXIS("axis");

    private final String wire;

    SirentideRole(String wire) {
        this.wire = wire;
    }

    /// The exact string emitted as the `data-sirentide-role` attribute value.
    public String wire() {
        return wire;
    }

    /// The closed set of legal wire strings — the single source of truth the containment validator
    /// pins to. Derived from the enum so it can never drift from the constants above.
    public static final Set<String> WIRE_VALUES;

    static {
        Set<String> s = new HashSet<>();
        for (SirentideRole r : values()) {
            s.add(r.wire);
        }
        WIRE_VALUES = Set.copyOf(s);
    }

    /// True iff `v` is a legal role wire string (member of the closed enum). Anything else is a
    /// containment violation.
    public static boolean isWire(String v) {
        return v != null && WIRE_VALUES.contains(v);
    }
}
