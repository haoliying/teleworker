#!/usr/bin/env bash
# Build helper: pins JDK 17 (AGP 8.x does not support JDK 25) and keeps the
# Gradle cache off the nearly-full C: drive.
set -euo pipefail

export JAVA_HOME="D:/.buildenv/jdk-17"
export GRADLE_USER_HOME="D:/.buildenv/gradle-home"
export ANDROID_HOME="D:/Android/Sdk"
export ANDROID_SDK_ROOT="D:/Android/Sdk"
export PATH="$JAVA_HOME/bin:$PATH"

GRADLE_BIN="D:/.buildenv/gradle-home/wrapper/dists/gradle-8.9-bin/78qddjpeqn5v6yec3xb8kv9ca/gradle-8.9/bin/gradle"

cd "$(dirname "$0")"
"$GRADLE_BIN" "$@"
