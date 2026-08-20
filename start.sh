#!/usr/bin/env bash
# Repeatable local recovery entry point for TestHub.
set -euo pipefail
umask 077

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
state_dir="$root_dir/.testhub"
api_log="$state_dir/platform-api.log"
web_log="$state_dir/web.log"
docker_log="$state_dir/docker-compose.log"
api_pid_file="$state_dir/platform-api.pid"
web_pid_file="$state_dir/web.pid"
lock_dir="$state_dir/start.lock"
with_api_runner=false
docker_repaired=false
runner_warning=false

note() { printf '%s\n' "$*"; }
warn() { printf 'Warning: %s\n' "$*" >&2; }
fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Usage: ./start.sh [--with-api-runner]

Recovers the local TestHub development stack. Every run safely restarts the
Platform API and Web UI, reuses healthy infrastructure, and verifies the CSRF
login bootstrap path before reporting success.

Options:
  --with-api-runner  Also start the optional API Runner container.
  -h, --help         Show this help message.

The Web UI is available at http://127.0.0.1:5173. Runtime logs and process IDs
are kept in .testhub/ (which is ignored by Git).
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-api-runner) with_api_runner=true ;;
    -h|--help) usage; exit 0 ;;
    *) fail "unknown option: $1" ;;
  esac
  shift
done

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

