#!/usr/bin/env bash
# Local Web development lifecycle; never deletes databases or Docker volumes.
set -euo pipefail
umask 077

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
state_dir="${TESTHUB_START_STATE_DIR:-$root_dir/.testhub}"
[[ "$state_dir" == /* ]] || state_dir="$root_dir/$state_dir"
api_port="${TESTHUB_START_API_PORT:-8080}"
web_port="${TESTHUB_START_WEB_PORT:-5173}"
api_log="$state_dir/platform-api.log"
web_log="$state_dir/web.log"
docker_log="$state_dir/docker-compose.log"
api_pid_file="$state_dir/platform-api.pid"
web_pid_file="$state_dir/web.pid"
config_file="$state_dir/start-config.sha256"
lock_dir="$state_dir/start.lock"
dependency_stamp="$root_dir/node_modules/.testhub-start-deps"
metadata="$root_dir/scripts/dev/start-metadata.mjs"
action=start
with_api_runner=false
with_registration=false
start_colima=false
new_api=false
new_web=false
containers_tracked=false
containers_before=""
success=false

note() { printf '%s\n' "$*"; }
warn() { printf 'Warning: %s\n' "$*" >&2; }
fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
usage() {
  cat <<'HELP'
Usage: ./start.sh [start|restart|stop|status|doctor] [options]

  start    Start missing services; reuse healthy services (default).
  restart  Preflight dependencies, then restart this checkout's API and Web UI.
  stop     Stop this checkout's API and Web UI; keep infrastructure and data.
  status   Report process ownership and HTTP/CSRF health; exit 1 if not ready.
  doctor   Read-only environment, port, dependency and Docker checks.

Options (start/restart/doctor only):
  --with-registration  Enable registration; bind API and Web UI to loopback.
  --with-api-runner    Also prepare/start the API Runner (execution stays opt-in).
  --start-colima       Allow starting stopped default Colima on start/restart.
  -h, --help           Show this help message.

Node.js 22+, JDK 21, Docker Compose v2 and (for image builds) Buildx are required.
No browser or application window is opened. Logs/PIDs are kept in .testhub/.
See docs/local-development.md for ports, lifecycle and Runner configuration.
HELP
}

if [[ "${1:-}" != -* && $# -gt 0 ]]; then action="$1"; shift; fi
case "$action" in start|restart|stop|status|doctor) ;; *) fail "unknown command: $action" ;; esac
while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-api-runner) with_api_runner=true ;;
    --with-registration) with_registration=true ;;
    --start-colima) start_colima=true ;;
    -h|--help) usage; exit 0 ;;
    *) fail "unknown option: $1" ;;
  esac
  shift
done
if [[ "$action" == stop || "$action" == status ]]; then
  [[ "$with_api_runner$with_registration$start_colima" == falsefalsefalse ]] \
    || fail "startup options are not valid with $action"
fi
for port in "$api_port" "$web_port"; do
  [[ "$port" =~ ^[1-9][0-9]{0,4}$ ]] && (( port <= 65535 )) || fail "invalid TCP port: $port"
done
[[ "$api_port" != "$web_port" ]] || fail "API and Web ports must differ"
api_url="http://127.0.0.1:$api_port"
web_url="http://127.0.0.1:$web_port"

require_command() { command -v "$1" >/dev/null 2>&1 || fail "$1 is required"; }
process_alive() {
  [[ "$1" =~ ^[1-9][0-9]*$ ]] && kill -0 "$1" 2>/dev/null \
    && [[ "$(ps -p "$1" -o stat= 2>/dev/null)" != *Z* ]]
}
read_pid() { if [[ -f "$1" ]]; then sed -n '1p' "$1"; fi; }
listener_pids() { lsof -nP "-iTCP:$1" -sTCP:LISTEN -t 2>/dev/null | sort -u || true; }
managed_process() {
  local pid="$1" kind="$2" cwd command
  process_alive "$pid" || return 1
  cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1 || true)"
  command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  case "$cwd" in "$root_dir"|"$root_dir"/*) ;; *) return 1 ;; esac
  case "$kind" in
    api) [[ "$command" == *spring-boot:run* || "$command" == *TestHubApplication* || "$command" == *services/platform-api* ]] ;;
    web) [[ "$command" == *vite* ]] ;;
    *) return 1 ;;
  esac
}

# Inspect both services before stopping either one. Process command lines may
# contain another application's secrets, so report only its PID and port.
check_service_ownership() {
  local name="$1" kind="$2" port="$3" pid_file="$4" pid
  pid="$(read_pid "$pid_file")"
  if process_alive "$pid"; then
    managed_process "$pid" "$kind" || fail "$name PID file refers to unrelated PID $pid; inspect $pid_file"
  fi
  for pid in $(listener_pids "$port"); do
    managed_process "$pid" "$kind" || fail "port $port is occupied by unrelated PID $pid; nothing has been stopped"
  done
}
check_ports() {
  check_service_ownership "Platform API" api "$api_port" "$api_pid_file"
  check_service_ownership "Web UI" web "$web_port" "$web_pid_file"
}
service_present() { process_alive "$(read_pid "$2")" || [[ -n "$(listener_pids "$1")" ]]; }
http_ready() { curl --noproxy '*' --silent --fail --max-time 3 "$1" >/dev/null 2>&1; }
csrf_ready() {
  curl --noproxy '*' --silent --fail --max-time 3 "$1/api/v1/auth/csrf" 2>/dev/null \
    | node "$metadata" csrf >/dev/null 2>&1
}
api_ready() { http_ready "$api_url/actuator/health" && csrf_ready "$api_url"; }
web_ready() { http_ready "$web_url/" && csrf_ready "$web_url"; }

signal_tree() {
  local pid="$1" signal="$2" child
  for child in $(pgrep -P "$pid" 2>/dev/null || true); do signal_tree "$child" "$signal"; done
  kill "-$signal" "$pid" 2>/dev/null || true
}
stop_service() {
  local name="$1" kind="$2" port="$3" pid_file="$4" pid pids deadline alive
  pids="$(read_pid "$pid_file") $(listener_pids "$port")"
  for pid in $pids; do
    if managed_process "$pid" "$kind"; then
      note "Stopping $name (PID $pid)..."
      signal_tree "$pid" TERM
    fi
  done
  deadline=$((SECONDS + 15))
  while (( SECONDS < deadline )); do
    alive=false
    for pid in $pids; do managed_process "$pid" "$kind" && alive=true; done
    [[ "$alive" == false && -z "$(listener_pids "$port")" ]] && break
    sleep 1
  done
  for pid in $pids $(listener_pids "$port"); do
    if managed_process "$pid" "$kind"; then signal_tree "$pid" KILL; fi
  done
  rm -f "$pid_file"
}

acquire_lock() {
  local owner
  mkdir -p "$state_dir"
  if ! mkdir "$lock_dir" 2>/dev/null; then
    owner="$(read_pid "$lock_dir/pid")"
    # An empty lock may belong to a process between mkdir and writing its PID.
    [[ -n "$owner" ]] || fail "startup lock has no owner yet; retry, or inspect $lock_dir"
    process_alive "$owner" && fail "another start.sh is running (PID $owner)"
    rm -f "$lock_dir/pid"
    rmdir "$lock_dir" && mkdir "$lock_dir" || fail "cannot acquire startup lock: $lock_dir"
  fi
  printf '%s\n' "$$" > "$lock_dir/pid"
}
finish() {
  local result=$? pid
  trap - EXIT INT TERM
  set +e
  if [[ "$success" != true ]]; then
    [[ "$new_web" == false ]] || stop_service "new Web UI" web "$web_port" "$web_pid_file"
    if [[ "$new_api" == true ]]; then
      stop_service "new Platform API" api "$api_port" "$api_pid_file"
      rm -f "$config_file"
    fi
    if [[ "$containers_tracked" == true ]]; then
      # Stop only containers started by this attempt; retain their volumes/data.
      for pid in $(docker compose --profile runner ps --status running -q mysql redis minio api-runner 2>/dev/null); do
        if ! printf '%s\n' "$containers_before" | grep -Fxq "$pid"; then
          docker stop "$pid" >>"$docker_log" 2>&1 || warn "could not stop newly started container $pid"
        fi
      done
    fi
  fi
  rm -f "$lock_dir/pid"
  rmdir "$lock_dir" 2>/dev/null || true
  exit "$result"
}

find_java21() {
  local candidate discovered
  local candidates=()
  [[ -z "${JAVA_HOME:-}" ]] || candidates+=("$JAVA_HOME")
  if [[ -x /usr/libexec/java_home ]]; then
    discovered="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    [[ -z "$discovered" ]] || candidates+=("$discovered")
  fi
  if command -v java >/dev/null 2>&1; then
    discovered="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.home = //p' | head -n 1)"
    [[ -z "$discovered" ]] || candidates+=("$discovered")
  fi
  candidates+=("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" "/opt/homebrew/opt/openjdk@21"
    "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" "/usr/local/opt/openjdk@21")
  for candidate in "${candidates[@]}"; do
    if [[ -x "$candidate/bin/java" && -x "$candidate/bin/javac" ]] \
      && "$candidate/bin/java" -version 2>&1 | grep -Eq 'version "21([.]|")|openjdk 21'; then
      printf '%s\n' "$candidate"; return 0
    fi
  done
  return 1
}
check_environment() {
  require_command npx
  node -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 22 ? 0 : 1)' || fail "Node.js 22+ is required"
  java_home="$(find_java21 || true)"
  [[ -n "$java_home" ]] || fail "JDK 21 (including javac) is required; set JAVA_HOME to its installation"
  [[ -x "$root_dir/mvnw" ]] || fail "Maven wrapper is not executable; run chmod +x mvnw"
  dependency_hash="$(node "$metadata" dependencies)"
  note "Node $(node --version); JDK 21: $java_home"
}
dependencies_current() {
  [[ -x "$root_dir/node_modules/.bin/vite" && -f "$dependency_stamp" ]] \
    && [[ "$(cat "$dependency_stamp")" == "$dependency_hash" ]]
}
ensure_dependencies() {
  if ! dependencies_current; then
    note "Synchronizing dependencies with pnpm 10.13.1 (frozen lockfile)..."
    npx --yes pnpm@10.13.1 install --frozen-lockfile >>"$state_dir/dependencies.log" 2>&1 \
      || fail "dependency install failed; existing services were preserved; see $state_dir/dependencies.log"
    [[ -x "$root_dir/node_modules/.bin/vite" ]] || fail "dependency install did not provide Vite"
    printf '%s\n' "$dependency_hash" > "$dependency_stamp"
  fi
}

infrastructure_ready() { node "$metadata" infrastructure >/dev/null 2>&1; }
check_docker() {
  if [[ -z "${COMPOSE_FILE:-}" ]] && [[ -n "${TESTHUB_DB_URL:-}${TESTHUB_REDIS_HOST:-}${TESTHUB_REDIS_PORT:-}${TESTHUB_MINIO_ENDPOINT:-}" || "${TESTHUB_STORAGE_TYPE:-minio}" != minio ]]; then
    fail "configured external infrastructure is unavailable (or a Runner was requested); start it separately, or supply a matching COMPOSE_FILE"
  fi
  require_command docker
  docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required"
  if ! docker info >/dev/null 2>&1; then
    if [[ "$action" != doctor && "$start_colima" == true ]] \
      && [[ "$(docker context show 2>/dev/null)" == colima ]] && command -v colima >/dev/null 2>&1; then
      colima status >/dev/null 2>&1 && fail "Colima is running but Docker is unavailable; inspect Colima without restarting shared services"
      note "Starting Colima in the background..."
      colima start >>"$docker_log" 2>&1 || fail "Colima failed to start; see $docker_log (no VM configuration was changed by this script)"
    fi
    docker info >/dev/null 2>&1 || fail "Docker daemon is unavailable; start Docker or run colima start (or use --start-colima)"
  fi
  docker compose --profile runner config --quiet || fail "invalid Compose configuration"
  image_plan="$(docker compose --profile runner config --format json | node "$metadata" images)"
}
prepare_images() {
  local service image build
  while IFS=$'\t' read -r service image build; do
    [[ "$service" != api-runner || "$with_api_runner" == true ]] || continue
    if ! docker image inspect "$image" >/dev/null 2>&1; then
      if [[ "$build" == true ]]; then
        docker buildx version >/dev/null 2>&1 || fail "Docker Buildx is required to build $service; install the Docker Buildx plugin"
      fi
      if [[ "$action" == doctor ]]; then
        note "$service image missing; start will prepare it (network access may be needed)."
      else
        note "Preparing $service image before changing any running services..."
        if [[ "$build" == true ]]; then
          docker compose --profile runner build "$service" >>"$docker_log" 2>&1 || fail "$service image build failed; see $docker_log"
        else
          docker compose pull "$service" >>"$docker_log" 2>&1 || fail "$service image pull failed; see $docker_log"
        fi
      fi
    fi
  done <<< "$image_plan"
}
ensure_infrastructure() {
  local deadline
  if infrastructure_ready && [[ "$with_api_runner" == false ]]; then
    note "Healthy configured infrastructure reused."
    return
  fi
  check_docker
  prepare_images
  containers_before="$(docker compose --profile runner ps --status running -q mysql redis minio api-runner)"
  containers_tracked=true
  docker compose up -d --no-recreate --no-build --pull never mysql redis minio >>"$docker_log" 2>&1 \
    || fail "infrastructure startup failed; see $docker_log"
  deadline=$((SECONDS + 120))
  until infrastructure_ready; do
    (( SECONDS < deadline )) || fail "infrastructure was not ready within 120 seconds; see $docker_log"
    sleep 2
  done
  if [[ "$with_api_runner" == true ]]; then
    docker compose --profile runner up -d --no-recreate --no-build --pull never api-runner >>"$docker_log" 2>&1 \
      || fail "requested API Runner could not start; see $docker_log"
    note "API Runner container started; real execution still requires explicit configuration and target authorization."
  fi
}

wait_for_service() {
  local kind="$1" pid_file="$2" log_file="$3" deadline
  deadline=$((SECONDS + 180))
  while (( SECONDS < deadline )); do
    if "${kind}_ready"; then return 0; fi
    process_alive "$(read_pid "$pid_file")" || fail "$kind exited before readiness; see $log_file"
    sleep 2
  done
  fail "$kind HTTP/CSRF checks timed out after 180 seconds; see $log_file"
}
start_api() {
  note "Starting Platform API..."
  new_api=true
  nohup env JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" SERVER_PORT="$api_port" \
    SERVER_ADDRESS="$api_host" TESTHUB_REGISTRATION_ENABLED="$registration" \
    TESTHUB_CORS_ALLOWED_ORIGINS="${TESTHUB_CORS_ALLOWED_ORIGINS:-http://localhost:$web_port,http://127.0.0.1:$web_port}" \
    ./mvnw -pl services/platform-api spring-boot:run </dev/null >"$api_log" 2>&1 &
  printf '%s\n' "$!" > "$api_pid_file"
  wait_for_service api "$api_pid_file" "$api_log"
  printf '%s\n' "$config_hash" > "$config_file"
}
start_web() {
  note "Starting Web UI..."
  new_web=true
  nohup env TESTHUB_VITE_API_ORIGIN="$api_url" "$root_dir/node_modules/.bin/vite" \
    --host "$web_host" --port "$web_port" --strictPort </dev/null >"$web_log" 2>&1 &
  printf '%s\n' "$!" > "$web_pid_file"
  wait_for_service web "$web_pid_file" "$web_log"
}

cd "$root_dir"
require_command lsof
require_command pgrep
require_command ps
if [[ "$action" == stop ]]; then
  acquire_lock
  trap finish EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  check_ports
  stop_service "Web UI" web "$web_port" "$web_pid_file"
  stop_service "Platform API" api "$api_port" "$api_pid_file"
  rm -f "$config_file"
  success=true
  note "API/Web stopped. Infrastructure, Runner containers and all workspace data were retained."
  exit 0
fi
require_command node
require_command curl
check_ports
if [[ "$action" == status ]]; then
  ready=true
  if service_present "$api_port" "$api_pid_file" && api_ready; then note "API: ready ($api_url)"; else note "API: stopped or unhealthy"; ready=false; fi
  if service_present "$web_port" "$web_pid_file" && web_ready; then note "Web: ready ($web_url/login)"; else note "Web: stopped or unhealthy"; ready=false; fi
  note "Logs: $state_dir"
  [[ "$ready" == true ]]
  exit $?
fi

check_environment
registration="${TESTHUB_REGISTRATION_ENABLED:-false}"
[[ "$with_registration" == false ]] || registration=true
case "$registration" in
  true|TRUE) registration=true ;;
  false|FALSE) registration=false ;;
  *) fail "TESTHUB_REGISTRATION_ENABLED must be true or false" ;;
esac
api_host="${SERVER_ADDRESS:-0.0.0.0}"
web_host=0.0.0.0
if [[ "$registration" == true ]]; then api_host=127.0.0.1; web_host=127.0.0.1; fi
config_hash="$(node "$metadata" config "$registration" "$api_host" "$web_host" "$api_port" "$web_port" "$java_home")"

if [[ "$action" == doctor ]]; then
  if dependencies_current; then note "Web dependencies: current."; else note "Web dependencies: start will run frozen-lockfile install."; fi
  if infrastructure_ready && [[ "$with_api_runner" == false ]]; then
    note "Infrastructure: healthy."
  else
    check_docker
    prepare_images
  fi
  note "Preflight passed. First startup may still need Maven/image downloads."
  exit 0
fi

acquire_lock
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# Check again under the lifecycle lock before changing processes or dependencies.
check_ports
if [[ "$action" == start ]] && { service_present "$api_port" "$api_pid_file" || service_present "$web_port" "$web_pid_file"; }; then
  [[ -f "$config_file" && "$(cat "$config_file")" == "$config_hash" ]] \
    || fail "running services have different/unknown startup options; use ./start.sh restart with the desired options"
  if service_present "$api_port" "$api_pid_file"; then api_ready || fail "API is unhealthy; inspect $api_log, then use restart"; fi
  if service_present "$web_port" "$web_pid_file"; then web_ready || fail "Web is unhealthy; inspect $web_log, then use restart"; fi
  dependencies_current || fail "dependencies changed while services are running; use ./start.sh restart with the desired options"
fi
ensure_dependencies
ensure_infrastructure
check_ports
if [[ "$action" == restart ]]; then
  stop_service "Web UI" web "$web_port" "$web_pid_file"
  stop_service "Platform API" api "$api_port" "$api_pid_file"
fi
if service_present "$api_port" "$api_pid_file"; then note "Healthy API reused."; else start_api; fi
if service_present "$web_port" "$web_pid_file"; then note "Healthy Web UI reused."; else start_web; fi
success=true
note "TestHub HTTP/CSRF checks passed."
note "Web UI: $web_url/login"
note "API:    $api_url/actuator/health"
note "Logs:   $state_dir"
