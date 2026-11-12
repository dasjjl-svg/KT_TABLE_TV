#!/bin/sh
set -eu
CDPATH= cd -- "$(dirname -- "$0")"
exec node scripts/start.mjs
