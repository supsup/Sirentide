package com.sirentide.api;

/// Trusted embedding-consumer limits for one play-through frame deck.
///
/// This is Java API input, not author-controlled DSL. Both limits are positive and may only
/// narrow Sirentide's independent producer limits. A consumer can therefore reject work before
/// retaining a deck without weakening the existing renderer-wide defenses.
public record FrameBudget(int maxFrames, long maxUtf8Bytes) {

    public FrameBudget {
        if (maxFrames <= 0 || maxFrames > Sirentide.MAX_FRAMES) {
            throw new IllegalArgumentException("maxFrames must be between 1 and "
                + Sirentide.MAX_FRAMES + " (inclusive)");
        }
        if (maxUtf8Bytes <= 0 || maxUtf8Bytes > Sirentide.MAX_TOTAL_OUTPUT_BYTES) {
            throw new IllegalArgumentException("maxUtf8Bytes must be between 1 and "
                + Sirentide.MAX_TOTAL_OUTPUT_BYTES + " (inclusive)");
        }
    }
}
