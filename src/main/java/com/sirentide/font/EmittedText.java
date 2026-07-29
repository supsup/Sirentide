package com.sirentide.font;

import java.util.ArrayDeque;
import java.util.Deque;

/// The glyph-emission tap (Marlow sirentide/712 HIGH 1; nesting-safe per 720/729; plain-render
/// scoping + no-retention per 733). Coverage diagnostics must be derived from the text that
/// ACTUALLY reaches glyph emission — {@link FontMetrics#textPathD} is the single funnel every
/// layout's label text passes through on its way to baked outlines — never from a parallel
/// re-derivation of pre-layout IR. The re-derivation could not see layout-time ellipsization
/// (a truncated-away emoji still warned) or a per-run FragmentGuard degrade (a math run baked
/// as tofu without warning), and a sealed type switch over Diagram subtypes could not catch a
/// new label FIELD or a new transform inside an existing layout. Tapping the emission seam
/// makes the corpus ground truth by construction.
///
/// Mechanics: a per-thread STACK of sinks. A {@code MathFragmentRenderer} is an application
/// callback with NO non-reentrancy restriction (Marlow sirentide/720): an inner render inside
/// the callback must neither erase the outer render's capture nor contaminate it. A nested
/// DIAGNOSTICS render pushes a fresh sink via {@link #arm()}; a nested PLAIN render pushes a
/// SUSPENSION frame via {@link #enterPlainRender()} (Marlow sirentide/733: only the render
/// actually executing contributes to a corpus, and a plain render contributes to NONE).
/// {@link #record} appends to the top sink only, ignoring a suspension frame.
///
/// NO RETENTION (733 LOW): the ThreadLocal is nullable — an unarmed emission reads null and
/// allocates nothing, a top-level plain render is a two-null-check fast path, and the deque is
/// created only when a capture/suspension scope is actually entered and removed when the last
/// scope exits. A render-only pooled thread never carries sink state between renders. Layout
/// itself is single-threaded (no parallel streams in the pipeline), so the thread that arms is
/// the thread that lays out.
public final class EmittedText {

    private static final ThreadLocal<Deque<StringBuilder>> SINKS = new ThreadLocal<>();

    /// Marker frame for a plain render running beneath an armed diagnostic frame: emissions
    /// while it is on top belong to NO corpus. Identity-compared; never appended to.
    private static final StringBuilder SUSPENDED = new StringBuilder(0);

    private EmittedText() {}

    /// Called by {@link FontMetrics#textPathD} with each text run it turns into glyph outlines.
    /// No-op when no sink is armed (every non-diagnostics render — zero allocation) and while a
    /// plain render is suspended beneath an armed frame. Appends to the INNERMOST armed frame
    /// only — the render actually executing is the one emitting these glyphs.
    static void record(String text) {
        Deque<StringBuilder> sinks = SINKS.get();
        if (sinks == null) {
            return;
        }
        StringBuilder sink = sinks.peek();
        if (sink != null && sink != SUSPENDED && text != null && !text.isEmpty()) {
            sink.append(text).append('\n');
        }
    }

    /// Arm a fresh sink frame for this thread (nested arms stack). Pair with {@link #disarm()}
    /// in a finally.
    public static void arm() {
        Deque<StringBuilder> sinks = SINKS.get();
        if (sinks == null) {
            sinks = new ArrayDeque<>();
            SINKS.set(sinks);
        }
        sinks.push(new StringBuilder());
    }

    /// The corpus collected by the INNERMOST armed frame ("" when nothing is armed or emitted).
    public static String collected() {
        Deque<StringBuilder> sinks = SINKS.get();
        StringBuilder sink = sinks == null ? null : sinks.peek();
        return sink == null || sink == SUSPENDED ? "" : sink.toString();
    }

    /// Pop this thread's innermost frame, restoring any outer capture. Safe when nothing is
    /// armed. The ThreadLocal itself is removed when the last frame pops, so no empty deque
    /// outlives the outermost render on pooled threads.
    public static void disarm() {
        Deque<StringBuilder> sinks = SINKS.get();
        if (sinks == null) {
            return;
        }
        sinks.poll();
        if (sinks.isEmpty()) {
            SINKS.remove();
        }
    }

    /// Enter a PLAIN (non-diagnostics) public render boundary (Marlow sirentide/733 HIGH).
    /// Beneath an armed diagnostic frame this pushes a suspension frame so the plain render's
    /// emissions reach no corpus; at top level it is a fast path that pushes and allocates
    /// nothing. Returns whether a frame was pushed — pass it to
    /// {@link #exitPlainRender(boolean)} in the caller's finally.
    public static boolean enterPlainRender() {
        Deque<StringBuilder> sinks = SINKS.get();
        if (sinks == null || sinks.isEmpty()) {
            return false;
        }
        sinks.push(SUSPENDED);
        return true;
    }

    /// Exit a plain render boundary. Pops the suspension frame iff {@link #enterPlainRender()}
    /// pushed one (the outer armed frame beneath it keeps the deque alive, so this never
    /// removes the ThreadLocal out from under an armed capture).
    public static void exitPlainRender(boolean suspended) {
        if (suspended) {
            disarm();
        }
    }

    /// Test probe (package-private): does THIS thread currently retain any sink state? The
    /// no-retention contract (Marlow sirentide/733 LOW) is that an unarmed emission and a
    /// completed top-level render leave nothing attached to a pooled thread.
    static boolean hasThreadState() {
        return SINKS.get() != null;
    }
}
