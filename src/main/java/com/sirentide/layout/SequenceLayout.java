package com.sirentide.layout;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.contract.SirentideRole;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.Divider;
import com.sirentide.ir.SeqBlock;
import com.sirentide.ir.SeqLifecycle;
import com.sirentide.ir.SeqMessage;
import com.sirentide.ir.SeqNote;
import com.sirentide.ir.Sequence;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Pure sequence-diagram layout: actor heads across the top, a vertical lifeline dropping from each,
/// and one time-ordered message ROW per {@link SeqMessage} (docs/DESIGN.md §M1 — the founding
/// go/no-go demonstrator). Deliberately pure GRID ARITHMETIC — no graph solve — unlike
/// {@link FlowchartLayout}: actor i sits at a fixed column, message i at a fixed row.
///
/// Robustness is the point (DESIGN §6 — never throw, always terminate): an unknown-actor message is
/// defensively skipped, and non-finite geometry can never reach emit (guards + {@link #fmt}).
///
/// M1 scope: linear messages only. M2 adds ACTIVATION BARS (this file — implicit activation: a call
/// `->>` activates its callee, a reply `-->>` deactivates its sender, nested activations stack with an
/// x-offset). FOLLOW-UP (M3): alt/loop/par frames, and bottom actor boxes.
public final class SequenceLayout {

    private SequenceLayout() {}

    private static final double MARGIN = 24;
    private static final double ACTOR_H = 30;        // actor-head box height
    private static final double PAD_X = 14;          // horizontal padding inside a head box
    private static final double MIN_HEAD_W = 44;
    private static final double MAX_HEAD_LABEL_W = 140;  // head labels ellipsize past this
    private static final double ACTOR_GAP = 40;      // gap added to the widest head → column spacing
    private static final double MSG_TOP_PAD = 24;    // gap from the head bottom to the first message
    private static final double ROW_H = 34;          // vertical stride per message row
    private static final double SELF_W = 28;         // self-message hook width (out then back)
    private static final double SELF_ROWS = 1.5;     // a self-message consumes 1.5 rows
    private static final double LABEL_GAP = 6;        // gap between a self-hook's edge and its label
    private static final double LABEL_LIFT = 5;       // a message label sits this far ABOVE its line
    private static final double MIN_W = 120;          // minimal blank-canvas width (0 actors)
    private static final double MIN_H = 60;

    private static final FontMetrics FONT = FontMetrics.bundled();
    private static final double LABEL_SIZE = 12;      // head-label size
    private static final double MSG_LABEL_SIZE = 11;  // message-label size
    // Hard message-label width cap (robustness plan fe8c5bbc #3): the label ellipsized to the SPAN
    // between the two actors, so a message across DISTANT actors (a wide span, up to ~10000px) admitted
    // a full 512-char label — a giant, unreadable run. Cap the ellipsize width at min(span-8, this),
    // matching the head-label discipline; a long label now truncates even on a wide span.
    private static final double MAX_MSG_LABEL_W = 220;

    private static final String HEAD_FILL = "#dbe4ff";
    private static final String LIFELINE_STROKE = "#cbd5e1";
    private static final double LIFELINE_WIDTH = 1;
    private static final String CALL_STROKE = "#64748b";    // a call `->>` — solid, filled triangle
    private static final String REPLY_STROKE = "#94a3b8";   // a return `-->>` — lighter, open V
    private static final double MSG_WIDTH = 1.5;
    private static final double ARROW_LEN = 10;       // arrowhead length back from the dst anchor
    private static final double ARROW_HALF_W = 4;     // filled-triangle half-width (perpendicular)
    private static final double V_DX = 8.19;          // open-V back-offset ≈ ARROW_LEN·cos35°
    private static final double V_DY = 5.74;          // open-V half-spread ≈ ARROW_LEN·sin35°
    private static final double EDGE_PAD = 2;         // labels are clamped inside [EDGE_PAD, W-EDGE_PAD]

    // -- activation bars (M2). A thin rect on the actor's lifeline while a call is active; nested
    // (concurrent) activations of the SAME actor step ACT_OFFSET right so overlapping bars stay
    // visible. ACT_FILL is a subtle "active" tint that reads over the light lifeline without dominating.
    private static final double ACT_W = 8;            // activation-bar width
    private static final double ACT_OFFSET = 4;       // each nesting depth steps this far right
    private static final String ACT_FILL = "#c7d2fe"; // subtle soft-indigo "active" fill

    // -- alt/loop/par FRAME blocks (M2). A stroke-only rectangle (four Lines — Rect carries fill only,
    // and a stroke-only frame reads cleaner than a tint that stacks when nested) around a run of
    // messages, with a filled corner TAB holding the kind (`alt`/`loop`/`par`) + a label, and dashed
    // DIVIDER lines at each `else`/`and`. Emitted UNDER the lifelines/messages/activations so a frame
    // never occludes the content it wraps. Nested frames inset by depth·FRAME_INSET.
    private static final String FRAME_STROKE = "#b8c0cc";  // subtle frame border (unique — test-keyed)
    private static final String TAB_FILL = "#e7ecff";      // pale-indigo label-tab background
    private static final double FRAME_STROKE_W = 1;
    private static final double FRAME_PAD_X = 12;     // horizontal padding beyond the outer lifelines
    private static final double FRAME_INSET = 6;      // each nesting depth tightens the frame this far
    private static final double FRAME_TOP_PAD = 24;   // space above the first message (holds the tab
                                                      // clear of the first message's own label band)
    private static final double FRAME_BOTTOM_PAD = 12; // space below the last message
    private static final double TAB_H = 15;           // label-tab height
    private static final double TAB_PAD = 5;          // horizontal padding inside the tab
    private static final double TAB_SIZE = 10;        // kind/label glyph size
    private static final double DIV_LIFT = 15;        // a divider sits this far above its branch's msg
    private static final double DASH = 5;             // dashed-divider dash length (Line has no dash)
    private static final double DASH_GAP = 4;         // dashed-divider gap length

