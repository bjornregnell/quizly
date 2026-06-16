#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

api_port=8095
spa_port="${SPA_PORT:-8096}"
server_jar="server/target/scala-3.9.0-RC1/quizly.jar"
client_js="client/target/scala-3.9.0-RC1/quizly-client-fastopt/main.js"
client_map="$client_js.map"
client_index="client/index.html"
static_dir="$(dirname "$server_jar")"

api_pid=""

cleanup() {
  if [[ -n "$api_pid" ]] && kill -0 "$api_pid" 2>/dev/null; then
    kill "$api_pid"
  fi
}

trap cleanup EXIT INT TERM

sbt --client clean
sbt --client server/assembly
sbt --client client/fastLinkJS

if [[ ! -f "$server_jar" ]]; then
  echo "Could not find assembled server jar at $server_jar" >&2
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

cp "$client_index" "$static_dir/index.html"
cp "$client_js" "$static_dir/main.js"
if [[ -f "$client_map" ]]; then
  cp "$client_map" "$static_dir/main.js.map"
fi

if curl -sS --max-time 1 "http://localhost:$api_port/api/quizzes" >/dev/null 2>&1; then
  echo "Something is already serving the Quiz API on http://localhost:$api_port" >&2
  exit 1
fi

if curl -sS --max-time 1 "http://localhost:$spa_port/quizly" >/dev/null 2>&1; then
  echo "Something is already serving the Quiz SPA on http://localhost:$spa_port" >&2
  exit 1
fi

PORT="$api_port" SPA_PORT="$spa_port" STATIC_DIR="$static_dir" java -jar "$server_jar" &
api_pid="$!"

for _ in {1..50}; do
  if curl -fsS "http://localhost:$api_port/api/quizzes" >/dev/null 2>&1 &&
    curl -fsS "http://localhost:$spa_port/quizly/" >/dev/null 2>&1; then
    break
  fi
  sleep 0.2
done

if ! curl -fsS "http://localhost:$api_port/api/quizzes" >/dev/null 2>&1; then
  echo "Quiz API did not start on http://localhost:$api_port" >&2
  exit 1
fi

if ! curl -fsS "http://localhost:$spa_port/quizly/" >/dev/null 2>&1; then
  echo "Quiz SPA did not start on http://localhost:$spa_port/quizly" >&2
  exit 1
fi

echo "Quiz API: http://localhost:$api_port/api/quizzes"
echo "Quiz SPA: http://localhost:$spa_port/quizly"
echo "Press Ctrl-C to stop both servers."

wait "$api_pid"
