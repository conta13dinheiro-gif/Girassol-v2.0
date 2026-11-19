#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
: "${JAVA_HOME:?Set JAVA_HOME to JDK17}"
./gradlew :app:compileDebugKotlin :core:jar :core:writeHostClasspath --no-daemon
build_root="$(mktemp -d)"
trap 'rm -rf "$build_root"' EXIT
c++ -std=c++20 -O1 -Wall -Wextra -Werror -fPIC -fno-exceptions -DHNSWLIB_ENABLE_EXCEPTIONS=OFF -DHNSWLIB_USE_PREFETCH=0 \
  -isystem app/src/main/cpp/third_party/hnswlib -c app/src/main/cpp/mind/vector_memory.cpp -o "$build_root/vector_memory.o"
c++ -std=c++20 -O1 -Wall -Wextra -Werror -shared -fPIC -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  app/src/main/cpp/mind/plasticity.cpp app/src/main/cpp/mind/mind_jni.cpp app/src/main/cpp/mind/vector_memory_jni.cpp \
  app/src/main/cpp/evolution/cma_es.cpp app/src/main/cpp/evolution/cma_jni.cpp "$build_root/vector_memory.o" -o "$build_root/libgirassol_projection.so"
host_cp="app/build/tmp/kotlin-classes/debug:core/build/libs/core.jar:$(cat core/build/host-runtime-classpath.txt)"
javac -cp "$host_cp" -d "$build_root" app/src/test/host/*.java
java -Xmx768m -Djava.library.path="$build_root" -cp "$host_cp:$build_root" MindIntegration
java -Xmx768m -Djava.library.path="$build_root" -cp "$host_cp:$build_root" ShadowIntegration