    // -- NOTE boxes (M2 enrichment). A bordered box (a filled Rect background + four border Lines +
    // centred glyph text) pinned at the current time position: `over A` centred on A's lifeline, `over
    // A,B` spanning A..B, `left of`/`right of` to the side. The box injects its own vertical BAND
    // between the surrounding messages so it never overlaps them. The fill is a soft sticky-note tint;
    // the text contrast-fills against it so it reads on any theme (same discipline as the head labels).
    private static final String NOTE_FILL = "#fff8c5";    // soft sticky-note yellow (unique — test-keyed)
    private static final String NOTE_STROKE = "#d4a72c";  // amber note border
    private static final double NOTE_STROKE_W = 1;
    private static final double NOTE_SIZE = 11;        // note-text glyph size
    private static final double NOTE_PAD_X = 8;        // horizontal padding inside the box
    private static final double NOTE_H = 22;           // note-box height (single text line)
    private static final double NOTE_MARGIN = 8;       // vertical gap above AND below the box in its band
    private static final double NOTE_BAND = NOTE_H + 2 * NOTE_MARGIN;   // the injected row height
    private static final double MIN_NOTE_W = 40;       // minimum note-box width
    private static final double MAX_NOTE_W = 220;      // note text ellipsizes past this content width
    private static final double NOTE_SIDE_GAP = 12;    // gap between a lifeline and a left/right note
    private static final double NOTE_SPAN_PAD = 10;    // an `over A,B` note extends this far past A..B

    // -- CREATE / DESTROY (M2 enrichment). A created actor's head + lifeline start mid-diagram (an
    // injected band of ACTOR_H + MSG_TOP_PAD holds the head above the message that first reaches it); a
    // destroyed actor's lifeline ENDS at the destroy y with an `X` mark (two short crossed lines).
    private static final String DESTROY_STROKE = "#64748b";   // the X mark (matches the call stroke)
    private static final double DESTROY_X_HALF = 5;           // half-length of each X stroke

    /// The visible-degrade message for a non-empty body that parsed to ZERO actors (every line
    /// malformed) — drawn as a glyph run so a mistyped sequence never renders as silent nothing.
    private static final String DEGRADE_MSG = "sequence: no messages parsed";

    public static LaidOut layout(Sequence seq) {
        return layout(seq, null);
    }

