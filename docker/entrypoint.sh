#!/bin/sh
set -eu

sirentide_jar=/opt/sirentide/sirentide.jar
worker_jar=/opt/sirentide/sirentide-worker.jar

case "${1-}" in
    watch)
        shift
        if [ "$#" -ne 0 ]; then
            echo "sirentide: watch mode takes no arguments" >&2
            exit 2
        fi
        exec java -cp "$sirentide_jar:$worker_jar" \
            com.sirentide.cli.SirentideFolderWorker
        ;;
    cli)
        shift
        exec java -jar "$sirentide_jar" "$@"
        ;;
    *)
        # Compatibility shape: arguments that do not name a container mode go
        # straight to the shipped CLI. With no arguments, stdin DSL -> stdout
        # remains byte-for-byte the original one-shot contract.
        exec java -jar "$sirentide_jar" "$@"
        ;;
esac
