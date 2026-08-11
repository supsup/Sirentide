# Sirentide in a box: build the zero-dependency jar and the container-only
# folder worker from source, then copy only those immutable artifacts into a
# non-root Java 25 runtime.

FROM eclipse-temurin:25-jdk AS build

# The jar stamps Sirentide-Source-Revision and REQUIRES an exact 40-hex commit. This context
# has no repository — .dockerignore excludes .git — so git cannot supply it here and adding
# git to this stage would not help. The caller passes it in:
#
#     docker build --build-arg SIRENTIDE_SOURCE_REVISION="$(git rev-parse HEAD)" .
#
# Left unset, the build FAILS rather than producing an unstamped jar, which is the correct
# posture for an artifact whose whole contract is naming the tree it was cut from.
ARG SIRENTIDE_SOURCE_REVISION
ENV SIRENTIDE_SOURCE_REVISION=${SIRENTIDE_SOURCE_REVISION}

WORKDIR /src
COPY . .

RUN ./gradlew --no-daemon clean jar \
    && mkdir -p /out \
    && jar_path="$(find build/libs -maxdepth 1 -type f -name 'sirentide-*.jar' \
        ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)" \
    && test -n "$jar_path" \
    && cp "$jar_path" /out/sirentide.jar

RUN mkdir -p /worker-classes \
    && javac -cp /out/sirentide.jar -d /worker-classes \
        docker/SirentideFolderWorker.java \
    && jar --create --file /out/sirentide-worker.jar \
        --main-class com.sirentide.cli.SirentideFolderWorker \
        -C /worker-classes .

FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S -g 10001 sirentide \
    && adduser -S -D -H -u 10001 -G sirentide sirentide \
    && mkdir -p /opt/sirentide \
        /sirentide/input/processing \
        /sirentide/input/finished \
        /sirentide/input/failed \
        /sirentide/input/failed/pending \
        /sirentide/output \
    && chown -R sirentide:sirentide /sirentide

COPY --from=build /out/sirentide.jar /opt/sirentide/sirentide.jar
COPY --from=build /out/sirentide-worker.jar /opt/sirentide/sirentide-worker.jar
COPY docker/entrypoint.sh /opt/sirentide/entrypoint.sh
RUN chmod 0555 /opt/sirentide/entrypoint.sh \
    && chmod 0444 /opt/sirentide/sirentide.jar /opt/sirentide/sirentide-worker.jar

USER 10001:10001
WORKDIR /sirentide

ENTRYPOINT ["/opt/sirentide/entrypoint.sh"]