    /// Inline-math entry (plan sirentide-math-in-all-label-types): a `$…$` run in a MESSAGE label
    /// (`$O(n)$`, `$\Delta t$`) or an ACTOR-head label bakes through the shared {@link MathLabel}
    /// seam. A null `math` degrades every `$…$` to plain text — byte-identical to
    /// {@link #layout(Sequence)}.
    public static LaidOut layout(Sequence seq, MathFragmentRenderer math) {
        List<String> actors = seq.actors();
        int n = actors.size();
        if (n == 0) {
            // A bare `sequence` header (no body) is an INTENTIONAL empty diagram → keep the minimal
            // blank canvas. But a NON-EMPTY body that parsed to zero actors is an ERROR (every line
            // malformed) → degrade VISIBLY with a small message canvas, never a silent blank shell.
            if (!seq.bodyHadContent()) {
                return LaidOut.of(MIN_W, MIN_H);
            }
            return degradeNoMessages(seq.textColor());
        }

        String textColor = seq.textColor();
        // Actor-head box fill: the header `nodecolor=#hex` default, else the built-in HEAD_FILL. The
        // head LABEL contrasts against THIS fill (dark on a light head, white on a dark one) so it
        // stays legible on any theme — message labels below keep textColor (they sit on the page bg).
        String headFill = seq.nodeColor() != null ? seq.nodeColor() : HEAD_FILL;
        String headLabelFill = Colors.contrastFill(headFill);

        // -- head boxes: ellipsized label → width; the widest sets a uniform column spacing so
        // lifelines are evenly placed (a mermaid actor row). id → column index for message routing.
        Map<String, Integer> index = new HashMap<>();
        String[] headLabels = new String[n];
        double[] headW = new double[n];
        // Composite measure for a head whose name carries `$…$` AND a renderer was provided; null for
        // every plain-text head, which keeps the existing ellipsize path (byte-identical) below.
        MathLabel.Measured[] headMeasures = new MathLabel.Measured[n];
        double maxHeadW = MIN_HEAD_W;
        for (int i = 0; i < n; i++) {
            index.put(actors.get(i), i);
            String raw = actors.get(i);
            if (math != null && MathLabel.hasMath(raw)) {
                // A math head name SKIPS ellipsization (a formula must not be cut mid-run); size the
                // head box on the composite width.
                MathLabel.Measured m = MathLabel.measure(raw, LABEL_SIZE, FONT, math);
                headMeasures[i] = m;
                headLabels[i] = raw;
                headW[i] = Math.max(MIN_HEAD_W, m.width() + 2 * PAD_X);
            } else {
                String label = FONT.ellipsize(raw, MAX_HEAD_LABEL_W, LABEL_SIZE);
                headLabels[i] = label;
                headW[i] = Math.max(MIN_HEAD_W, FONT.runWidth(label, LABEL_SIZE) + 2 * PAD_X);
            }
            maxHeadW = Math.max(maxHeadW, headW[i]);
        }
        double colSpacing = maxHeadW + ACTOR_GAP;
        // lifeline centre x per actor (evenly spaced, first head left-aligned to the margin).
        double[] cx = new double[n];
        for (int i = 0; i < n; i++) {
            cx[i] = MARGIN + maxHeadW / 2 + i * colSpacing;
        }
        double headBottom = MARGIN + ACTOR_H;

        // -- CREATE / DESTROY resolution (M2 enrichment): the FIRST create / destroy per actor wins
        // (a later duplicate is inert). `createAt[i]`/`destroyAt[i]` hold the anchoring message index,
        // or -1 when the actor is not created / destroyed. Empty lists → all -1 → the legacy path
        // (head at top, lifeline head-bottom→content-bottom), byte-identical.
        int[] createAt = new int[n];
        int[] destroyAt = new int[n];
        Arrays.fill(createAt, -1);
        Arrays.fill(destroyAt, -1);
        for (SeqLifecycle lc : seq.lifecycles()) {
            Integer ai = index.get(lc.actor());
            if (ai == null) {
                continue;
            }
            if (lc.create() && createAt[ai] < 0) {
                createAt[ai] = lc.atMsg();
            } else if (!lc.create() && destroyAt[ai] < 0) {
                destroyAt[ai] = lc.atMsg();
            }
        }

        // -- head/lifeline start y per actor. A NORMAL actor's head sits at the top (MARGIN) and its
        // lifeline starts at headBottom. A CREATED actor's head + lifeline start mid-diagram — set
        // during the row walk when its create boundary is reached.
        double[] headTop = new double[n];
        double[] lifeTop = new double[n];
        Arrays.fill(headTop, MARGIN);
        Arrays.fill(lifeTop, headBottom);

        // -- assign each message a row y (self-messages consume 1.5 rows). Unknown-actor messages
        // are skipped defensively (the parser guards this, but layout must tolerate a stray ref).
        List<SeqMessage> drawn = new ArrayList<>();
        double[] msgY = new double[seq.messages().size()];
        // Blocks (M2) reference the FLAT message list by original index, but a skipped (unknown-actor)
        // message shifts the drawn positions — so keep a parallel index→y map (NaN = skipped/not
        // drawn) and index→bottom-extent (a self-message's hook drops ROW_H/2 below its row) so a
        // frame can be pinned to the real geometry of the messages it wraps, not to a brittle constant.
        int msgCount = seq.messages().size();
        double[] yByMsg = new double[msgCount];
        double[] bottomByMsg = new double[msgCount];
        Arrays.fill(yByMsg, Double.NaN);
        Arrays.fill(bottomByMsg, Double.NaN);
        // Walk the message boundaries K = 0..msgCount, accumulating an ABSOLUTE y cursor. Before each
        // message we INJECT any note bands + created-head bands anchored at that boundary (atMsg == K),
        // growing the diagram so the annotation never overlaps its neighbours. With no notes/creates
        // every injected band is zero → the cursor advances by exactly ROW_H (or SELF_ROWS·ROW_H) per
        // drawn message, byte-identical to the pre-enrichment `headBottom + MSG_TOP_PAD + rowCursor·ROW_H`.
        List<NoteBox> noteBoxes = new ArrayList<>();
        double cursorY = headBottom + MSG_TOP_PAD;
        for (int K = 0; K <= msgCount; K++) {
            // Notes anchored at this boundary (declaration order): each injects its own vertical band.
            for (SeqNote note : seq.notes()) {
                if (note.atMsg() != K) {
                    continue;
                }
                NoteBox nb = layoutNote(note, cursorY + NOTE_MARGIN, cx, index);
                if (nb != null) {
                    noteBoxes.add(nb);
                    cursorY += NOTE_BAND;   // the note consumes its band; the next message drops below it
                }
            }
            // Created heads anchored at this boundary: one shared band (ACTOR_H head + MSG_TOP_PAD gap)
            // holds every actor created here, placed above the message that first reaches it.
            boolean anyCreate = false;
            for (int i = 0; i < n; i++) {
                if (createAt[i] == K) {
                    anyCreate = true;
                }
            }
            if (anyCreate) {
                for (int i = 0; i < n; i++) {
                    if (createAt[i] == K) {
                        headTop[i] = cursorY;
                        lifeTop[i] = cursorY + ACTOR_H;
                    }
                }
                cursorY += ACTOR_H + MSG_TOP_PAD;
            }
            if (K < msgCount) {
                SeqMessage m = seq.messages().get(K);
                Integer a = index.get(m.from());
                Integer b = index.get(m.to());
                if (a == null || b == null) {
                    continue;
                }
                msgY[drawn.size()] = cursorY;
                yByMsg[K] = cursorY;
                boolean self = a.equals(b);
                bottomByMsg[K] = self ? cursorY + ROW_H / 2 : cursorY;   // a self-hook drops ROW_H/2 below
                drawn.add(m);
                cursorY += self ? SELF_ROWS * ROW_H : ROW_H;
            }
        }
        double contentBottom = cursorY;

        // -- lifeline END y per actor: the diagram bottom by default, or the destroy y (the row of the
        // message the `destroy` precedes; the diagram bottom when that message is out of range / not
        // drawn — a `destroy` at end-of-input). A `destroy` above the lifeline start is degenerate and
        // draws no lifeline (guarded at emit).
        double[] endY = new double[n];
        Arrays.fill(endY, contentBottom);
        for (int i = 0; i < n; i++) {
            if (destroyAt[i] >= 0) {
                int dk = destroyAt[i];
                endY[i] = (dk < msgCount && !Double.isNaN(yByMsg[dk])) ? yByMsg[dk] : contentBottom;
            }
        }

        // -- alt/loop/par frame geometry (M2). Resolve each block's message index range to a pixel
        // rectangle + tab + dividers. Blocks with no drawn message in range are degenerate → skipped.
        List<Frame> frames = buildFrames(seq.blocks(), yByMsg, bottomByMsg, cx, index, seq.messages());

        // -- activation bars (M2, IMPLICIT activation — the low-friction default). Walk the drawn
        // messages in time order maintaining a per-actor STACK of open activations:
        //   • a CALL `->>` (reply=false) ACTIVATES its CALLEE (the `to`) — push an activation onto
        //     that actor's stack, starting at the message y. A self-call `A ->> A` activates A too
        //     (Mermaid semantics: the callee is A — no special case, `to` already equals `from`).
        //   • a REPLY `-->>` (reply=true) DEACTIVATES its SENDER (the `from`) — pop that actor's
        //     MOST-RECENT open activation (LIFO), ending its bar at the reply y.
        // Nested (concurrent) activations of the same actor carry a `depth` = the open count at push
        // time, so each steps ACT_OFFSET to the right and overlapping bars stay visible. Robustness
        // (DESIGN §6 — malformed→inert, never throw): an UNBALANCED reply (pop on an empty stack) is
        // ignored (nothing to close); an UNBALANCED call (no matching reply) keeps its default endY =
        // contentBottom, so its bar closes cleanly at the diagram bottom. Arrows still connect to the
        // lifeline CENTRE (v1 — the bar overlays the centred arrow; edge-coupling is a follow-up).
        List<Activation> activations = new ArrayList<>();
        List<Deque<Activation>> open = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            open.add(new ArrayDeque<>());
        }
        for (int i = 0; i < drawn.size(); i++) {
            SeqMessage m = drawn.get(i);
            double y = msgY[i];
            if (!m.reply()) {
                int actor = index.get(m.to());        // a call activates the callee
                Deque<Activation> st = open.get(actor);
                Activation act = new Activation(actor, y, contentBottom, st.size());
                activations.add(act);
                st.push(act);
            } else {
                int actor = index.get(m.from());       // a reply deactivates the sender
                Deque<Activation> st = open.get(actor);
                if (!st.isEmpty()) {
                    st.pop().endY = y;                 // close the most-recent open activation (LIFO)
                }
                // else: an unbalanced reply — no open activation to close, ignore (never throw)
            }
        }
        // Still-open activations (unbalanced calls) keep their default endY = contentBottom.

