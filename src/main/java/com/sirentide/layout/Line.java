package com.sirentide.layout;

/// A laid-out straight line segment (user coordinates) — e.g. an xychart axis. Emit serializes it
/// to a `<line>` with a numeric stroke (M1 sirentide-output-contract alphabet).
public record Line(double x1, double y1, double x2, double y2, String stroke, double strokeWidth)
    implements Shape {

    /// Charges the global layout-time work budget ({@link LayoutWorkBudget}, plan fe8c5bbc slice 2).
    /// A `<line>` is the cheapest primitive per unit and therefore the one an aggregate blow-up is
    /// built from (a dotted flowchart edge emits one per dash stride), so it is exactly the shape a
    /// per-path cap cannot bound in the SUM. A no-op when no layout scope is armed.
    public Line {
        LayoutWorkBudget.charge(LayoutWorkBudget.WEIGHT_LINE);
    }
}
