#!/usr/bin/env bash
#
# Runs Maven with a reachable Docker daemon, on Linux/macOS and on Windows.
#
#   ./scripts/run-tests.sh                                  # full suite
#   ./scripts/run-tests.sh test -Dtest=EventProcessingFlowIntTest
#   ./scripts/run-tests.sh package
#
# Where a native Docker daemon is already reachable (Linux, macOS, Docker
# Desktop, or DOCKER_HOST already exported) this is a thin passthrough to Maven.
#
# On Windows with Docker running as a native daemon inside a WSL2 distro, plain
# `mvn test` fails every integration test with "Could not find a valid Docker
# environment", for two reasons:
#
#   1. WSL2 shuts the distro down when it goes idle, taking the daemon with it -
#      including part way through a test run.
#   2. Nothing tells the JVM where the daemon is; there is no Docker Desktop
#      named pipe for Testcontainers to find.
#
# This script handles both: it holds a WSL session open for exactly as long as
# the run lasts, makes sure the socat TCP bridge is listening, resolves the
# distro's current IP, and exports DOCKER_HOST before invoking Maven.
#
# Override the defaults with environment variables if needed:
#   WSL_DISTRO=Ubuntu-22.04 DOCKER_BRIDGE_PORT=2375 ./scripts/run-tests.sh

set -euo pipefail

MAVEN_ARGS=("$@")
if [ ${#MAVEN_ARGS[@]} -eq 0 ]; then
    MAVEN_ARGS=(test)
fi

DISTRO="${WSL_DISTRO:-Ubuntu}"
PORT="${DOCKER_BRIDGE_PORT:-2375}"
PROXY_NAME="docker-tcp-proxy"
KEEPALIVE_PID=""

cleanup() {
    if [ -n "$KEEPALIVE_PID" ] && kill -0 "$KEEPALIVE_PID" 2>/dev/null; then
        kill "$KEEPALIVE_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# A daemon we can already reach needs none of the WSL plumbing.
if docker info >/dev/null 2>&1 || [ -n "${DOCKER_HOST:-}" ]; then
    echo "Docker is already reachable; running Maven directly."
    exec mvn "${MAVEN_ARGS[@]}"
fi

if ! command -v wsl.exe >/dev/null 2>&1; then
    echo "No reachable Docker daemon and no WSL available." >&2
    echo "Start Docker (or export DOCKER_HOST) and try again." >&2
    exit 1
fi

wsl_run() {
    wsl.exe -d "$DISTRO" -- bash -lc "$1" | tr -d '\r'
}

# 1. Hold the distro open. Without this WSL2 can stop it - and the daemon - part
#    way through the run, which surfaces as an intermittent "Connection refused"
#    or "DOCKER_HOST ... is not listening".
echo "[1/4] Holding $DISTRO open for the duration of the run..."
wsl.exe -d "$DISTRO" -- sleep infinity &
KEEPALIVE_PID=$!

# 2. The daemon itself is a systemd service and starts with the distro; the TCP
#    bridge is an ordinary container that may need starting or creating.
echo "[2/4] Checking the Docker daemon and the $PROXY_NAME bridge..."
SERVER_VERSION="$(wsl_run "docker version --format '{{.Server.Version}}'" || true)"
if [ -z "$SERVER_VERSION" ]; then
    echo "Docker is not responding inside $DISTRO." >&2
    exit 1
fi
echo "      Docker Engine $SERVER_VERSION"

if [ -z "$(wsl_run "docker ps -q -f name=^${PROXY_NAME}\$")" ]; then
    if [ -n "$(wsl_run "docker ps -aq -f name=^${PROXY_NAME}\$")" ]; then
        echo "      Starting the existing $PROXY_NAME container..."
        wsl_run "docker start $PROXY_NAME" >/dev/null
    else
        echo "      Creating the $PROXY_NAME container..."
        wsl_run "docker run -d --name $PROXY_NAME --restart unless-stopped --network host \
            -v /var/run/docker.sock:/var/run/docker.sock alpine/socat \
            TCP-LISTEN:${PORT},fork,reuseaddr UNIX-CONNECT:/var/run/docker.sock" >/dev/null
    fi
fi

# 3. The distro's address is assigned by WSL and changes across restarts, so
#    resolve it per run. Use the literal IPv4: the JVM may resolve "localhost" to
#    ::1 and report the port closed.
echo "[3/4] Resolving the $DISTRO address..."
IP="$(wsl_run 'hostname -I' | awk '{print $1}')"
if [ -z "$IP" ]; then
    echo "Could not determine the IP address of $DISTRO." >&2
    exit 1
fi

for _ in $(seq 1 10); do
    if curl -fsS -m 3 "http://${IP}:${PORT}/_ping" >/dev/null 2>&1; then
        break
    fi
    sleep 0.5
done
if ! curl -fsS -m 3 "http://${IP}:${PORT}/_ping" >/dev/null 2>&1; then
    echo "The Docker bridge at tcp://${IP}:${PORT} did not respond." >&2
    echo "Check the '$PROXY_NAME' container inside $DISTRO." >&2
    exit 1
fi

export DOCKER_HOST="tcp://${IP}:${PORT}"
echo "      DOCKER_HOST=$DOCKER_HOST"

# 4. Run Maven with the daemon in reach.
echo "[4/4] mvn ${MAVEN_ARGS[*]}"
echo
mvn "${MAVEN_ARGS[@]}"