        // -- canvas width: base = last lifeline + half a head + margin. Then WIDEN for any trailing
        // actor's self-message label (its hook + left-aligned label reach right of the lifeline).
        double baseW = cx[n - 1] + maxHeadW / 2 + MARGIN;
        double canvasW = baseW;
        for (int i = 0; i < drawn.size(); i++) {
            SeqMessage m = drawn.get(i);
            int a = index.get(m.from());
            if (a == index.get(m.to())) {
                double labelW = selfLabelWidth(m.label(), math);
                double need = cx[a] + SELF_W + LABEL_GAP + labelW + MARGIN;
                canvasW = Math.max(canvasW, need);
            }
        }
        // Widen for any activation bar that offsets RIGHT past the base width (a deeply-nested bar on
        // the trailing actor) so the containment invariant holds — no rect x+width escapes the canvas.
        for (Activation act : activations) {
            double right = cx[act.actor] - ACT_W / 2 + act.depth * ACT_OFFSET + ACT_W;
            canvasW = Math.max(canvasW, right + MARGIN);
        }
        // Widen for any frame whose right edge (or its tab) reaches past the base width, so the
        // containment invariant holds (no frame line/tab escapes the canvas). Grow the height too for
        // a frame whose bottom pad drops below the content bottom (rare, but keep it inside).
        double canvasH = contentBottom + MARGIN;
        for (Frame f : frames) {
            canvasW = Math.max(canvasW, Math.max(f.right, f.left + f.tabW) + MARGIN);
            canvasH = Math.max(canvasH, f.bottom + MARGIN);
        }
        // Widen for any note box whose right edge reaches past the base width (a `right of` note on the
        // trailing actor, or a wide `over A,B` span) so the containment invariant holds — no note rect
        // x+width escapes the canvas.
        for (NoteBox nb : noteBoxes) {
            canvasW = Math.max(canvasW, nb.left + nb.width + MARGIN);
        }

        // -- emit order (readability + the containment audit): frames UNDER everything, then lifelines,
        // then message lines + arrowheads + labels, then head boxes, then head labels on top.
        List<Shape> shapes = new ArrayList<>();

        // 0) alt/loop/par frames FIRST — the block border + label tab + dividers draw BEHIND the
        // lifelines, messages and activation bars so a frame never occludes the content it wraps.
        for (Frame f : frames) {
            emitFrame(shapes, f, textColor, canvasW);
        }

        // 1) lifelines: a light vertical line from each actor's start y (head bottom — the top for a
        // normal actor, the mid-diagram create point for a created one) to its end y (the diagram
        // bottom, or the destroy y). A degenerate span (destroy at/above the start) draws no lifeline.
        for (int i = 0; i < n; i++) {
            if (endY[i] > lifeTop[i]) {
                shapes.add(new Line(cx[i], lifeTop[i], cx[i], endY[i], LIFELINE_STROKE, LIFELINE_WIDTH));
            }
        }
        // 1.1) destroy X marks: two short crossed lines centred on a destroyed actor's lifeline at its
        // end y (the `X` that reads "this participant is gone"). Emitted after the lifelines so the mark
        // sits on top of the line it terminates. create/destroy add no anchor group (they only modify
        // the lifeline) — the X is emitted bare, like the lifelines.
        for (int i = 0; i < n; i++) {
            if (destroyAt[i] >= 0 && Double.isFinite(endY[i])) {
                double ex = cx[i];
                double ey = endY[i];
                shapes.add(new Line(ex - DESTROY_X_HALF, ey - DESTROY_X_HALF,
                    ex + DESTROY_X_HALF, ey + DESTROY_X_HALF, DESTROY_STROKE, MSG_WIDTH));
                shapes.add(new Line(ex - DESTROY_X_HALF, ey + DESTROY_X_HALF,
                    ex + DESTROY_X_HALF, ey - DESTROY_X_HALF, DESTROY_STROKE, MSG_WIDTH));
            }
        }

        // 1.5) activation bars: a thin ACT_FILL rect per activation, centred on its actor's lifeline
        // (nested bars stepped right by ACT_OFFSET·depth). Emitted AFTER the lifelines (the bar
        // overlays the line) and BEFORE the messages (arrows/heads draw on top). A degenerate span
        // (zero/negative/non-finite height) is skipped — never emit inverted geometry (DESIGN §6).
        for (Activation act : activations) {
            double h = act.endY - act.startY;
            if (!Double.isFinite(h) || h <= 0) {
                continue;
            }
            double bx = cx[act.actor] - ACT_W / 2 + act.depth * ACT_OFFSET;
            shapes.add(new Rect(bx, act.startY, ACT_W, h, ACT_FILL));
        }

        // Per-diagram anchor factory (plan sirentide-semantic-anchor-g): each message → one
        // `<g role="message">`, each actor → one `<g role="actor">`. Messages emit first, so seq runs
        // 0..drawn-1 over messages then drawn..drawn+n-1 over actors — the deterministic emit-order
        // index. Grouping is additive: the wrapped shapes keep their exact geometry.
        AnchorAssigner assigner = new AnchorAssigner();

        // 2) messages — each drawn message's shapes collect into ONE `<g role="message">` (id = the
        // from-to actor pair, uniquified for parallel messages). A degenerate (coincident-lifeline)
        // message emits an empty group, keeping seq aligned with the drawn-message set (as in flowchart).
        for (int i = 0; i < drawn.size(); i++) {
            SeqMessage m = drawn.get(i);
            int a = index.get(m.from());
            int b = index.get(m.to());
            double y = msgY[i];
            String stroke = m.reply() ? REPLY_STROKE : CALL_STROKE;
            List<Shape> mg = new ArrayList<>();
            if (a == b) {
                emitSelfMessage(mg, cx[a], y, m, stroke, textColor, canvasW, math);
            } else {
                emitMessage(mg, cx[a], cx[b], y, m, stroke, textColor, canvasW, math);
            }
            shapes.add(new Group(assigner.assign(SirentideRole.MESSAGE, m.from() + "-" + m.to()), mg));
        }

