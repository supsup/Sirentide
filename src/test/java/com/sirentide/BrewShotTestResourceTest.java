package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import org.junit.jupiter.api.Test;

class BrewShotTestResourceTest {

    @Test
    void aProfileEntryThatChromeAlreadyRemovedDoesNotFailTeardown() {
        assertDoesNotThrow(() -> {
            try (AutoCloseable closeGuard = BrewShotTestResource.asResource(() -> {
                throw new UncheckedIOException(new NoSuchFileException("singleton-lock"));
            })) {
                // Closing the resource exercises the adapter.
            }
        });
    }

    @Test
    void everyOtherUncheckedIoFailureStillFailsLoud() {
        UncheckedIOException expected = new UncheckedIOException(new IOException("disk failure"));

        UncheckedIOException actual = assertThrows(UncheckedIOException.class, () -> {
            try (AutoCloseable closeGuard = BrewShotTestResource.asResource(() -> {
                throw expected;
            })) {
                // Closing the resource exercises the adapter.
            }
        });

        assertSame(expected, actual);
    }

    @Test
    void nonIoCloseFailuresStillFailLoud() {
        IllegalStateException expected = new IllegalStateException("transport still owned");

        IllegalStateException actual = assertThrows(IllegalStateException.class, () -> {
            try (AutoCloseable closeGuard = BrewShotTestResource.asResource(() -> {
                throw expected;
            })) {
                // Closing the resource exercises the adapter.
            }
        });

        assertSame(expected, actual);
    }

    @Test
    void aBodyFailureRemainsPrimaryWhenCloseAlsoFails() {
        AssertionError bodyFailure = new AssertionError("browser assertion failed");
        IllegalStateException closeFailure = new IllegalStateException("transport still owned");

        AssertionError actual = assertThrows(AssertionError.class, () -> {
            try (AutoCloseable closeGuard = BrewShotTestResource.asResource(() -> {
                throw closeFailure;
            })) {
                throw bodyFailure;
            }
        });

        assertSame(bodyFailure, actual);
        assertArrayEquals(new Throwable[] {closeFailure}, actual.getSuppressed());
    }
}
