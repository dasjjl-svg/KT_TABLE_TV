#!/bin/zsh
set -eu
cd "$(dirname "$0")"
node scripts/setup.mjs
npm --prefix panel ci
npm --prefix panel run build
printf '\n완료. Start.command를 실행하세요.\n'