        // 3) actor heads — one `<g role="actor">` per actor wrapping its head box (rect, centred on the
        // lifeline) + its centred label (glyph paths, never <text>, contrast-filled against the head
        // box). Heads occupy disjoint columns and a label never leaves its own box, so folding the box
        // and label into one group per actor introduces no cross-actor z-order change (geometry
        // byte-identical, visually identical) — the same box+label fold the flowchart nodes use.
        for (int i = 0; i < n; i++) {
            List<Shape> ag = new ArrayList<>();
            ag.add(new Rect(cx[i] - headW[i] / 2, headTop[i], headW[i], ACTOR_H, headFill));
            double baseline = headTop[i] + ACTOR_H / 2 + LABEL_SIZE * 0.35;
            if (headMeasures[i] != null) {
                MathLabel.emit(headMeasures[i], cx[i] - headMeasures[i].width() / 2, baseline,
                    headLabelFill, LABEL_SIZE, FONT, ag);
            } else {
                double w = FONT.runWidth(headLabels[i], LABEL_SIZE);
                String d = FONT.textPathD(headLabels[i], cx[i] - w / 2, baseline, LABEL_SIZE);
                if (!d.isBlank()) {
                    ag.add(new GlyphRun(d, headLabelFill));
                }
            }
            shapes.add(new Group(assigner.assign(SirentideRole.ACTOR, actors.get(i)), ag));
        }

        // 4) notes — each note box is ONE `<g role="note">` (a filled Rect + four border Lines +
        // centred glyph text), emitted LAST so it draws on top of the lifelines it annotates. Its id is
        // the sanitized note text, its seq the emit-order index after the messages + actors — so a
        // note-free sequence never mints a note anchor (the message/actor seq range is unchanged).
        for (NoteBox nb : noteBoxes) {
            emitNote(shapes, nb, canvasW, assigner);
        }

        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// A horizontal message between two DISTINCT lifelines: a stroked line + a dst arrowhead (a
    /// FILLED triangle for a call, an OPEN V — two short lines — for a reply), plus a centred label
    /// above the line, ellipsized to the span and clamped inside the canvas.
    private static void emitMessage(List<Shape> shapes, double xa, double xb, double y, SeqMessage m,
                                    String stroke, String textColor, double canvasW,
                                    MathFragmentRenderer math) {
        double dx = xb - xa;
        double span = Math.abs(dx);
        if (!Double.isFinite(span) || span < 1e-6) {
            return;   // degenerate (coincident lifelines) — skip (never emit NaN geometry)
        }
        double ux = dx > 0 ? 1 : -1;   // horizontal unit vector toward the dst
        if (m.reply()) {
            // Reply: the full line reaches the dst, then an OPEN V (two short lines) points back.
            shapes.add(new Line(xa, y, xb, y, stroke, MSG_WIDTH));
            shapes.add(new Line(xb, y, xb - V_DX * ux, y - V_DY, stroke, MSG_WIDTH));
            shapes.add(new Line(xb, y, xb - V_DX * ux, y + V_DY, stroke, MSG_WIDTH));
        } else {
            // Call: the line stops at the arrow base so it doesn't overshoot the filled triangle.
            double baseX = xb - ARROW_LEN * ux;
            shapes.add(new Line(xa, y, baseX, y, stroke, MSG_WIDTH));
            String d = "M " + fmt(xb) + " " + fmt(y)
                + " L " + fmt(baseX) + " " + fmt(y - ARROW_HALF_W)
                + " L " + fmt(baseX) + " " + fmt(y + ARROW_HALF_W)
                + " Z";
            shapes.add(new Path(d, stroke));
        }
        // Label: centred over the span, above the line, ellipsized to the span width, clamped so the
        // run never crosses the canvas edge (the geometry-escape fix class). A `$…$` message label
        // (`$O(n)$`, `$\Delta t$`) bakes through the shared MathLabel seam; a math label SKIPS the
        // span-ellipsize (a formula must not be cut mid-run) and is centred on its composite width.
        if (m.label() != null) {
            double midX = (xa + xb) / 2;
            double cap = Math.min(span - 8, MAX_MSG_LABEL_W);
            MathLabel.Measured mm = (math != null && MathLabel.hasMath(m.label()))
                ? MathLabel.measure(m.label(), MSG_LABEL_SIZE, FONT, math) : null;
            if (mm != null && mm.width() <= cap) {
                // In-cap math: render the composite centred (byte-identical to the pre-cap math path).
                double runX = clamp(midX - mm.width() / 2, mm.width(), canvasW);
                MathLabel.emit(mm, runX, y - LABEL_LIFT, textColor, MSG_LABEL_SIZE, FONT, shapes);
            } else {
                // Plain label, OR an OVER-WIDE math composite (fe8c5bbc #3, Fix sirentide/256): a
                // formula can't be ellipsized mid-run, so an over-wide one degrades WHOLE to its raw
                // source ellipsized to the cap — a visible truncation that bounds the run width,
                // never a span-independent thousands-of-px composite.
                String text = FONT.ellipsize(m.label(), cap, MSG_LABEL_SIZE);
                double w = FONT.runWidth(text, MSG_LABEL_SIZE);
                double runX = clamp(midX - w / 2, w, canvasW);
                String d = FONT.textPathD(text, runX, y - LABEL_LIFT, MSG_LABEL_SIZE);
                if (!d.isBlank()) {
                    shapes.add(new GlyphRun(d, textColor));
                }
            }
        }
    }

