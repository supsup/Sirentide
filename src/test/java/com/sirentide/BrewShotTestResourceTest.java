package com.sirentide;

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
        assertDoesNotThrow(() -> BrewShotTestResource.close(() -> {
            throw new UncheckedIOException(new NoSuchFileException("singleton-lock"));
        }));
    }

    @Test
    void everyOtherUncheckedIoFailureStillFailsLoud() {
        UncheckedIOException expected = new UncheckedIOException(new IOException("disk failure"));

        UncheckedIOException actual = assertThrows(UncheckedIOException.class,
            () -> BrewShotTestResource.close(() -> { throw expected; }));

        assertSame(expected, actual);
    }

    @Test
    void nonIoCloseFailuresStillFailLoud() {
        IllegalStateException expected = new IllegalStateException("transport still owned");

        IllegalStateException actual = assertThrows(IllegalStateException.class,
            () -> BrewShotTestResource.close(() -> { throw expected; }));

        assertSame(expected, actual);
    }
}
