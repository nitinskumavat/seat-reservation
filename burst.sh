#!/usr/bin/env sh
# Reproduces the on-sale stampede against a running service and reconciles the result.
# Usage: ./burst.sh [BASE_URL]   (default http://localhost:8080; needs Java 21+)
set -e
ulimit -n 10240 2>/dev/null || true
exec java "$(dirname "$0")/burst/Burst.java" "${1:-http://localhost:8080}"