    /// A self-message `A ->> A`: a three-segment hook off the lifeline — out right SELF_W, down
    /// ROW_H/2, back left into the lifeline with a LEFT-pointing arrowhead (filled triangle for a
    /// call, open V for a reply) — plus a left-aligned label just right of the hook's outer edge.
    private static void emitSelfMessage(List<Shape> shapes, double x, double y, SeqMessage m,
                                        String stroke, String textColor, double canvasW,
                                        MathFragmentRenderer math) {
        double outX = x + SELF_W;
        double yb = y + ROW_H / 2;
        shapes.add(new Line(x, y, outX, y, stroke, MSG_WIDTH));            // out right
        shapes.add(new Line(outX, y, outX, yb, stroke, MSG_WIDTH));        // down
        if (m.reply()) {
            shapes.add(new Line(outX, yb, x, yb, stroke, MSG_WIDTH));      // back left (full, open V)
            shapes.add(new Line(x, yb, x + V_DX, yb - V_DY, stroke, MSG_WIDTH));
            shapes.add(new Line(x, yb, x + V_DX, yb + V_DY, stroke, MSG_WIDTH));
        } else {
            double baseX = x + ARROW_LEN;   // line stops at the arrow base (left-pointing triangle)
            shapes.add(new Line(outX, yb, baseX, yb, stroke, MSG_WIDTH));
            String d = "M " + fmt(x) + " " + fmt(yb)
                + " L " + fmt(baseX) + " " + fmt(yb - ARROW_HALF_W)
                + " L " + fmt(baseX) + " " + fmt(yb + ARROW_HALF_W)
                + " Z";
            shapes.add(new Path(d, stroke));
        }
        // Label: left-aligned just right of the hook's outer edge, aligned on the top out-segment,
        // clamped in-canvas (the canvas was widened above so a trailing actor's label fits). A `$…$`
        // self-message label bakes through the shared MathLabel seam (math skips the ellipsize).
        if (m.label() != null) {
            MathLabel.Measured mm = (math != null && MathLabel.hasMath(m.label()))
                ? MathLabel.measure(m.label(), MSG_LABEL_SIZE, FONT, math) : null;
            if (mm != null && mm.width() <= MAX_HEAD_LABEL_W) {
                double runX = clamp(outX + LABEL_GAP, mm.width(), canvasW);
                MathLabel.emit(mm, runX, y + MSG_LABEL_SIZE * 0.35, textColor, MSG_LABEL_SIZE, FONT, shapes);
            } else {
                // Over-wide math degrades WHOLE to the raw source ellipsized to the cap (fe8c5bbc #3,
                // Fix sirentide/256) — same bound the plain self-message label already uses.
                String text = FONT.ellipsize(m.label(), MAX_HEAD_LABEL_W, MSG_LABEL_SIZE);
                double w = FONT.runWidth(text, MSG_LABEL_SIZE);
                double runX = clamp(outX + LABEL_GAP, w, canvasW);
                String d = FONT.textPathD(text, runX, y + MSG_LABEL_SIZE * 0.35, MSG_LABEL_SIZE);
                if (!d.isBlank()) {
                    shapes.add(new GlyphRun(d, textColor));
                }
            }
        }
    }

    /// The width a self-message label reserves to the right of its hook — used to widen the canvas so
    /// a trailing actor's label fits. Math-aware: a `$…$` label measures its composite width, a
    /// plain label its ellipsized width (byte-identical to the legacy calc).
    private static double selfLabelWidth(String raw, MathFragmentRenderer math) {
        if (raw == null) {
            return 0;
        }
        if (math != null && MathLabel.hasMath(raw)) {
            double w = MathLabel.measure(raw, MSG_LABEL_SIZE, FONT, math).width();
            if (w <= MAX_HEAD_LABEL_W) {
                return w;   // in-cap math reserves its true composite width
            }
            // Over-wide math degrades to the ellipsized raw source at EMIT (fe8c5bbc #3, Fix
            // sirentide/256); reserve the SAME bounded width here so the canvas isn't blown by the
            // uncapped composite it will never draw.
        }
        return FONT.runWidth(FONT.ellipsize(raw, MAX_HEAD_LABEL_W, MSG_LABEL_SIZE), MSG_LABEL_SIZE);
    }

    /// A resolved note box: its top-left corner + size, the (already ellipsized) display text, and the
    /// RAW note text (the anchor-id source). Layout-internal — {@link #emitNote} turns it into a filled
    /// Rect + four border Lines + a centred glyph run inside one `<g role="note">`.
    private static final class NoteBox {
        final double left;
        final double top;
        final double width;
        final double height;
        final String display;
        final String rawText;

