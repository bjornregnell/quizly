#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

api_port="${API_PORT:-${PORT:-8095}}"
spa_port="${SPA_PORT:-8096}"
mode="${QUIZLY_REBUILD_MODE:-run}"
server_jar="server/target/scala-3.9.0-RC1/quizly.jar"
server_target="server/target/scala-3.9.0-RC1"
server_classpath_file="server/target/server-classpath.txt"
client_js="client/target/scala-3.9.0-RC1/quizly-client-fastopt/main.js"
client_map="$client_js.map"
client_index="client/index.html"
static_dir="$server_target"
runtime_dir="${QUIZLY_RUNTIME_DIR:-tmp}"
pid_file="$runtime_dir/quizly-pid"
log_file="$runtime_dir/quizly.log"
run_in_background="${QUIZLY_BACKGROUND:-0}"
server_pid=""

cleanup_pid_file() {
  if [[ "$run_in_background" == "1" || -z "$server_pid" || ! -f "$pid_file" ]]; then
    return
  fi

  local current_pid
  current_pid="$(<"$pid_file")"
  if [[ "$current_pid" == "$server_pid" ]]; then
    rm -f "$pid_file"
  fi
}

run_sbt() {
  if command -v setsid >/dev/null 2>&1; then
    setsid sbt --client "$@"
  else
    sbt --client "$@"
  fi
}

stop_pid() {
  local pid="$1"
  local reason="$2"

  if ! kill -0 "$pid" 2>/dev/null; then
    return
  fi

  echo "Stopping Quizly server pid $pid ($reason)"
  kill "$pid" 2>/dev/null || true

  for _ in {1..50}; do
    if ! kill -0 "$pid" 2>/dev/null; then
      return
    fi
    sleep 0.1
  done

  echo "Quizly server pid $pid did not stop cleanly; forcing it"
  kill -9 "$pid" 2>/dev/null || true
}

stop_pid_file_server() {
  if [[ ! -f "$pid_file" ]]; then
    return
  fi

  local pid
  pid="$(<"$pid_file")"
  if [[ "$pid" =~ ^[0-9]+$ ]]; then
    stop_pid "$pid" "$pid_file"
  fi
  rm -f "$pid_file"
}

quizly_pid_on_port() {
  local port="$1"
  local pid
  local command

  while read -r pid; do
    if [[ -z "$pid" ]]; then
      continue
    fi

    command="$(ps -p "$pid" -o args= 2>/dev/null || true)"
    case "$command" in
      *"$server_jar"*|*quizly.jar*|*quizly.server.QuizServer*)
        echo "$pid"
        ;;
    esac
  done < <(listening_pids_on_port "$port")
}

listening_pids_on_port() {
  local port="$1"

  if command -v lsof >/dev/null 2>&1; then
    lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true
  elif command -v fuser >/dev/null 2>&1; then
    fuser -n tcp "$port" 2>/dev/null | tr ' ' '\n' || true
  fi
}

stop_port_server() {
  local port="$1"
  local pids
  pids="$(quizly_pid_on_port "$port" | sort -u)"

  if [[ -z "$pids" ]]; then
    return
  fi

  while read -r pid; do
    stop_pid "$pid" "port $port"
  done <<< "$pids"
}

ensure_port_free() {
  local port="$1"
  local pids
  pids="$(listening_pids_on_port "$port" | sort -u)"

  if [[ -n "$pids" ]]; then
    echo "Port $port is still in use by a non-Quizly process." >&2
    echo "Stop that process or choose another port with API_PORT/SPA_PORT." >&2
    exit 1
  fi
}

mkdir -p "$runtime_dir"
case "$mode" in
  run|assembly)
    ;;
  *)
    echo "Unknown QUIZLY_REBUILD_MODE '$mode'. Use 'run' or 'assembly'." >&2
    exit 1
    ;;
esac

stop_pid_file_server
stop_port_server "$api_port"
stop_port_server "$spa_port"
ensure_port_free "$api_port"
ensure_port_free "$spa_port"

run_sbt client/fastLinkJS

if [[ "$mode" == "assembly" ]]; then
  run_sbt server/assembly
else
  run_sbt server/writeServerClasspath
fi

if [[ ! -f "$client_index" ]]; then
  echo "Could not find SPA index at $client_index" >&2
  exit 1
fi

if [[ ! -f "$client_js" ]]; then
  echo "Could not find generated client JS at $client_js" >&2
  exit 1
fi

mkdir -p "$static_dir"
cp "$client_index" "$static_dir/index.html"
cp "$client_js" "$static_dir/main.js"
if [[ -f "$client_map" ]]; then
  cp "$client_map" "$static_dir/main.js.map"
fi

if [[ "$mode" == "assembly" ]]; then
  if [[ ! -f "$server_jar" ]]; then
    echo "Could not find assembled server jar at $server_jar" >&2
    exit 1
  fi

  if [[ "$run_in_background" == "1" ]] && command -v setsid >/dev/null 2>&1; then
    setsid java -jar "$server_jar" \
      --api-port "$api_port" \
      --spa-port "$spa_port" \
      --static-dir "$static_dir" >"$log_file" 2>&1 &
  else
    java -jar "$server_jar" \
      --api-port "$api_port" \
      --spa-port "$spa_port" \
      --static-dir "$static_dir" >"$log_file" 2>&1 &
  fi
else
  if [[ ! -f "$server_classpath_file" ]]; then
    echo "Could not find server classpath at $server_classpath_file" >&2
    exit 1
  fi

  server_classpath="$(<"$server_classpath_file")"
  if [[ "$run_in_background" == "1" ]] && command -v setsid >/dev/null 2>&1; then
    setsid java -cp "$server_classpath" quizly.server.QuizServer \
      --api-port "$api_port" \
      --spa-port "$spa_port" \
      --static-dir "$static_dir" >"$log_file" 2>&1 &
  else
    java -cp "$server_classpath" quizly.server.QuizServer \
      --api-port "$api_port" \
      --spa-port "$spa_port" \
      --static-dir "$static_dir" >"$log_file" 2>&1 &
  fi
fi
server_pid="$!"
echo "$server_pid" > "$pid_file"
trap cleanup_pid_file EXIT

for _ in {1..50}; do
  if curl -fsS "http://localhost:$api_port/api/quizzes" >/dev/null 2>&1 &&
    curl -fsS "http://localhost:$spa_port/quizly/" >/dev/null 2>&1; then
    break
  fi
  sleep 0.2
done

if ! curl -fsS "http://localhost:$api_port/api/quizzes" >/dev/null 2>&1; then
  echo "Quiz API did not start on http://localhost:$api_port" >&2
  echo "See $log_file" >&2
  exit 1
fi

if ! curl -fsS "http://localhost:$spa_port/quizly/" >/dev/null 2>&1; then
  echo "Quiz SPA did not start on http://localhost:$spa_port/quizly" >&2
  echo "See $log_file" >&2
  exit 1
fi

echo "Quizly rebuilt and running."
echo "Mode: $mode"
echo "SPA: http://localhost:$spa_port/quizly"
echo "API: http://localhost:$api_port/api/quizzes"
echo "PID: $server_pid"
echo "Log: $log_file"

if [[ "$run_in_background" == "1" ]]; then
  exit 0
fi

echo "Press Ctrl+C to stop the server."
wait "$server_pid"
