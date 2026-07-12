package com.sirentide.emit;

import com.sirentide.layout.Colors;

/// A per-frame EMPHASIS map for the play-through API (plan sirentide-play-through-frames): the first
/// CONSUMER of the semantic anchors. The `data-sirentide-seq` step-ordering already stamped on every
/// `<g>` is inert data until something reads it — this reads it. Given the frame's ACTIVE seq `k`,
/// {@link #state} classifies each group's seq into a {@link State}, and the emitter recolours that
/// group's leaf shapes accordingly. It is PURELY a fill/stroke/stroke-width recolour — no shape is
/// added, removed, or moved, so every frame shares the ONE layout's geometry byte-for-byte and differs
/// only in presentation (the "static slideshow you flip through", zero JS, same output alphabet).
///
/// The scheme, all IN-ALPHABET (the contract fill/stroke is opaque `#rrggbb` — NO alpha/opacity, so
/// de-emphasis is a lighter TINT, never a fade):
///   - {@link State#DONE} (a step ALREADY played, cumulative mode) → the group's NORMAL colours,
///     byte-identical to the un-emphasized bake — it reads as "done".
///   - {@link State#ACTIVE} (the step this frame plays) → the group keeps its fill, and its
///     stroke/label/arrowhead take the {@link #ACCENT} colour with a thicker stroke — it POPS.
///   - {@link State#FUTURE} (a step not yet reached) → every colour {@link Colors#lighten}ed toward
///     white ({@link #DIM_T}) so the group ghosts back without leaving the palette.
///
/// A `null` {@link Emphasis} threaded through the emitter is the NO-EMPHASIS path: every transform
/// below is the identity, so {@link com.sirentide.api.Sirentide#render}'s bake stays BYTE-IDENTICAL.
public final class Emphasis {

    /// The play-through accent — a strong warm hex OUTSIDE the item palette so an active step reads as
    /// distinctly "current", legible on a light or dark host page. Contract-clean `#rrggbb`.
    static final String ACCENT = "#e8590c";

    /// De-emphasis strength: how far a future group's colours blend toward white (0 = unchanged,
    /// 1 = pure white). 0.62 ghosts a mid-tone clearly while keeping it visible as context.
    static final double DIM_T = 0.62;

    /// The active step's stroke thickens by this factor (a line-drawn step — a sequence message arrow,
    /// a flowchart edge — reads as "the current hop" without any new geometry, just a heavier stroke).
    static final double ACTIVE_WIDTH_MULT = 2.0;

    /// The three roles a group's seq can play in a given frame.
    public enum State { DONE, ACTIVE, FUTURE }

    private final int activeSeq;
    private final boolean cumulative;

    private Emphasis(int activeSeq, boolean cumulative) {
        this.activeSeq = activeSeq;
        this.cumulative = cumulative;
    }

    /// CUMULATIVE emphasis (the play-through default — "playing forward"): steps BEFORE the active one
    /// are {@link State#DONE} (shown normal, as completed), the active step is {@link State#ACTIVE},
    /// and later steps are {@link State#FUTURE} (dimmed). A frame per seq walks the diagram building up.
    public static Emphasis cumulative(int activeSeq) {
        return new Emphasis(activeSeq, true);
    }

    /// SINGLE-STEP emphasis: ONLY the active step is shown normal+accented; every other step (earlier
    /// OR later) is {@link State#FUTURE}-dimmed. A spotlight that moves rather than a build-up.
    public static Emphasis singleStep(int activeSeq) {
        return new Emphasis(activeSeq, false);
    }

    /// Classify a group's `seq` for this frame: the active seq is ACTIVE; a lower seq is DONE in
    /// cumulative mode (else dimmed); a higher seq is always FUTURE (dimmed).
    State state(int seq) {
        if (seq == activeSeq) {
            return State.ACTIVE;
        }
        if (seq < activeSeq) {
            return cumulative ? State.DONE : State.FUTURE;
        }
        return State.FUTURE;
    }

    /// Recolour an ACCENTABLE fill/stroke sink (a line stroke, an arrowhead/label fill): the active
    /// step promotes to the {@link #ACCENT}, a future step dims, a done step (or the no-emphasis `null`
    /// state) is unchanged. `resolved` is the already-theme-resolved colour, so the returned value is
    /// still contract-clean. The `null` short-circuit is what keeps the normal bake byte-identical.
    static String accent(State st, String resolved) {
        if (st == null) {
            return resolved;
        }
        return switch (st) {
            case DONE -> resolved;
            case ACTIVE -> ACCENT;
            case FUTURE -> Colors.lighten(resolved, DIM_T);
        };
    }

    /// Recolour a STRUCTURAL fill (a box/actor-head rect, a pie/commit wedge): a future step dims, but
    /// an active or done step KEEPS its fill (a filled box's FILL has no in-alphabet accent — so its
    /// label, and its OPTIONAL `classDef` border stroke where present ({@link SvgEmitter#appendStroke},
    /// which runs {@link #accent}/{@link #strokeWidth} on it), carry the accent instead, not the fill).
    static String box(State st, String resolved) {
        if (st == null || st != State.FUTURE) {
            return resolved;
        }
        return Colors.lighten(resolved, DIM_T);
    }

    /// Scale a stroke width for the frame: the active step thickens, everything else is unchanged (so
    /// the no-emphasis path emits the exact original width — byte-identical).
    static double strokeWidth(State st, double w) {
        return st == State.ACTIVE ? w * ACTIVE_WIDTH_MULT : w;
    }
}