find_java21() {
  local candidate discovered
  local candidates=()

  [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME")
  if [[ -x /usr/libexec/java_home ]]; then
    discovered="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    [[ -n "$discovered" ]] && candidates+=("$discovered")
  fi
  candidates+=(
    "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    "/opt/homebrew/opt/openjdk@21"
    "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    "/usr/local/opt/openjdk@21"
  )

  for candidate in "${candidates[@]}"; do
    if [[ -x "$candidate/bin/java" ]] && "$candidate/bin/java" -version 2>&1 | grep -Eq 'version "21([.]|")|openjdk 21'; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

acquire_lock() {
  local owner=""
  mkdir -p "$state_dir"
  if mkdir "$lock_dir" 2>/dev/null; then
    printf '%s\n' "$$" > "$lock_dir/pid"
    return
  fi

  [[ -f "$lock_dir/pid" ]] && owner="$(sed -n '1p' "$lock_dir/pid" 2>/dev/null || true)"
  if [[ "$owner" =~ ^[0-9]+$ ]] && kill -0 "$owner" 2>/dev/null; then
    fail "another start.sh is already running (PID $owner)"
  fi

  rm -f "$lock_dir/pid"
  rmdir "$lock_dir" 2>/dev/null || fail "cannot clear stale startup lock: $lock_dir"
  mkdir "$lock_dir" || fail "cannot acquire startup lock: $lock_dir"
  printf '%s\n' "$$" > "$lock_dir/pid"
}

release_lock() {
  rm -f "$lock_dir/pid" 2>/dev/null || true
  rmdir "$lock_dir" 2>/dev/null || true
}

mysql_ready() {
  node -e '
    const net = require("node:net")
    const socket = net.createConnection({ host: "127.0.0.1", port: 13307 })
    const finish = (ok) => { socket.destroy(); process.exit(ok ? 0 : 1) }
    socket.setTimeout(2000)
    socket.once("data", (data) => finish(data.length >= 5))
    socket.once("timeout", () => finish(false))
    socket.once("error", () => finish(false))
    socket.once("end", () => finish(false))
  ' >/dev/null 2>&1
}

redis_ready() {
  TESTHUB_START_REDIS_PASSWORD="${TESTHUB_REDIS_PASSWORD:-testhub_redis_password}" node -e '
    const net = require("node:net")
    const password = process.env.TESTHUB_START_REDIS_PASSWORD
    const auth = `*2\r\n$4\r\nAUTH\r\n$${Buffer.byteLength(password)}\r\n${password}\r\n`
    const ping = "*1\r\n$4\r\nPING\r\n"
    const socket = net.createConnection({ host: "127.0.0.1", port: 6380 })
    let response = ""
    const finish = (ok) => { socket.destroy(); process.exit(ok ? 0 : 1) }
    socket.setTimeout(2000)
    socket.once("connect", () => socket.write(auth + ping))
    socket.on("data", (data) => {
      response += data.toString("utf8")
      if (response.includes("+PONG\r\n")) finish(true)
      else if (response.includes("-ERR")) finish(false)
    })
    socket.once("timeout", () => finish(false))
    socket.once("error", () => finish(false))
    socket.once("end", () => finish(false))
  ' >/dev/null 2>&1
}

minio_ready() {
  curl --silent --fail --max-time 2 http://127.0.0.1:9000/minio/health/live >/dev/null 2>&1
}

infrastructure_ready() {
  mysql_ready && redis_ready && minio_ready
}

wait_for_infrastructure() {
  local deadline=$((SECONDS + 120))
  while (( SECONDS < deadline )); do
    infrastructure_ready && return 0
    sleep 2
  done
  return 1
}

ensure_compose_available() {
  require_command docker
  docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required"
}

compose_core() {
  note "Starting MySQL, Redis and MinIO..."
  docker compose up -d mysql redis minio >>"$docker_log" 2>&1
}

compose_runner() {
  note "Starting optional API Runner..."
  docker compose --profile runner up -d api-runner >>"$docker_log" 2>&1
}

docker_storage_error() {
  grep -Eqi 'input/output error|temporary lease|metadata[.]db|overlay2' "$docker_log" 2>/dev/null
}

docker_recoverable_error() {
  docker_storage_error || grep -Eqi 'cannot connect to the Docker daemon|is the docker daemon running|dial unix.*docker[.]sock' "$docker_log" 2>/dev/null
}

sync_colima_instance_disk() {
  local existing_disk="$1" colima_data_dir instance_config
  colima_data_dir="${COLIMA_HOME:-${HOME}/.colima}"
  instance_config="$colima_data_dir/_lima/colima/colima.yaml"
  [[ -f "$instance_config" ]] || return 1

  TESTHUB_COLIMA_CONFIG="$instance_config" TESTHUB_COLIMA_DISK="$existing_disk" node -e '
    const fs = require("node:fs")
    const config = process.env.TESTHUB_COLIMA_CONFIG
    const disk = process.env.TESTHUB_COLIMA_DISK
    const before = fs.readFileSync(config, "utf8")
    const matches = before.match(/^disk:\s*[0-9]+\s*$/gm) ?? []
    if (matches.length !== 1) process.exit(2)
    const after = before.replace(/^disk:\s*[0-9]+\s*$/m, `disk: ${disk}`)
    if (after === before) process.exit(0)
    const temporary = `${config}.testhub-${process.pid}.tmp`
    const mode = fs.statSync(config).mode
    fs.writeFileSync(temporary, after, { mode })
    fs.renameSync(temporary, config)
  '
}

repair_colima() {
  local context deadline existing_disk
  [[ "$docker_repaired" == false ]] || return 1
  command -v colima >/dev/null 2>&1 || return 1
  context="$(docker context show 2>/dev/null || true)"
  [[ "$context" == "colima" ]] || return 1

  docker_repaired=true
  warn "Docker/Colima runtime or storage metadata is unavailable; restarting Colima once and retrying."
  if ! colima restart >>"$docker_log" 2>&1; then
    existing_disk="$(sed -nE 's/.*disk from ([0-9]+)GiB to [0-9]+GiB.*/\1/p' "$docker_log" | tail -n 1)"
    if grep -qi 'Shrinking is currently unavailable' "$docker_log" && [[ "$existing_disk" =~ ^[0-9]+$ ]]; then
      warn "Colima configuration requested an unsafe disk shrink; keeping the existing ${existing_disk}GiB disk."
      if ! colima start --disk "$existing_disk" >>"$docker_log" 2>&1; then
        # Colima 0.8.x can retain a stale disk size in its instance metadata
        # even after saving the corrected user configuration.
        sync_colima_instance_disk "$existing_disk" || return 1
        colima start >>"$docker_log" 2>&1 || return 1
      fi
    else
      return 1
    fi
  fi

  deadline=$((SECONDS + 120))
  while (( SECONDS < deadline )); do
    docker info >/dev/null 2>&1 && return 0
    sleep 2
  done
  return 1
}

show_docker_failure() {
  warn "Docker could not recover the required local infrastructure."
  warn "Details: $docker_log"
  if docker_storage_error; then
    warn "The Docker VM has a disk/containerd I/O fault. Run 'colima stop', repair free disk space, then rerun this script. The script never deletes Docker data automatically."
  fi
  tail -n 20 "$docker_log" >&2 2>/dev/null || true
}

ensure_infrastructure() {
  if infrastructure_ready; then
    note "Local infrastructure is healthy; reusing it without querying Docker metadata."
    return 0
  fi

  ensure_compose_available
  : > "$docker_log"
  if ! compose_core; then
    if docker_recoverable_error && repair_colima; then
      : > "$docker_log"
      compose_core || {
        show_docker_failure
        return 1
      }
    else
      show_docker_failure
      return 1
    fi
  fi

  if ! wait_for_infrastructure; then
    warn "MySQL, Redis or MinIO did not become ready within 120 seconds."
    show_docker_failure
    return 1
  fi
  note "Local infrastructure is ready."
}

ensure_api_runner() {
  ensure_compose_available
  : > "$docker_log"
  if compose_runner; then
    return 0
  fi

  if docker_recoverable_error && repair_colima; then
    : > "$docker_log"
    if ! compose_core || ! wait_for_infrastructure; then
      show_docker_failure
      return 1
    fi
    compose_runner && return 0
  fi

  warn "API Runner could not be started; Platform API and Web UI recovery will continue."
  show_docker_failure
  return 1
}

process_alive() {
  [[ "$1" =~ ^[0-9]+$ ]] && kill -0 "$1" 2>/dev/null
}

process_cwd() {
  lsof -a -p "$1" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1
}

process_command() {
  ps -p "$1" -o command= 2>/dev/null || true
}

managed_process() {
  local pid="$1" kind="$2" cwd command
  process_alive "$pid" || return 1
  cwd="$(process_cwd "$pid")"
  command="$(process_command "$pid")"
  case "$cwd" in
    "$root_dir"|"$root_dir"/*) ;;
    *) return 1 ;;
  esac
  case "$kind" in
    api) [[ "$command" == *spring-boot:run* || "$command" == *TestHubApplication* || "$command" == *services/platform-api* ]] ;;
    web) [[ "$command" == *vite* ]] ;;
    *) return 1 ;;
  esac
}

listener_pid() {
  lsof -nP "-iTCP:$1" -sTCP:LISTEN -t 2>/dev/null | head -n 1 || true
}

signal_tree() {
  local pid="$1" signal="$2" children child
  children="$(pgrep -P "$pid" 2>/dev/null || true)"
  for child in $children; do
    signal_tree "$child" "$signal"
  done
  kill "-$signal" "$pid" 2>/dev/null || true
}

stop_managed_service() {
  local name="$1" kind="$2" port="$3" pid_file="$4"
  local pid="" active="" deadline

  [[ -f "$pid_file" ]] && pid="$(sed -n '1p' "$pid_file" 2>/dev/null || true)"
  if process_alive "$pid"; then
    managed_process "$pid" "$kind" || fail "$name PID $pid is not owned by this TestHub checkout; refusing to stop it"
    note "Stopping $name (PID $pid)..."
    signal_tree "$pid" TERM
  fi

  active="$(listener_pid "$port")"
  if [[ -n "$active" ]] && [[ "$active" != "$pid" ]]; then
    managed_process "$active" "$kind" || fail "port $port is occupied by PID $active ($(process_command "$active")); refusing to stop an unrelated process"
    note "Stopping remaining $name listener (PID $active)..."
    signal_tree "$active" TERM
  fi

  deadline=$((SECONDS + 15))
  while (( SECONDS < deadline )); do
    [[ -z "$(listener_pid "$port")" ]] && break
    sleep 1
  done

  active="$(listener_pid "$port")"
  if [[ -n "$active" ]]; then
    managed_process "$active" "$kind" || fail "port $port is still occupied by unrelated PID $active"
    warn "$name did not stop gracefully; terminating PID $active."
    signal_tree "$active" KILL
  fi
  rm -f "$pid_file"
}

start_platform_api() {
  local java_home="$1"
  note "Starting Platform API..."
  : > "$api_log"
  (
    cd "$root_dir"
    nohup env JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      ./mvnw -pl services/platform-api spring-boot:run </dev/null >"$api_log" 2>&1 &
    printf '%s\n' "$!" > "$api_pid_file"
  )
}

start_web_ui() {
  note "Starting Web UI..."
  : > "$web_log"
  (
    cd "$root_dir"
    nohup "$root_dir/node_modules/.bin/vite" --host 0.0.0.0 --strictPort </dev/null >"$web_log" 2>&1 &
    printf '%s\n' "$!" > "$web_pid_file"
  )
}

wait_for_http_process() {
  local url="$1" name="$2" log_file="$3" pid_file="$4" deadline pid
  deadline=$((SECONDS + 180))
  while (( SECONDS < deadline )); do
    curl --silent --fail --max-time 2 "$url" >/dev/null 2>&1 && return 0
    pid="$(sed -n '1p' "$pid_file" 2>/dev/null || true)"
    if ! process_alive "$pid"; then
      warn "$name exited before it became ready."
      tail -n 40 "$log_file" >&2 2>/dev/null || true
      return 1
    fi
    sleep 2
  done
  warn "$name did not become ready within 180 seconds; see $log_file"
  tail -n 40 "$log_file" >&2 2>/dev/null || true
  return 1
}

csrf_ready() {
  curl --silent --fail --max-time 3 "$1" 2>/dev/null | grep -q '"headerName"'
}

require_command curl
require_command lsof
require_command node
require_command pgrep

java_home="$(find_java21 || true)"
[[ -n "$java_home" ]] || fail "JDK 21 is required. Install it, or set JAVA_HOME to a JDK 21 installation."

acquire_lock
trap release_lock EXIT
trap 'exit 130' INT TERM
cd "$root_dir"

ensure_infrastructure || fail "local infrastructure recovery failed; correct the reported Docker/disk fault and rerun ./start.sh"

if [[ "$with_api_runner" == true ]]; then
  ensure_api_runner || runner_warning=true
  infrastructure_ready || ensure_infrastructure || fail "infrastructure became unavailable while recovering API Runner"
fi

stop_managed_service "Platform API" api 8080 "$api_pid_file"
stop_managed_service "Web UI" web 5173 "$web_pid_file"

if [[ ! -d node_modules ]]; then
  require_command npx
  note "Installing Web dependencies..."
  npx pnpm@10.13.1 install --frozen-lockfile
fi

start_platform_api "$java_home"
wait_for_http_process http://127.0.0.1:8080/actuator/health "Platform API" "$api_log" "$api_pid_file" \
  || fail "Platform API recovery failed"
csrf_ready http://127.0.0.1:8080/api/v1/auth/csrf \
  || fail "Platform API is running, but the CSRF login bootstrap endpoint is unavailable; see $api_log"

start_web_ui
wait_for_http_process http://127.0.0.1:5173/ "Web UI" "$web_log" "$web_pid_file" \
  || fail "Web UI recovery failed"
csrf_ready http://127.0.0.1:5173/api/v1/auth/csrf \
  || fail "Web UI proxy cannot reach the CSRF login bootstrap endpoint; see $web_log and $api_log"

note "TestHub recovery checks passed."
note "Web UI:  http://127.0.0.1:5173"
note "API:     http://127.0.0.1:8080/actuator/health"
note "Logs:    $state_dir"
if [[ "$runner_warning" == true ]]; then
  warn "The optional API Runner is unavailable, but login and the TestHub control plane are ready. Rerun with --with-api-runner after repairing Docker."
fi
