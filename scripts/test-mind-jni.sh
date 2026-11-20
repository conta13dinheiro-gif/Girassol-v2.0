#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
: "${JAVA_HOME:?Set JAVA_HOME to JDK17}"
./gradlew -Dorg.gradle.configureondemand=true :host-validation:writeHostClasspath --no-daemon
build_root="$(mktemp -d)"
trap 'rm -rf "$build_root"' EXIT
c++ -std=c++20 -O1 -Wall -Wextra -Werror -fPIC -DHNSWLIB_ENABLE_EXCEPTIONS=ON -DHNSWLIB_USE_PREFETCH=0 \
  -isystem app/src/main/cpp/third_party/hnswlib -c app/src/main/cpp/mind/vector_memory.cpp -o "$build_root/vector_memory.o"
c++ -std=c++20 -O1 -Wall -Wextra -Werror -fPIC -DHNSWLIB_ENABLE_EXCEPTIONS=ON -DHNSWLIB_USE_PREFETCH=0 \
  -isystem app/src/main/cpp/third_party/hnswlib -c app/src/main/cpp/mind/semantic_index.cpp -o "$build_root/semantic_index.o"
c++ -std=c++20 -O1 -Wall -Wextra -Werror -shared -fPIC -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  app/src/main/cpp/girassol_gea_jni.cpp app/src/main/cpp/girassol_autonomy_jni.cpp app/src/main/cpp/projection.cpp app/src/main/cpp/projection_jni.cpp app/src/main/cpp/mind/plasticity.cpp app/src/main/cpp/mind/mind_jni.cpp app/src/main/cpp/mind/vector_memory_jni.cpp app/src/main/cpp/mind/semantic_index_jni.cpp \
  -ffp-contract=off -fno-fast-math app/src/main/cpp/city_mesh.cpp app/src/main/cpp/city_mesh_jni.cpp app/src/main/cpp/physics/spatial.cpp app/src/main/cpp/physics/spatial_jni.cpp \
  app/src/main/cpp/evolution/cma_es.cpp app/src/main/cpp/evolution/cma_jni.cpp "$build_root/vector_memory.o" "$build_root/semantic_index.o" -o "$build_root/libgirassol_core.so"
host_cp="$(cat host-validation/build/runtime-classpath.txt)"
mkdir "$build_root/absent"
java -Djava.library.path="$build_root/absent" -cp "$host_cp" GeaContractIntegration absent
java -Djava.library.path="$build_root" -cp "$host_cp" GeaContractIntegration present
java -Djava.library.path="$build_root" -cp "$host_cp" TsetlinIntegration
java -Xmx768m -Djava.library.path="$build_root" -cp "$host_cp" MindIntegration
java -Xmx768m -Djava.library.path="$build_root" -cp "$host_cp" ShadowIntegration
java -Xmx768m -Djava.library.path="$build_root" -cp "$host_cp" AutonomyIntegration
