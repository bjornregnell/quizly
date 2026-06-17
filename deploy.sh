#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

jar_path="server/target/scala-3.9.0-RC1/quizly.jar"
client_js="client/target/scala-3.9.0-RC1/quizly-client-fastopt/main.js"
client_map="$client_js.map"
client_index="client/index.html"
remote_host="bjornix"
remote_app_dir="/home/bjornr/quizly"
remote_jar="$remote_app_dir/quizly.jar"
screen_name="quizly"
api_port="${API_PORT:-8095}"
spa_port="${SPA_PORT:-8096}"

sbt --client server/assembly
sbt --client client/fastLinkJS

if [[ ! -f "$jar_path" ]]; then
  echo "Could not find assembled jar at $jar_path" >&2
  exit 1
fi

if [[ ! -f "$client_index" ]]; then
  echo "Could not find SPA index at $client_index" >&2
  exit 1
fi

if [[ ! -f "$client_js" ]]; then
  echo "Could not find generated client JS at $client_js" >&2
  exit 1
fi

files=("$jar_path" "$client_index" "$client_js")
if [[ -f "$client_map" ]]; then
  files+=("$client_map")
fi

ssh "$remote_host" "set -e; mkdir -p '$remote_app_dir'"
scp "${files[@]}" "$remote_host:$remote_app_dir/"
ssh "$remote_host" "set -e; screen -S $screen_name -X quit || true; screen -dmS $screen_name java -jar $remote_jar --api-port $api_port --spa-port $spa_port --static-dir $remote_app_dir; screen -ls"
