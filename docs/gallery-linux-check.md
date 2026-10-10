# Rendering the gallery on Linux

The gallery PNGs under `examples/gallery/` are references, not byte-goldens: `BrewShotGalleryTest`
rewrites them on every run, and the bytes depend on the host's Chromium. A macOS run and a Linux run
disagree on some images. The committed references are the Linux render below, so that anyone can
reproduce them exactly.

**Do not commit gallery PNGs from a macOS test run.** After running the suite on a Mac, restore them:
`git restore examples/gallery`.

## The harness

The same base image and Chromium pins as BrewShot's own Dockerfile, run as a non-root user:

```dockerfile
FROM eclipse-temurin:25-jdk-alpine@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151
RUN apk add --no-cache chromium=149.0.7827.53-r0 font-liberation=2.1.5-r2 font-dejavu=2.37-r6 font-noto-emoji=2.048-r0 \
    && adduser -D -u 1000 tester
ENV BREWSHOT_CHROME=/usr/bin/chromium-browser \
    BREWSHOT_CHROME_ARGS="--no-sandbox --disable-dev-shm-usage"
USER 1000:1000
```

## Render and compare

From a clean checkout, with the Dockerfile above saved as `harness/Dockerfile` outside the checkout:

```sh
docker build -t sirentide-gallery-linux harness
rev=$(git rev-parse HEAD)
work=$(mktemp -d)
mkdir -p "$work/src" "$work/home"
git archive "$rev" | tar -x -C "$work/src"
chmod -R a+rwX "$work"
docker run --rm -e HOME=/work/home -e GRADLE_USER_HOME=/work/home/.gradle \
  -v "$work:/work" -w /work/src sirentide-gallery-linux \
  ./gradlew -q test --tests com.sirentide.BrewShotGalleryTest
# every committed gallery file against the Linux render
for f in $(git ls-tree --name-only "$rev" examples/gallery/); do
  git show "$rev:$f" | cmp -s - "$work/src/$f" || echo "DIFFERS $f"
done
```

No `DIFFERS` line means the committed gallery is the Linux render. Check that the test report shows
the gallery tests ran rather than skipped (`skipped="0"` in
`$work/src/build/test-results/test/TEST-com.sirentide.BrewShotGalleryTest.xml`); a skip means
Chromium was not found and nothing was rendered.

When a gallery Case is added or a renderer change moves an image, regenerate with the same commands
and copy `examples/gallery/` back from `$work/src` before committing.
