package com.sirentide.api;

import java.util.List;

/// The play-through bake plus its structured WHY — the frames twin of {@link RenderResult}
/// (plan sirentide-fence-diagnostics). `frames` is exactly what
/// {@link Sirentide#renderFrames(String, MathFragmentRenderer)} returns for the same input
/// (byte-identical, including every degrade), and `diagnostics` classifies the outcome the
/// same way {@link Sirentide#renderWithDiagnostics(String, MathFragmentRenderer)} does.
public record FramesResult(List<String> frames, Diagnostics diagnostics) {}
