package com.sirentide.ir;

import java.util.List;

/// A snake-graph continued-fraction figure (plan sirentide-snake-graph-primitive). A continued
/// fraction `[a0; a1, a2, …]` — a list of POSITIVE-INTEGER partial quotients — renders as a
/// connected strip of unit squares (the "snake graph" / dimer object of Çanakçı–Schiffler / Propp,
/// arXiv 2607.14332). Each quotient `a_i` contributes a RUN of `a_i` unit squares in one direction;
/// consecutive quotients ALTERNATE direction (the "turns").
///
/// DIRECTION CONVENTION (documented once, here, and mirrored by {@link
/// com.sirentide.layout.SnakeGraphLayout}): even-index runs (a0, a2, …) go RIGHT (+x), odd-index
/// runs (a1, a3, …) go UP (+y, math coords); the strip is a monotone staircase. The total number of
/// unit squares equals the SUM of the quotients (each square is a distinct cell — a turn is a
/// direction change BETWEEN consecutive squares, never a shared/overlapping cell).
///
/// `quotients` is the parsed, cap-bounded partial-quotient list (each positive; parse-side clamped
/// and the running square-total bounded so a huge CF can't OOM — see {@code DslParser.parseSnake}).
/// An empty list (a bare `snake` with no `cf:`) is valid and bakes an empty canvas. `textColor` is
/// unused by the current layout (the run labels sit ON the square tints and take a contrast-derived
/// fill) but is threaded for parity with the other types and future off-strip labels; it defaults to
/// `currentColor`.
public record Snake(List<Integer> quotients, String textColor) implements Diagram {

    public Snake {
        quotients = List.copyOf(quotients);
        if (textColor == null) {
            textColor = "currentColor";
        }
    }
}
