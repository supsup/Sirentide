plugins {
    `java-library`
    application
    `maven-publish`
}

group = "com.sirentide"
// A real, immutable release version — NOT a rolling SNAPSHOT. Same discipline as LatteX:
// a consumer pins the exact version; a change requires an explicit bump + republish, so a
// pinned consumer can never silently go stale. Bump on each downstream-relevant release.
version = "0.1.0"

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
}

tasks.test {
    useJUnitPlatform()
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
