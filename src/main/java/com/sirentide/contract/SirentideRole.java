package com.sirentide.contract;

import java.util.HashSet;
import java.util.Set;

/// The CLOSED set of semantic-anchor ROLES a Sirentide `<g data-sirentide-role>` may carry (plan
/// sirentide-semantic-anchor-g, contract sirentide/67 — the queryable/narratable "spin" foundation).
/// ONLY these wire strings may ever reach the emitter's `data-sirentide-role` attribute; the
/// {@link SirentideContract} validator + the ContainmentTest pin producer ⊆ this set, exactly like
/// the element/attribute allowlists.
///
/// ROLE ASSIGNMENT PER TYPE (as emitted): flowchart nodes → {@link #NODE}, edges → {@link #EDGE};
/// pie slices → {@link #SLICE}; state states → {@link #NODE}, transitions → {@link #EDGE} (it reuses
/// the flowchart engine); sequence actors → {@link #ACTOR}, messages → {@link #MESSAGE}; xychart bars
/// AND line/scatter points → {@link #BAR}; gantt tasks → {@link #BAR}; timeline events →
/// {@link #EVENT}; quadrant points → {@link #POINT}; class boxes → {@link #CLASS}, relations →
/// {@link #EDGE}; ER entities → {@link #ENTITY}, relations → {@link #EDGE}. mathblock has no discrete
/// elements, so it emits no anchor group. gitGraph commit dots → {@link #COMMIT}, branch lanes →
/// {@link #BRANCH} (its spine + name label); a branch/merge connector is decorative and un-anchored.
/// journey tasks → {@link #TASK} (each task's point disc + name + actor labels); the satisfaction line,
/// axes, and section-header brackets are decorative and un-anchored.
///
/// sequence NOTE boxes → {@link #NOTE} (the annotation-box role; a `create`/`destroy` adds no discrete
/// element — it only modifies the lifeline it names — so it emits no anchor group).
///
/// {@link #CLUSTER} (subgraph frames) and {@link #AXIS} (chart axes) stay RESERVED — those decorative
/// structures are not anchored yet; the allowlist already admits them for a future slice. Adding a
/// role here (not at a call site) is the single reviewed choke point that keeps the role vocabulary
/// closed.
public enum SirentideRole {
    NODE("node"),
    EDGE("edge"),
    SLICE("slice"),
    ACTOR("actor"),
    MESSAGE("message"),
    BAR("bar"),
    CLASS("class"),
    POINT("point"),
    // -- timeline event + ER entity: distinct-per-type roles added in the per-type widening slice, so a
    // narrator can say "the CUSTOMER entity" / "the Launch event" rather than collapsing them into the
    // graph-flavored node/class vocabulary. Kept as their own closed values (documented above).
    EVENT("event"),
    ENTITY("entity"),
    // -- sequence note-box annotation (added in the note + create/destroy enrichment slice) — its own
    // closed value so a narrator can say "the note over Alice, Bob" distinctly from the message/actor
    // vocabulary. A create/destroy modifies only its lifeline, so it adds no anchor.
    NOTE("note"),
    // -- gitGraph commit dot + branch lane (added in the gitGraph slice) — their own closed values so a
    // narrator can say "the fix commit" / "the develop branch" distinctly from the graph node/edge
    // vocabulary. A commit dot anchors as COMMIT; a branch's lane spine + name label anchor as BRANCH.
    COMMIT("commit"),
    BRANCH("branch"),
    // -- journey task point (added in the journey slice) — its own closed value so a narrator can say
    // "the Commute task, scored 3" distinctly from the chart bar/point vocabulary. Each task's point
    // disc + name label + actor labels anchor together as one TASK group.
    TASK("task"),
    // -- reserved (NOT emitted yet; admitted by the contract so a later slice is emitter-only) -------
    CLUSTER("cluster"),
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
