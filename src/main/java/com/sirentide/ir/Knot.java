package com.sirentide.ir;

/// A classical KNOT DIAGRAM (plan sirentide-knot-diagram-primitive): a 2D projection of a knot — one
/// closed smooth curve in the plane with a finite set of transverse double-point CROSSINGS, each drawn
/// with the UNDER strand BROKEN (a small gap) so the over/under reads unambiguously. Bounded to small,
/// classical, ALTERNATING knots (over/under alternate along the traversal), each defined by a hard-coded
/// LOOKUP — a deterministic parametric curve plus a crossing table — never a solver:
///
///   * `unknot`   — the trivial knot (0 crossings): a plain closed loop (a circle).
///   * `trefoil`  — the 3₁ knot (3 crossings): the 3-fold-symmetric knot; Gauss code O1U2O3U1O2U3.
///   * `figure8`  — the 4₁ knot (4 crossings): the figure-eight knot — a wide loop with a small top loop
///     and a bottom teardrop. Its diagram is a HAND-BUILT planar embedding (plan 5f48185e): 4₁ is not a
///     Lissajous knot, so no smooth 3D projection realizes it; the curve was found by searching harmonic
///     families for a REDUCED, ALTERNATING, INTERLEAVED 4-crossing realization (the Gauss-code-
///     realization problem). Gauss code O1U4O2U1O3U2O4U3.
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
    /// The 4₁ knot: the figure-eight, four alternating crossings.
    public static final String FIGURE8 = "figure8";

    public Knot {
        if (textColor == null) {
            textColor = "currentColor";
        }
    }
}
