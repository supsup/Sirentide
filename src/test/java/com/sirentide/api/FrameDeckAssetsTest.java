package com.sirentide.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FrameDeckAssetsTest {

    private static final String JAVASCRIPT_RESOURCE =
        "/com/sirentide/frames/sirentide-frames.js";
    private static final String STYLESHEET_RESOURCE =
        "/com/sirentide/frames/sirentide-frames.css";

    @Test
    void accessorsReturnTheExactBundledResourceBytes() throws IOException {
        byte[] javascript = FrameDeckAssets.javascript();
        byte[] stylesheet = FrameDeckAssets.stylesheet();

        assertTrue(javascript.length > 0);
        assertTrue(stylesheet.length > 0);
        assertArrayEquals(resourceBytes(JAVASCRIPT_RESOURCE), javascript);
        assertArrayEquals(resourceBytes(STYLESHEET_RESOURCE), stylesheet);
    }

    @Test
    void callersCannotMutateTheCachedArtifactBytes() {
        byte[] first = FrameDeckAssets.javascript();
        byte original = first[0];
        first[0] = (byte) (original ^ 0x7f);

        assertNotEquals(first[0], FrameDeckAssets.javascript()[0]);
        assertEquals(original, FrameDeckAssets.javascript()[0]);
    }

    @Test
    void runtimeUsesOnlyFixedDomConstructionAndDirectChildFrames() {
        String javascript = new String(FrameDeckAssets.javascript(), StandardCharsets.UTF_8);

        assertTrue(javascript.contains("document.querySelectorAll(\".sirentide\")"));
        assertTrue(javascript.contains("wrapper.children"));
        assertTrue(javascript.contains("document.createElement"));
        assertTrue(javascript.contains("addEventListener"));
        assertTrue(javascript.contains(".hidden"));
        assertTrue(javascript.contains("aria-controls"));
        assertTrue(javascript.contains("aria-live"));
        assertTrue(javascript.contains("Previous"));
        assertTrue(javascript.contains("Next"));
        assertTrue(javascript.contains("Step "));

        for (String forbidden : new String[] {
                "innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(",
                "new Function", "onclick", "onload", "dataset", "getAttribute("}) {
            assertFalse(javascript.contains(forbidden), "forbidden runtime sink: " + forbidden);
        }
    }

    @Test
    void stylesheetIsInactiveUntilTheRuntimeMarksADeckReady() {
        String stylesheet = new String(FrameDeckAssets.stylesheet(), StandardCharsets.UTF_8);

        assertTrue(stylesheet.contains(".sirentide.sirentide-frames-ready"));
        assertTrue(stylesheet.contains("[hidden]"));
        assertFalse(stylesheet.contains("@keyframes"));
        assertFalse(stylesheet.contains("animation"));
        assertFalse(stylesheet.contains(".sirentide > svg"),
            "unenhanced frames must remain visible in source order");
    }

    private static byte[] resourceBytes(String path) throws IOException {
        try (InputStream input = FrameDeckAssetsTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new AssertionError("missing test resource " + path);
            }
            return input.readAllBytes();
        }
    }
}
