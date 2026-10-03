#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${HOST_TEST_TOOLS:?Set HOST_TEST_TOOLS to a directory containing compiler.jar, stdlib.jar, script.jar, reflect.jar, coroutines.jar, annotations.jar and json.jar (see tests/host/README.md)}"
java_cmd="${JAVA_HOME:+$JAVA_HOME/bin/}java"
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
sources=()
for name in Oci Tar Config Supervisor Deploys Http Dashboard Apps Paths; do
    sources+=("app/src/main/java/dev/homedroid/$name.kt")
done
compiler_cp="$HOST_TEST_TOOLS/compiler.jar:$HOST_TEST_TOOLS/stdlib.jar:$HOST_TEST_TOOLS/script.jar:$HOST_TEST_TOOLS/reflect.jar:$HOST_TEST_TOOLS/coroutines.jar:$HOST_TEST_TOOLS/annotations.jar"
"$java_cmd" -cp "$compiler_cp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 \
    -classpath "$HOST_TEST_TOOLS/stdlib.jar:$HOST_TEST_TOOLS/json.jar:$HOST_TEST_TOOLS/annotations.jar" \
    -d "$build/tests.jar" "${sources[@]}" tests/host/stubs/*.kt tests/host/Tests.kt
"$java_cmd" -cp "$build/tests.jar:$HOST_TEST_TOOLS/stdlib.jar:$HOST_TEST_TOOLS/json.jar" dev.homedroid.TestsKt
