#!/usr/bin/env bash
# tbridge CLI — broadcasts adb wrappés en commandes courtes pour piloter TalkBack.
#
# Setup (une fois) :
#   1. ./gradlew installDebug
#   2. Activer tbridge dans Paramètres → Accessibilité → Services installés
#   3. ./tb.sh init    # fetch le token depuis l'app via adb run-as
#
# Le token est stocké dans ~/.tbridge-token (lu automatiquement) ou via TBRIDGE_TOKEN env var.

set -euo pipefail

PREFIX="fr.acart.tbridge.gesture"
PKG="fr.acart.tbridge"
ADB="${ADB:-adb}"
TOKEN_FILE="${HOME}/.tbridge-token"
# Délai entre 2 broadcasts dans une commande multi-N. Doit laisser TalkBack vocaliser
# l'élément focused entre 2 swipes. ~700ms est un bon compromis.
INTER_DELAY="${TBRIDGE_INTER_DELAY:-0.7}"

usage() {
  cat <<EOF
Usage: $(basename "$0") <command> [args]

Setup:
  init                 Fetch le token depuis l'app (via adb run-as) et le stocke
                       dans ${TOKEN_FILE}.

Navigation TalkBack (peut être enchaîné via [N]):
  right [N]            N swipe-right (= focus → élément suivant). N défaut 1.
  left [N]             N swipe-left (= focus précédent). N défaut 1.
  up / down            Swipe vertical (1 seul, pas de N).
  tap [x] [y]          Tap simple (défaut = centre écran).

Actions (toujours unitaires — JAMAIS chaînées, raisonne avant) :
  click                Active l'élément accessibility focused (= double-tap TalkBack).
  longclick            Long-clic sur l'élément focused.

Scroll TalkBack (2 doigts) :
  two-up               Scroll contenu vers le haut.

Actions globales système (performGlobalAction) :
  back                 = bouton retour système
  home                 = bouton home
  recents              = liste apps récentes
  notifications        = ouvre shade notifications
  quick-settings       = ouvre shade quick settings
  power-dialog         = menu power (⚠️ peut interrompre la session)
  lock                 = lock écran (⚠️ tu devras déverrouiller pour continuer)
  screenshot           = capture d'écran

Env vars:
  ADB                  Path vers adb (défaut: 'adb' via PATH)
  TBRIDGE_TOKEN        Override du token (par défaut lu depuis ${TOKEN_FILE})
  TBRIDGE_INTER_DELAY  Délai (en s) entre 2 broadcasts d'une commande N. Défaut 0.7.

Examples:
  $(basename "$0") init
  $(basename "$0") right            # 1 swipe
  $(basename "$0") right 5          # 5 swipes avec 700ms entre
  $(basename "$0") tap 540 1200
  $(basename "$0") click            # action — JAMAIS chaînée

⚠️  click / longclick / tap = actions à conséquence. Toujours raisonner sur
    l'utterance TalkBack précédente AVANT de cliquer.
EOF
}

fetch_token() {
  local raw
  raw=$("$ADB" shell run-as "$PKG" cat "/data/data/$PKG/shared_prefs/tbridge.xml" 2>/dev/null) || {
    echo "ERR : 'adb shell run-as $PKG' a échoué." >&2
    echo "Vérifie que :" >&2
    echo "  - l'app est installée en debug build" >&2
    echo "  - le device est connecté (adb devices)" >&2
    echo "  - l'app a été ouverte au moins une fois (le token est créé au 1er accès)" >&2
    return 1
  }
  local token
  token=$(printf '%s' "$raw" | grep -oE 'name="token">[^<]+' | sed 's/^name="token">//' | head -1)
  if [[ -z "$token" ]]; then
    echo "ERR : token introuvable dans les SharedPreferences." >&2
    echo "Ouvre l'app tbridge sur le device, puis re-tente '$(basename "$0") init'." >&2
    return 1
  fi
  printf '%s' "$token" > "$TOKEN_FILE"
  chmod 600 "$TOKEN_FILE"
  echo "Token sauvé dans $TOKEN_FILE"
}

resolve_token() {
  if [[ -n "${TBRIDGE_TOKEN:-}" ]]; then
    printf '%s' "$TBRIDGE_TOKEN"
    return 0
  fi
  if [[ -f "$TOKEN_FILE" ]]; then
    cat "$TOKEN_FILE"
    return 0
  fi
  echo "ERR : pas de token. Lance '$(basename "$0") init' ou exporte TBRIDGE_TOKEN." >&2
  return 1
}

broadcast() {
  local action="$1"; shift
  local token
  token=$(resolve_token)
  "$ADB" shell am broadcast -a "${PREFIX}.${action}" --es token "$token" "$@" >/dev/null
}

# Navigation directionnelle avec count optionnel.
# Usage: nav_n SWIPE_RIGHT 5
nav_n() {
  local action="$1"; local n="${2:-1}"
  if ! [[ "$n" =~ ^[1-9][0-9]*$ ]]; then
    echo "Usage: $(basename "$0") <right|left> [N≥1]" >&2
    exit 1
  fi
  for ((i = 0; i < n; i++)); do
    broadcast "$action"
    if (( i < n - 1 )); then
      sleep "$INTER_DELAY"
    fi
  done
}

if [[ $# -eq 0 ]]; then
  usage
  exit 1
fi

cmd="$1"; shift

case "$cmd" in
  init)      fetch_token ;;
  right)     nav_n SWIPE_RIGHT "${1:-1}" ;;
  left)      nav_n SWIPE_LEFT "${1:-1}" ;;
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
  back)            broadcast GLOBAL_BACK ;;
  home)            broadcast GLOBAL_HOME ;;
  recents)         broadcast GLOBAL_RECENTS ;;
  notifications)   broadcast GLOBAL_NOTIFICATIONS ;;
  quick-settings)  broadcast GLOBAL_QUICK_SETTINGS ;;
  power-dialog)    broadcast GLOBAL_POWER_DIALOG ;;
  lock)            broadcast GLOBAL_LOCK ;;
  screenshot)      broadcast GLOBAL_SCREENSHOT ;;
  -h|--help|help) usage ;;
  *)
    echo "Unknown command: $cmd" >&2
    usage
    exit 1
    ;;
esac
