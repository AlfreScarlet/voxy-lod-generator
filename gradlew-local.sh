#!/usr/bin/env bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
JAVA_HOME_CANDIDATE=""
for candidate in "$ROOT"/.tools/jdk-25 "$ROOT"/.tools/jdk-24 "$ROOT"/.tools/jdk-21 "$ROOT"/.tools/amazon-corretto-*; do
  if [[ -x "$candidate/bin/java" ]]; then
    JAVA_HOME_CANDIDATE="$candidate"
    break
  fi
done
if [[ -z "$JAVA_HOME_CANDIDATE" ]]; then
  echo "No project-local JDK 25 found under $ROOT/.tools" >&2
  exit 1
fi
export JAVA_HOME="$JAVA_HOME_CANDIDATE"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$ROOT/.gradle-home"
if [[ -x "$ROOT/.tools/gradle-9.5.1/bin/gradle" ]]; then
  exec "$ROOT/.tools/gradle-9.5.1/bin/gradle" "$@"
fi
if [[ -x "$ROOT/.tools/gradle-8.14/bin/gradle" ]]; then
  exec "$ROOT/.tools/gradle-8.14/bin/gradle" "$@"
fi
exec "$ROOT/gradlew" "$@"
