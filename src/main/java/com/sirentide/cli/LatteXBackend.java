package com.sirentide.cli;

import com.sirentide.api.MathFragment;
import com.sirentide.api.MathFragmentRenderer;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/// The `--math` backend: LatteX, located at RUN time from a jar path (plan d9f29911 item 1).
///
/// WHY REFLECTION RATHER THAN A DEPENDENCY. Sirentide ships zero runtime dependencies (the hermetic
/// bake; `build.gradle.kts` says "Sirentide never depends on LatteX at runtime"), and `--png` already
/// set the CLI's posture for heavy tools: the HOST supplies the jar, and its absence is a loud usage
/// error. So this class names LatteX only as strings and calls exactly two API points that the
/// test-scope `LatteXMathFragmentRenderer` adapter calls statically: `LatteX.renderFragment(String,
/// double)` and the four geometry accessors of its `MathFragment` record. The mapping is the same
/// field copy as that adapter, which is what lets the CLI tests demand byte identity with the API.
///
/// ISOLATED LOADER. The jar is loaded with the PLATFORM loader as parent, not the application loader,
/// so a LatteX that happens to sit on the caller's classpath can never answer for a `--lattex` jar
/// that does not contain it -- the jar the author named is the jar that renders, or the load fails.
///
/// LOAD IS PROVEN, NOT ASSUMED: {@link #load} renders a probe (`x`) before returning, so a jar that
/// loads but cannot render (a missing font resource, an incompatible API) is an exit-2 usage error
/// up front, never a whole bake of silent raw-text fallbacks at exit 0.
final class LatteXBackend implements AutoCloseable {

    private static final String API_CLASS = "com.lattex.api.LatteX";
    private static final String FRAGMENT_CLASS = "com.lattex.api.MathFragment";

    private final URLClassLoader loader;
    private final Method renderFragment;
    private final Method innerSvg;
    private final Method widthPx;
    private final Method heightPx;
    private final Method depthPx;

    private LatteXBackend(URLClassLoader loader, Method renderFragment, Method innerSvg,
                          Method widthPx, Method heightPx, Method depthPx) {
        this.loader = loader;
        this.renderFragment = renderFragment;
        this.innerSvg = innerSvg;
        this.widthPx = widthPx;
        this.heightPx = heightPx;
        this.depthPx = depthPx;
    }

    /// Loads LatteX from `jar`, or reports why not on `err` and returns null (the caller exits 2).
    static LatteXBackend load(String jar, PrintStream err) {
        Path path = Path.of(jar);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            err.println("sirentide: LatteX jar not readable: '" + jar + "'");
            return null;
        }
        URLClassLoader loader = null;
        try {
            loader = new URLClassLoader(new URL[] {path.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
            Class<?> api = Class.forName(API_CLASS, true, loader);
            Class<?> frag = Class.forName(FRAGMENT_CLASS, true, loader);
            LatteXBackend backend = new LatteXBackend(loader,
                api.getMethod("renderFragment", String.class, double.class),
                frag.getMethod("innerSvg"), frag.getMethod("widthPx"),
                frag.getMethod("heightPx"), frag.getMethod("depthPx"));
            if (backend.renderOrEmpty("x", 16.0).isEmpty()) {
                throw new IllegalStateException("probe render of 'x' produced no fragment");
            }
            return backend;
        } catch (ReflectiveOperationException | LinkageError | IOException | RuntimeException e) {
            err.println("sirentide: cannot load LatteX from '" + jar + "': " + e);
            if (loader != null) {
                try {
                    loader.close();
                } catch (IOException ignored) {
                    // Already failing loudly; a close failure adds nothing.
                }
            }
            return null;
        }
    }

    /// A renderer bound to this backend that RECORDS every LaTeX source it could not typeset into
    /// `untypeset`, so the CLI can surface the raw-text fallback on the caveat channel. The fallback
    /// itself is Sirentide's (an empty result means "show the raw `$...$` source"); this only stops it
    /// being silent to a caller that reads exit codes rather than pictures.
    MathFragmentRenderer recording(Set<String> untypeset) {
        return (latex, fontSizePx) -> {
            Optional<MathFragment> f = renderOrEmpty(latex, fontSizePx);
            if (f.isEmpty()) {
                untypeset.add(latex);
            }
            return f;
        };
    }

    /// Same failure mapping as the test-scope adapter: a RuntimeException or StackOverflowError from
    /// LatteX (malformed LaTeX throws MathSyntaxException) is an empty result; any other Error is not
    /// ours to swallow and propagates.
    private Optional<MathFragment> renderOrEmpty(String latex, double fontSizePx) {
        try {
            Object f = renderFragment.invoke(null, latex, fontSizePx);
            return Optional.of(new MathFragment((String) innerSvg.invoke(f), (double) widthPx.invoke(f),
                (double) heightPx.invoke(f), (double) depthPx.invoke(f)));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error error && !(cause instanceof StackOverflowError)) {
                throw error;
            }
            return Optional.empty();
        } catch (IllegalAccessException | RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        try {
            loader.close();
        } catch (IOException ignored) {
            // The bake is complete; failing to release the jar handle is not a render outcome.
        }
    }
}
