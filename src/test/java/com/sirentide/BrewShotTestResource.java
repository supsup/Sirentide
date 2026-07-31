package com.sirentide;

import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;

/**
 * Closes a BrewShot test resource while containing the one teardown race fixed upstream after
 * the vendored 0.6.0 jar.
 */
final class BrewShotTestResource {

    private BrewShotTestResource() {}

    static void close(AutoCloseable resource) throws Exception {
        try {
            resource.close();
        } catch (UncheckedIOException failure) {
            if (failure.getCause() instanceof NoSuchFileException) {
                // Chrome may remove its singleton-lock entry while BrewShot 0.6.0 walks the
                // already-shut-down profile. The desired end state is still "entry absent".
                // BrewShot 0.7.1 fixed this exact race in upstream commit 60b4c60.
                return;
            }
            throw failure;
        }
    }
}
