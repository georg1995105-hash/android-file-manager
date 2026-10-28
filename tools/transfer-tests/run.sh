#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
"${JAVA_HOME:?Set JAVA_HOME to a full JDK 21}/bin/javac" --release 17 -d "$classes" \
  app/src/main/java/io/personal/filemanager/data/transfer/*.java \
  tools/transfer-tests/*.java
"$JAVA_HOME/bin/java" -cp "$classes" IncomingTransferCheck
"$JAVA_HOME/bin/java" -cp "$classes" io.personal.filemanager.data.transfer.HttpTransportCheck
