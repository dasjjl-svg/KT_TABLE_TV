#!/bin/zsh
set -eu
cd "$(dirname "$0")"
exec node scripts/start.mjs
