package com.sirentide.ir;

/// A classical KNOT DIAGRAM (plan sirentide-knot-diagram-primitive): a 2D projection of a knot — one
/// closed smooth curve in the plane with a finite set of transverse double-point CROSSINGS, each drawn
/// with the UNDER strand BROKEN (a small gap) so the over/under reads unambiguously. Bounded to small,
/// classical, ALTERNATING knots (over/under alternate along the traversal), each defined by a hard-coded
/// LOOKUP — a deterministic parametric curve plus a crossing table — never a solver:
///
///   * `unknot`   — the trivial knot (0 crossings): a plain closed loop (a circle).
///   * `trefoil`  — the 3₁ knot (3 crossings): the 3-fold-symmetric knot; Gauss code O1U2O3U1O2U3.
///
/// The `figure8` (4₁) knot is DEFERRED to a follow-up (review round 2): its smooth parametrization
/// projects only to the non-iconic "loop-and-weave" shadow family, so it ships once the iconic two-lobe
/// "pretzel" diagram is hand-built.
///
/// The semantic ORACLE is the GAUSS CODE reconstructed from the emitted geometry (traverse the closed
/// curve in emit order; at each crossing record its id + whether THIS pass is OVER or UNDER, recovered
/// from which strand carries the gap) — it must equal the knot's KNOWN code (see {@code
/// KnotGaussCodeOracleTest}, the snake matching-count / dynkin Cartan analogue). `type` is the parsed,
/// normalized knot name (always one of the built-ins above — the parser degrades an unknown/missing
/// type to {@link Empty}, never constructing an inert Knot). `textColor` is threaded for parity with
/// every other type (the knot carries NO `$…$` labels this slice) and defaults to `currentColor`.
public record Knot(String type, String textColor) implements Diagram {

    /// The trivial knot: a plain closed loop, zero crossings.
    public static final String UNKNOT = "unknot";
    /// The 3₁ knot: three crossings, 3-fold symmetric.
    public static final String TREFOIL = "trefoil";

    public Knot {
        if (textColor == null) {
            textColor = "currentColor";
        }
    }
}
