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
  status               Vérifie tbridge activé + TalkBack activé + fenêtre d'écoute
                       tbridge active. Exit non-zero si un check échoue.
  talkback-on          Active TalkBack via Settings.Secure (ajoute son ComponentName
                       à enabled_accessibility_services).
  talkback-off         Désactive TalkBack (le retire de enabled_accessibility_services).

Navigation TalkBack (peut être enchaîné via [N]):
  right [N]            N swipe-right (= focus → élément suivant). N défaut 1.
  left [N]             N swipe-left (= focus précédent). N défaut 1.
  up / down            Swipe vertical (1 seul, pas de N).
  tap [x] [y]          Tap simple (défaut = centre écran).
  double-tap [x] [y]   Double-tap atomique (~150ms entre les 2). Avec TalkBack ON,
                       active le node accessibility-focused (équivalent geste user).
                       Utile quand ACTION_CLICK renvoie false. Sans coords =
                       centre écran (TalkBack n'a pas besoin des bounds — peu importe).
  activate             Alias sémantique de double-tap (sans coords) : "activate
                       le node currently focused". À privilégier dans les workflows
                       lisibles où l'intent est l'activation, pas le geste exact.

Actions (toujours unitaires — JAMAIS chaînées, raisonne avant) :
  click                Active l'élément accessibility focused (= double-tap TalkBack).
  longclick            Long-clic sur l'élément focused.

Scroll TalkBack (2 doigts, naming par effet pas par direction du doigt) :
  scroll-up            Voir le contenu d'au-dessus (= geste 2 doigts vers le bas).
  scroll-down          Voir le contenu d'en-dessous (= geste 2 doigts vers le haut).

Inspection :
  focused              Affiche le node actuellement accessibility-focused (class,
                       label, id, clickable, bounds). À LANCER avant chaque click
                       pour confirmer qu'on est sur le bon élément.
  dump [--raw] [--all] Snapshot de l'arbre AccessibilityNodeInfo via tbridge
                       (DUMP_TREE — pas de toggle TalkBack). --raw pour voir le
                       XML brut. --all pour toutes les windows visibles (utile
                       pour overlays système — dialog permission, share sheet).
                       Sans --all : active window seule (défaut, plus prévisible
                       — multi-window peut faire migrer le focus entre windows).
  list-actions         Log les actions disponibles (id + label) dans logcat
                       (tag TBGesture). Utile pour découvrir 'Plus d'options',
                       'Voir détails' etc. (équivalent menu TalkBack contextuel).
  do-action <id>       Exécute l'action avec cet id sur le node focused.
  input <text>         Saisit <text> dans le node focused via ACTION_SET_TEXT
                       (requiert un EditText/TextInput). Le contenu existant est
                       remplacé. Pré-requis : activate le champ d'abord.
  activate-by-id <id>  Trouve le node par viewId (findAccessibilityNodeInfosByViewId)
                       puis active : ACTION_CLICK direct, fallback double-tap aux
                       bounds. Plus fiable que tap par coords quand le focus est
                       volatile (cover auto-défilante, etc.). Accepte forme courte
                       ('avatar') ou full ('fr.tf1.mytf1:id/avatar').

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

check_status() {
  local services
  services=$("$ADB" shell settings get secure enabled_accessibility_services 2>/dev/null)
  local ok=0
  if echo "$services" | grep -q "fr.acart.tbridge"; then
    echo "OK  tbridge service activé"
  else
    echo "KO  tbridge service NON activé (Paramètres → Accessibilité → tbridge)"
    ok=1
  fi
  if echo "$services" | grep -q "com.google.android.marvin.talkback"; then
    echo "OK  TalkBack activé"
  else
    echo "KO  TalkBack NON activé — sans TalkBack, les swipes tbridge ne navigueront pas"
    ok=1
  fi
  # Vérif fenêtre tbridge active via SharedPreferences (active_until_ms > now)
  local prefs
  prefs=$("$ADB" shell run-as "$PKG" cat shared_prefs/tbridge.xml 2>/dev/null) || prefs=""
  local active_until
  # Format SharedPrefs XML : <long name="active_until_ms" value="1779411599806" />
  active_until=$(printf '%s' "$prefs" | grep -oE 'name="active_until_ms" value="[0-9]+"' | grep -oE '[0-9]+' | head -1)
  local now_ms
  now_ms=$(($(date +%s) * 1000))
  if [[ -n "$active_until" ]] && (( active_until > now_ms )); then
    local remaining=$(( (active_until - now_ms) / 60000 ))
    echo "OK  fenêtre tbridge active (~${remaining} min restantes)"
  else
    echo "KO  fenêtre tbridge expirée ou jamais activée (ouvrir l'app → Activer)"
    ok=1
  fi
  return $ok
}

TALKBACK_COMPONENT="com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"

talkback_on() {
  local current
  current=$("$ADB" shell settings get secure enabled_accessibility_services 2>/dev/null)
  [[ "$current" == "null" ]] && current=""
  if echo "$current" | grep -q "com.google.android.marvin.talkback"; then
    echo "TalkBack déjà activé"
    return 0
  fi
  local new
  if [[ -z "$current" ]]; then
    new="$TALKBACK_COMPONENT"
  else
    new="${current}:${TALKBACK_COMPONENT}"
  fi
  "$ADB" shell settings put secure enabled_accessibility_services "$new" >/dev/null
  "$ADB" shell settings put secure accessibility_enabled 1 >/dev/null
  echo "TalkBack activé"
}

talkback_off() {
  local current
  current=$("$ADB" shell settings get secure enabled_accessibility_services 2>/dev/null)
  [[ "$current" == "null" ]] && current=""
  if ! echo "$current" | grep -q "com.google.android.marvin.talkback"; then
    echo "TalkBack déjà désactivé"
    return 0
  fi
  # Retire le composant TalkBack (et nettoie les éventuels ':' en double / début / fin)
  local new
  new=$(printf '%s' "$current" | sed -E "s#:?${TALKBACK_COMPONENT}##g; s#^:##; s#:\$##")
  "$ADB" shell settings put secure enabled_accessibility_services "$new" >/dev/null
  echo "TalkBack désactivé"
}

case "$cmd" in
  init)         fetch_token ;;
  status)       check_status ;;
  talkback-on)  talkback_on ;;
  talkback-off) talkback_off ;;
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
  double-tap|activate)
    # activate = alias sémantique : avec TalkBack ON, double-tap n'importe où
    # active le node accessibility-focused (geste user standard "activate").
    # Le centre écran (défaut) suffit donc, pas besoin des bounds.
    if [[ $# -eq 0 ]]; then
      broadcast DOUBLE_TAP
    elif [[ $# -eq 2 ]]; then
      broadcast DOUBLE_TAP --ei x "$1" --ei y "$2"
    else
      echo "Usage: $cmd [x y]" >&2
      exit 1
    fi
    ;;
  click)     broadcast CLICK_FOCUSED ;;
  longclick) broadcast LONG_CLICK_FOCUSED ;;
  scroll-up)   broadcast SCROLL_UP ;;
  scroll-down) broadcast SCROLL_DOWN ;;
  dump)
    raw=false
    all=false
    for arg in "$@"; do
      case "$arg" in
        --raw) raw=true ;;
        --all) all=true ;;
      esac
    done
    # Note : ne PAS utiliser `uiautomator dump` — il passe par UiAutomation qui
    # suspend tous les a11y services pendant l'op, donc TalkBack annonce
    # "désactivé... activé" à chaque dump. Notre action DUMP_TREE sérialise depuis
    # l'arbre accessibility directement, sans toucher TalkBack.
    #
    # --all : dump TOUTES les windows visibles (utile pour overlays système).
    # Sans --all : active window seule (défaut, plus prévisible — le focus
    # multi-window peut migrer entre windows et embrouiller la nav).
    if $all; then
      broadcast DUMP_TREE --ez all true
    else
      broadcast DUMP_TREE
    fi
    sleep 0.5
    if ! "$ADB" shell run-as "$PKG" cat cache/dump.xml > /tmp/tb_dump.xml 2>/dev/null; then
      echo "ERR : pull du dump (run-as $PKG) a échoué. tbridge bien activé + lancé ?" >&2
      exit 1
    fi
    if [[ ! -s /tmp/tb_dump.xml ]]; then
      echo "ERR : dump vide. Logs récents :" >&2
      "$ADB" logcat -d -s TBGesture:D 2>&1 | grep -E "DumpTree" | tail -3 >&2
      exit 1
    fi
    if $raw; then
      cat /tmp/tb_dump.xml
    else
      python3 "$(dirname "$0")/parse_dump.py" /tmp/tb_dump.xml
    fi
    ;;
  focused)
    "$ADB" logcat -c
    broadcast FOCUSED
    sleep 0.4
    "$ADB" logcat -d -s TBGesture:D 2>&1 | grep "Focused:" | tail -1 || echo "(aucun log — vérifier tbridge + TalkBack actifs)"
    ;;
  input)
    if [[ $# -lt 1 ]]; then
      echo "Usage: input <text>" >&2; exit 1
    fi
    broadcast INPUT_TEXT --es text "$*"
    ;;
  activate-by-id)
    if [[ $# -ne 1 ]]; then
      echo "Usage: activate-by-id <view_id>  (forme courte 'avatar' ou full 'fr.tf1.mytf1:id/avatar')" >&2
      exit 1
    fi
    broadcast ACTIVATE_BY_ID --es view_id "$1"
    ;;
  list-actions)
    "$ADB" logcat -c
    broadcast LIST_ACTIONS
    sleep 0.8
    echo "=== Actions du node accessibility-focused ==="
    "$ADB" logcat -d -s TBGesture:D 2>&1 | grep "ListActions:" || echo "(aucun log — re-vérifier qu'un élément est focused)"
    ;;
  do-action)
    if [[ $# -ne 1 ]]; then
      echo "Usage: do-action <id>  (récupère l'id via './tb.sh list-actions')" >&2
      exit 1
    fi
    "$ADB" logcat -c
    broadcast DO_ACTION --ei action_id "$1"
    sleep 0.8
    echo "=== Résultat ==="
    "$ADB" logcat -d -s TBGesture:D 2>&1 | grep "DoAction" | tail -1 || echo "(pas de log — re-vérifier qu'un élément est focused)"
    ;;
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
