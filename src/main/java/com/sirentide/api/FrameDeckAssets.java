package com.sirentide.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/// Optional progressive-enhancement assets for Sirentide frame decks.
///
/// The bytes are loaded from this exact Sirentide artifact and returned defensively, so an
/// embedding consumer can serve the runtime and stylesheet from the same jar used to render the
/// frames. Ordinary Sirentide SVG rendering has no runtime dependency on either asset.
public final class FrameDeckAssets {

    private static final String JAVASCRIPT_RESOURCE =
        "/com/sirentide/frames/sirentide-frames.js";
    private static final String STYLESHEET_RESOURCE =
        "/com/sirentide/frames/sirentide-frames.css";

    private FrameDeckAssets() {}

    /// Return the exact bundled frame-deck JavaScript bytes.
    public static byte[] javascript() {
        return JavaScriptHolder.BYTES.clone();
    }

    /// Return the exact bundled frame-deck stylesheet bytes.
    public static byte[] stylesheet() {
        return StylesheetHolder.BYTES.clone();
    }

    private static final class JavaScriptHolder {
        private static final byte[] BYTES = bundledResource(JAVASCRIPT_RESOURCE);
    }

    private static final class StylesheetHolder {
        private static final byte[] BYTES = bundledResource(STYLESHEET_RESOURCE);
    }

    private static byte[] bundledResource(String path) {
        try (InputStream input = FrameDeckAssets.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("bundled Sirentide frame-deck resource missing: "
                    + path);
            }
            return input.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read bundled Sirentide frame-deck resource "
                + path, e);
        }
    }
}
