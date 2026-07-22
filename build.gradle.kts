plugins {
    `java-library`
    application
    `maven-publish`
}

group = "com.sirentide"
// A real, immutable release version — NOT a rolling SNAPSHOT. Same discipline as LatteX:
// a consumer pins the exact version; a change requires an explicit bump + republish, so a
// pinned consumer can never silently go stale. Bump on each downstream-relevant release.
// 0.4.0: the heatmap type (21 → 22) — continuous-score grid on a single-hue sequential
// ramp + legend; to be vendored into stafficy /docs as sirentide-0.4.0.jar (part B).
version = "0.4.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
    // The LatteX render-only dependency (com.lattex:lattex, for math-in-labels) wires in at
    // M1, once its published artifact / composite resolution is confirmed. M0 core needs no
    // runtime deps — the whole point is a hermetic, zero-dep bake (see docs/DESIGN.md §2).
}

// Sirentide ships ZERO runtime dependencies (hermetic bake). Test-scope only for now.
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // BrewShot: real-browser screenshot + in-page audit harness (Confluence, PROJECT/brewshot).
    // TEST SCOPE ONLY — the zero-runtime-dependency bake is untouched. Pins what XML can't see
    // (labels/shapes escaping the canvas: the negative-x clip + y=226 stacking from review 14).
    testImplementation(files("libs/brewshot-0.6.0.jar"))
    // LatteX: the real LaTeX->SVG math backend for the math-in-labels moat proof
    // (MathInLabelsRealRenderTest + LatteXMathFragmentRenderer). TEST SCOPE ONLY — the core bake
    // stays hermetic (ZERO runtime dependencies). A downstream consumer that wants baked math
    // supplies this jar + a renderer itself; Sirentide never depends on LatteX at runtime.
    testImplementation(files("libs/lattex-0.6.0.jar"))
}

tasks.test {
    useJUnitPlatform()
    // Forward the golden-regen switch to the forked test JVM so
    // `./gradlew test -Dsirentide.updateGolden=true` actually reaches GoldenSvgTest.
    systemProperty("sirentide.updateGolden", System.getProperty("sirentide.updateGolden", "false"))
    // Same for the showcase-page regen switch, so
    // `./gradlew test -Dsirentide.updateShowcase=true` reaches ShowcaseGenTest.
    systemProperty("sirentide.updateShowcase", System.getProperty("sirentide.updateShowcase", "false"))
    testLogging { events("skipped", "failed") }

    // CI-honesty guard: the BrewShot browser-eyes pins assume-skip when Chrome is
    // absent (the only skips in this suite). CI installs Chrome and sets
    // SIRENTIDE_REQUIRE_CHROME, so ANY skip there means the browser pins silently
    // didn't run — a green that tested nothing. Fail instead.
    val requireChrome = System.getenv("SIRENTIDE_REQUIRE_CHROME")
        ?.let { it == "1" || it.equals("true", true) || it.equals("yes", true) } ?: false
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
        if (desc.parent == null && requireChrome && result.skippedTestCount > 0) {
            throw GradleException(
                "SIRENTIDE_REQUIRE_CHROME is set but ${result.skippedTestCount} test(s) were " +
                "SKIPPED — the BrewShot browser pins must run (Chrome present) or fail, never " +
                "skip green (green-that-tested-nothing guard)."
            )
        }
    }))
}

application {
    // Classpath app for M0 (non-modular — keeps the build + tests simple). A module-info can
    // be added later if we want JPMS strictness; it is not load-bearing for the scaffold.
    mainClass = "com.sirentide.cli.Main"
}

// Make the plain library jar directly launchable: `java -jar build/libs/sirentide-<ver>.jar`.
tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "com.sirentide.cli.Main",
            "Implementation-Title" to "Sirentide",
            "Implementation-Version" to project.version.toString(),
        )
    }
}
