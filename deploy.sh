#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

jar_path="target/out/jvm/scala-3.9.0-RC1/quizly/quizly.jar"
remote_host="bjornix"
remote_jar="/home/bjornr/quizly/quizly.jar"
remote_path="$remote_host:$remote_jar"
screen_name="quizly"

sbt assembly

if [[ ! -f "$jar_path" ]]; then
  echo "Could not find assembled jar at $jar_path" >&2
  exit 1
fi

scp "$jar_path" "$remote_path"
ssh "$remote_host" "set -e; screen -S $screen_name -X quit || true; screen -dmS $screen_name java -jar $remote_jar; screen -ls"
