package com.sirentide.font;

/// The glyph-emission tap (Marlow sirentide/712 HIGH 1). Coverage diagnostics must be derived
/// from the text that ACTUALLY reaches glyph emission — {@link FontMetrics#textPathD} is the
/// single funnel every layout's label text passes through on its way to baked outlines — never
/// from a parallel re-derivation of pre-layout IR. The re-derivation could not see layout-time
/// ellipsization (a truncated-away emoji still warned) or a per-run FragmentGuard degrade (a
/// math run baked as tofu without warning), and a sealed type switch over Diagram subtypes
/// could not catch a new label FIELD or a new transform inside an existing layout. Tapping the
/// emission seam makes the corpus ground truth by construction.
///
/// Mechanics: a ThreadLocal sink, ARMED only by the diagnostics twins around their
/// parse→layout→emit run and always DISARMED in a finally, so a plain {@code render} pays one
/// ThreadLocal read per text run and no allocation, and a leaked sink can never survive into an
/// unrelated render on the same thread. Layout is single-threaded (no parallel streams in the
/// pipeline), so the thread that arms is the thread that lays out.
public final class EmittedText {

    private static final ThreadLocal<StringBuilder> SINK = new ThreadLocal<>();

    private EmittedText() {}

    /// Called by {@link FontMetrics#textPathD} with each text run it turns into glyph outlines.
    /// No-op when no sink is armed (every non-diagnostics render).
    static void record(String text) {
        StringBuilder sink = SINK.get();
        if (sink != null && text != null && !text.isEmpty()) {
            sink.append(text).append('\n');
        }
    }

    /// Arm a fresh sink for this thread. Pair with {@link #disarm()} in a finally.
    public static void arm() {
        SINK.set(new StringBuilder());
    }

    /// The corpus collected so far on this thread ("" when nothing was armed or emitted).
    public static String collected() {
        StringBuilder sink = SINK.get();
        return sink == null ? "" : sink.toString();
    }

    /// Drop this thread's sink. Safe to call when nothing is armed.
    public static void disarm() {
        SINK.remove();
    }
}
