package com.sirentide.font;

import java.util.ArrayDeque;
import java.util.Deque;

/// The glyph-emission tap (Marlow sirentide/712 HIGH 1; nesting-safe per 720/729). Coverage
/// diagnostics must be derived from the text that ACTUALLY reaches glyph emission —
/// {@link FontMetrics#textPathD} is the single funnel every layout's label text passes through
/// on its way to baked outlines — never from a parallel re-derivation of pre-layout IR. The
/// re-derivation could not see layout-time ellipsization (a truncated-away emoji still warned)
/// or a per-run FragmentGuard degrade (a math run baked as tofu without warning), and a sealed
/// type switch over Diagram subtypes could not catch a new label FIELD or a new transform
/// inside an existing layout. Tapping the emission seam makes the corpus ground truth by
/// construction.
///
/// Mechanics: a per-thread STACK of sinks. A {@code MathFragmentRenderer} is an application
/// callback with NO non-reentrancy restriction (Marlow sirentide/720): an inner diagnostics
/// render inside the callback must neither erase the outer render's capture nor contaminate
/// it — so {@link #arm()} pushes a fresh sink, {@link #record} appends to the TOP sink only,
/// and {@link #disarm()} pops exactly one frame, restoring the outer capture in the caller's
/// finally. A plain {@code render} (no armed frame) pays one ThreadLocal read and no
/// allocation. Layout itself is single-threaded (no parallel streams in the pipeline), so the
/// thread that arms is the thread that lays out.
public final class EmittedText {

    private static final ThreadLocal<Deque<StringBuilder>> SINKS =
        ThreadLocal.withInitial(ArrayDeque::new);

    private EmittedText() {}

    /// Called by {@link FontMetrics#textPathD} with each text run it turns into glyph outlines.
    /// No-op when no sink is armed (every non-diagnostics render). Appends to the INNERMOST
    /// armed frame only — the render actually executing is the one emitting these glyphs.
    static void record(String text) {
        StringBuilder sink = SINKS.get().peek();
        if (sink != null && text != null && !text.isEmpty()) {
            sink.append(text).append('\n');
        }
    }

    /// Arm a fresh sink frame for this thread (nested arms stack). Pair with {@link #disarm()}
    /// in a finally.
    public static void arm() {
        SINKS.get().push(new StringBuilder());
    }

    /// The corpus collected by the INNERMOST armed frame ("" when nothing is armed or emitted).
    public static String collected() {
        StringBuilder sink = SINKS.get().peek();
        return sink == null ? "" : sink.toString();
    }

    /// Pop this thread's innermost frame, restoring any outer capture. Safe when nothing is
    /// armed. The ThreadLocal itself is removed when the last frame pops, so no empty deque
    /// outlives the outermost render on pooled threads.
    public static void disarm() {
        Deque<StringBuilder> sinks = SINKS.get();
        sinks.poll();
        if (sinks.isEmpty()) {
            SINKS.remove();
        }
    }
}
