#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
classes="$script_dir/target/dependency-graph-classes"
mkdir -p "$classes"

if [ -n "${JAVA_HOME:-}" ]; then
    javac="$JAVA_HOME/bin/javac"
    java="$JAVA_HOME/bin/java"
else
    javac=javac
    java=java
fi

"$javac" --release 21 -d "$classes" "$script_dir"/src/gama/ui/devtools/dependencies/*.java
"$java" -cp "$classes" gama.ui.devtools.dependencies.Main "$@"