        NoteBox(double left, double top, double width, double height, String display, String rawText) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.display = display;
            this.rawText = rawText;
        }
    }

    /// Resolves a {@link SeqNote} to a {@link NoteBox} at the given box-top y, or null when it does not
    /// resolve (an actor unknown to the layout index — the parser already guards this, but layout
    /// tolerates a stray ref). `over` centres on one lifeline or spans two; `left`/`right` place the box
    /// beside a single lifeline. The text ellipsizes to MAX_NOTE_W and the box grows to fit (down to
    /// MIN_NOTE_W). The left edge is clamped ≥ EDGE_PAD so a side note never escapes off the left of the
    /// canvas (the containment invariant).
    private static NoteBox layoutNote(SeqNote note, double top, double[] cx, Map<String, Integer> index) {
        List<Integer> idxs = new ArrayList<>();
        for (String a : note.actors()) {
            Integer i = index.get(a);
            if (i == null) {
                return null;
            }
            idxs.add(i);
        }
        if (idxs.isEmpty() || note.text() == null) {
            return null;
        }
        String display = FONT.ellipsize(note.text(), MAX_NOTE_W, NOTE_SIZE);
        double textW = FONT.runWidth(display, NOTE_SIZE);
        double contentW = textW + 2 * NOTE_PAD_X;
        double left;
        double width;
        switch (note.position()) {
            case "over" -> {
                if (idxs.size() >= 2) {
                    double spanL = Math.min(cx[idxs.get(0)], cx[idxs.get(1)]);
                    double spanR = Math.max(cx[idxs.get(0)], cx[idxs.get(1)]);
                    left = spanL - NOTE_SPAN_PAD;
                    width = (spanR + NOTE_SPAN_PAD) - left;
                    if (width < contentW) {
                        left -= (contentW - width) / 2;   // grow symmetrically to fit the text
                        width = contentW;
                    }
                } else {
                    width = Math.max(MIN_NOTE_W, contentW);
                    left = cx[idxs.get(0)] - width / 2;
                }
            }
            case "left" -> {
                width = Math.max(MIN_NOTE_W, contentW);
                left = cx[idxs.get(0)] - NOTE_SIDE_GAP - width;
            }
            default -> {   // "right"
                width = Math.max(MIN_NOTE_W, contentW);
                left = cx[idxs.get(0)] + NOTE_SIDE_GAP;
            }
        }
        if (left < EDGE_PAD) {
            left = EDGE_PAD;
        }
        return new NoteBox(left, top, width, NOTE_H, display, note.text());
    }

    /// Emits one {@link NoteBox}: a filled background Rect, a stroke-only border (four Lines — a Rect
    /// carries fill only), and the centred note text (glyph paths, contrast-filled against the note
    /// fill, clamped in-canvas), all folded into ONE `<g role="note">` via the assigner.
    private static void emitNote(List<Shape> shapes, NoteBox nb, double canvasW, AnchorAssigner assigner) {
        List<Shape> ng = new ArrayList<>();
        ng.add(new Rect(nb.left, nb.top, nb.width, nb.height, NOTE_FILL));
        double right = nb.left + nb.width;
        double bottom = nb.top + nb.height;
        ng.add(new Line(nb.left, nb.top, right, nb.top, NOTE_STROKE, NOTE_STROKE_W));
        ng.add(new Line(nb.left, bottom, right, bottom, NOTE_STROKE, NOTE_STROKE_W));
        ng.add(new Line(nb.left, nb.top, nb.left, bottom, NOTE_STROKE, NOTE_STROKE_W));
        ng.add(new Line(right, nb.top, right, bottom, NOTE_STROKE, NOTE_STROKE_W));
        double tw = FONT.runWidth(nb.display, NOTE_SIZE);
        double runX = clamp(nb.left + (nb.width - tw) / 2, tw, canvasW);
        double baseline = nb.top + nb.height / 2 + NOTE_SIZE * 0.35;
        String d = FONT.textPathD(nb.display, runX, baseline, NOTE_SIZE);
        if (!d.isBlank()) {
            ng.add(new GlyphRun(d, Colors.contrastFill(NOTE_FILL)));
        }
        shapes.add(new Group(assigner.assign(SirentideRole.NOTE, nb.rawText), ng));
    }

    /// Resolves each {@link SeqBlock}'s FLAT message-index range to a pixel {@link Frame}. For every
    /// block: scan the DRAWN messages in `[fromMsg, toMsg]` for the top y (first drawn), the bottom
    /// extent (last drawn message's row, dropped ROW_H/2 for a self-hook), and the actor x-span
    /// (min/max lifeline x of every endpoint, extended by SELF_W for a self-message hook). A block
    /// with NO drawn message in range — an empty block (`toMsg < fromMsg`), an out-of-range range, or
    /// one whose messages all skipped as unknown-actor — yields no frame (degenerate → inert, DESIGN
    /// §6). Nesting insets the padding by `depth·FRAME_INSET` (clamped ≥ 4) so a nested frame sits
    /// visibly inside its parent. Each `else`/`and` {@link Divider} becomes a {@link FrameDivider} at
    /// its branch message's y (skipped when that message is out of range / not drawn). Returns an
    /// EMPTY list for a block-free sequence, so the emit path adds nothing (byte-identical legacy bake).
    private static List<Frame> buildFrames(List<SeqBlock> blocks, double[] yByMsg, double[] bottomByMsg,
                                           double[] cx, Map<String, Integer> index,
                                           List<SeqMessage> messages) {
        List<Frame> frames = new ArrayList<>();
        if (blocks.isEmpty()) {
            return frames;   // legacy actor+message path — no frames, byte-identical bake
        }
        int msgCount = messages.size();
        for (SeqBlock blk : blocks) {
            int from = blk.fromMsg();
            int to = Math.min(blk.toMsg(), msgCount - 1);
            if (from < 0 || from >= msgCount || to < from) {
                continue;   // empty/out-of-range block — no frame
            }
            double topY = Double.NaN;
            double botY = Double.NEGATIVE_INFINITY;
            double minX = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            for (int mi = from; mi <= to; mi++) {
                double y = yByMsg[mi];
                if (Double.isNaN(y)) {
                    continue;   // a skipped (unknown-actor) message — not drawn, ignore
                }
                if (Double.isNaN(topY)) {
                    topY = y;   // the first drawn message sets the top
                }
                botY = Math.max(botY, bottomByMsg[mi]);
                SeqMessage m = messages.get(mi);
                Integer a = index.get(m.from());
                Integer b = index.get(m.to());
                if (a != null) {
                    minX = Math.min(minX, cx[a]);
                    maxX = Math.max(maxX, cx[a]);
                }
                if (b != null) {
                    minX = Math.min(minX, cx[b]);
                    maxX = Math.max(maxX, cx[b]);
                }
                if (a != null && a.equals(b)) {
                    maxX = Math.max(maxX, cx[a] + SELF_W);   // a self-hook reaches SELF_W to the right
                }
            }
            if (Double.isNaN(topY) || !Double.isFinite(minX)) {
                continue;   // no drawn message in range → no frame
            }
            double effPad = Math.max(4, FRAME_PAD_X - blk.depth() * FRAME_INSET);
            double left = minX - effPad;
            double right = maxX + effPad;
            double top = topY - FRAME_TOP_PAD;
            double bottom = botY + FRAME_BOTTOM_PAD;
            double tabW = FONT.runWidth(blk.kind(), TAB_SIZE) + 2 * TAB_PAD;
            // The block label rides to the right of the tab, ellipsized to the remaining frame width.
            String label = blk.label() == null || blk.label().isEmpty() ? null
                : FONT.ellipsize(blk.label(), Math.max(0, right - (left + tabW) - 2 * TAB_PAD), TAB_SIZE);
            // Dividers: each else/and at its branch message's y (skipped when out of range / not drawn).
            List<FrameDivider> divs = new ArrayList<>();
            double innerW = Math.max(0, right - left - 2 * TAB_PAD);
            for (Divider d : blk.dividers()) {
                int at = d.atMsg();
                if (at < from || at > to || Double.isNaN(yByMsg[at])) {
                    continue;   // a divider with no in-range branch message → inert
                }
                String dl = d.label() == null || d.label().isEmpty() ? null
                    : FONT.ellipsize(d.label(), innerW, TAB_SIZE);
                divs.add(new FrameDivider(yByMsg[at] - DIV_LIFT, dl));
            }
            frames.add(new Frame(blk.kind(), label, left, right, top, bottom, tabW, divs));
        }
        return frames;
    }

    /// Emits one alt/loop/par {@link Frame}: a STROKE-ONLY border (four {@link Line}s — a {@link Rect}
    /// carries fill only, and a stroke-only frame reads cleaner than a tint that darkens when nested),
    /// a filled corner TAB with the kind (`alt`/`loop`/`par`, contrast-filled against the tab) and the
    /// block label beside it, and a DASHED line per divider (Line has no dash attribute — the dividers
    /// are drawn as short segments, staying inside the svg/rect/line/path alphabet). All glyph runs
    /// are clamped in-canvas (the geometry-escape fix class). Emitted BEFORE the lifelines/messages so
    /// the frame sits behind the content it wraps.
    private static void emitFrame(List<Shape> shapes, Frame f, String textColor, double canvasW) {
        // Border — a stroke-only rectangle as four lines.
        shapes.add(new Line(f.left(), f.top(), f.right(), f.top(), FRAME_STROKE, FRAME_STROKE_W));
        shapes.add(new Line(f.left(), f.bottom(), f.right(), f.bottom(), FRAME_STROKE, FRAME_STROKE_W));
        shapes.add(new Line(f.left(), f.top(), f.left(), f.bottom(), FRAME_STROKE, FRAME_STROKE_W));
        shapes.add(new Line(f.right(), f.top(), f.right(), f.bottom(), FRAME_STROKE, FRAME_STROKE_W));
        // Dividers — a dashed horizontal line across the frame, plus a left-aligned label above it.
        for (FrameDivider d : f.dividers()) {
            emitDashedLine(shapes, f.left(), f.right(), d.y());
            if (d.label() != null) {
                double lw = FONT.runWidth(d.label(), TAB_SIZE);
                double runX = clamp(f.left() + TAB_PAD, lw, canvasW);
                String dd = FONT.textPathD(d.label(), runX, d.y() - LABEL_LIFT, TAB_SIZE);
                if (!dd.isBlank()) {
                    shapes.add(new GlyphRun(dd, textColor));
                }
            }
        }
        // Label tab — a filled rect holding the kind glyph (contrast-filled against the tab fill).
        shapes.add(new Rect(f.left(), f.top(), f.tabW(), TAB_H, TAB_FILL));
        double kw = FONT.runWidth(f.kind(), TAB_SIZE);
        double baseline = f.top() + TAB_H / 2 + TAB_SIZE * 0.35;
        String kd = FONT.textPathD(f.kind(), f.left() + (f.tabW() - kw) / 2, baseline, TAB_SIZE);
        if (!kd.isBlank()) {
            shapes.add(new GlyphRun(kd, Colors.contrastFill(TAB_FILL)));
        }
        // Block label — to the right of the tab, aligned on the tab baseline, clamped in-canvas.
        if (f.label() != null) {
            double lw = FONT.runWidth(f.label(), TAB_SIZE);
            double runX = clamp(f.left() + f.tabW() + TAB_PAD, lw, canvasW);
            String ld = FONT.textPathD(f.label(), runX, baseline, TAB_SIZE);
            if (!ld.isBlank()) {
                shapes.add(new GlyphRun(ld, textColor));
            }
        }
    }

    /// A DASHED horizontal line from `x1` to `x2` at `y`, approximated as short solid {@link Line}
    /// segments (the emitter's `<line>` has no `stroke-dasharray` in the contract, so a dash is drawn
    /// as geometry — a walk of DASH-long segments separated by DASH_GAP). Deterministic + bounded (the
    /// segment count is the span / stride, itself bounded by the canvas). A degenerate span emits nothing.
    private static void emitDashedLine(List<Shape> shapes, double x1, double x2, double y) {
        double span = x2 - x1;
        if (!Double.isFinite(span) || span <= 0) {
            return;
        }
        double stride = DASH + DASH_GAP;
        for (double x = x1; x < x2; x += stride) {
            double end = Math.min(x + DASH, x2);
            shapes.add(new Line(x, y, end, y, FRAME_STROKE, FRAME_STROKE_W));
        }
    }

    /// One divider inside an emitted frame: its y (already lifted above the branch's first message)
    /// and an optional (already ellipsized) label. Layout-internal.
    private record FrameDivider(double y, String label) {}

    /// One resolved alt/loop/par frame: the block `kind` + optional (ellipsized) `label`, the border
    /// rectangle `[left,right]×[top,bottom]`, the label-tab width `tabW`, and the divider lines.
    /// Layout-internal — {@link #emitFrame} turns it into border lines + a tab + dashed dividers.
    private record Frame(String kind, String label, double left, double right, double top,
                         double bottom, double tabW, List<FrameDivider> dividers) {}

    /// The VISIBLE degrade for a non-empty sequence body that parsed to zero actors: a small canvas
    /// with a single one-line glyph-run message (11px, `textColor` → `currentColor`), clamped
    /// in-canvas. Never the silent inert shell — a mistyped diagram must still say something.
    private static LaidOut degradeNoMessages(String textColor) {
        double tw = FONT.runWidth(DEGRADE_MSG, MSG_LABEL_SIZE);
        double w = tw + 2 * MARGIN;
        double h = MIN_H;
        double runX = clamp(MARGIN, tw, w);   // keep the whole run inside [EDGE_PAD, w-EDGE_PAD]
        double baseline = h / 2 + MSG_LABEL_SIZE * 0.35;
        List<Shape> shapes = new ArrayList<>();
        String d = FONT.textPathD(DEGRADE_MSG, runX, baseline, MSG_LABEL_SIZE);
        if (!d.isBlank()) {
            shapes.add(new GlyphRun(d, textColor));
        }
        return new LaidOut(w, h, shapes);
    }

    /// Clamps a glyph-run's start x so the whole run `[x, x+w]` stays inside `[EDGE_PAD, W-EDGE_PAD]`
    /// (labels must never cross the canvas edge — the geometry-escape fix class).
    private static double clamp(double x, double w, double canvasW) {
        double max = canvasW - EDGE_PAD - w;
        if (x > max) {
            x = max;
        }
        if (x < EDGE_PAD) {
            x = EDGE_PAD;
        }
        return x;
    }

    /// One activation frame on an actor's lifeline: the actor's column `index`, the y where the bar
    /// STARTS (a call arrived), the y where it ENDS (its matching reply — or `contentBottom` if the
    /// call was never replied to), and the nesting `depth` (0 = the base bar centred on the lifeline;
    /// each concurrent activation of the same actor steps +ACT_OFFSET right so overlapping bars stay
    /// visible). Mutable in `endY` alone: it is filled in when the reply is seen, else left at the
    /// diagram bottom (the unbalanced-call close). Layout-internal, never emitted directly.
    private static final class Activation {
        final int actor;
        final double startY;
        double endY;
        final int depth;

        Activation(int actor, double startY, double endY, int depth) {
            this.actor = actor;
            this.startY = startY;
            this.endY = endY;
            this.depth = depth;
        }
    }

    /// Deterministic 3-dp number formatting for arrowhead path data (byte-identical bakes, DESIGN §6).
    private static String fmt(double v) {
        if (!Double.isFinite(v)) {
            v = 0.0;
        }
        double r = Math.round(v * 1000.0) / 1000.0;
        return r == Math.rint(r) ? Long.toString((long) r) : Double.toString(r);
    }
}
