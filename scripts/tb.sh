#!/usr/bin/env bash
# tbridge CLI — broadcasts adb wrappés en commandes courtes pour piloter TalkBack.
#
# Requires :
#   - adb dans le PATH
#   - app tbridge installée (./gradlew installDebug)
#   - service tbridge activé dans Paramètres → Accessibilité (manuel, une fois)

set -euo pipefail

PREFIX="fr.acart.tbridge.gesture"
ADB="${ADB:-adb}"

usage() {
  cat <<EOF
Usage: $(basename "$0") <command> [args]

Commands:
  right                Swipe TalkBack vers la droite → élément suivant
  left                 Swipe vers la gauche → élément précédent
  up                   Swipe vertical bas → haut
  down                 Swipe vertical haut → bas
  tap [x] [y]          Tap simple aux coords (défaut = centre écran)
  click                Active l'élément accessibility focused (équivalent double-tap)
  longclick            Long-clic sur l'élément focused
  two-up               Swipe 2 doigts vers le haut (scroll TalkBack)

Env vars:
  ADB                  Path vers adb (défaut: 'adb', résolu via PATH)

Examples:
  $(basename "$0") right
  $(basename "$0") tap 540 1200
  $(basename "$0") click
EOF
}

if [[ $# -eq 0 ]]; then
  usage
  exit 1
fi

cmd="$1"; shift

broadcast() {
  local action="$1"; shift
  "$ADB" shell am broadcast -a "${PREFIX}.${action}" "$@" >/dev/null
}

case "$cmd" in
  right)     broadcast SWIPE_RIGHT ;;
  left)      broadcast SWIPE_LEFT ;;
  up)        broadcast SWIPE_UP ;;
  down)      broadcast SWIPE_DOWN ;;
  tap)
    if [[ $# -eq 0 ]]; then
      broadcast TAP
    elif [[ $# -eq 2 ]]; then
      broadcast TAP --ei x "$1" --ei y "$2"
    else
      echo "Usage: tap [x y]" >&2
      exit 1
    fi
    ;;
  click)     broadcast CLICK_FOCUSED ;;
  longclick) broadcast LONG_CLICK_FOCUSED ;;
  two-up)    broadcast SWIPE_TWO_FINGERS_UP ;;
  -h|--help|help) usage ;;
  *)
    echo "Unknown command: $cmd" >&2
    usage
    exit 1
    ;;
esac
