#!/bin/zsh
set -eu
cd "$(dirname "$0")"
if [[ ! -f runtime/setup.json ]]; then
  node scripts/setup.mjs
fi
python3 scripts/android_tools.py --install
python3 scripts/android_setup.py
python3 hardware/panel-app/build.py
printf '\nBuild complete. Copy hardware/panel-app/SmartFactory.apk to the root of your USB memory.\n'
printf 'The APK contains your private server credentials; do not publish or share it.\n'
