#!/bin/sh
set -eu
CDPATH= cd -- "$(dirname -- "$0")"
node scripts/host-check.mjs
node scripts/setup.mjs
npm --prefix panel ci
npm --prefix panel run build
printf '\n설정 완료. sh Start.sh로 실행하세요.\n'
