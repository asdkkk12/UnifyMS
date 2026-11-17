#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"
# Build the classpath from Maven's resolved runtime dependencies, not a global jar wildcard.
mvn -q -DskipTests compile org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/tuning-classpath.txt
exec java -cp "target/classes:$(cat target/tuning-classpath.txt)" tuning.TuningCli "$@"
