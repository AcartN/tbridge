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
# State files pour `record` (PID + path + timestamp + max). Locaux côté host.
RECORD_PID_FILE="/tmp/tb_record.pid"
RECORD_PATH_FILE="/tmp/tb_record.path"
RECORD_MAX_FILE="/tmp/tb_record.max"
RECORD_STARTED_FILE="/tmp/tb_record.started"
# Hard cap par défaut (5 min). Override via --max ou TBRIDGE_RECORD_MAX.
RECORD_DEFAULT_MAX="${TBRIDGE_RECORD_MAX:-300}"

usage() {
  cat <<EOF
Usage: $(basename "$0") <command> [args]

Setup:
  init                 Fetch le token depuis l'app (via adb run-as) et le stocke
                       dans ${TOKEN_FILE}.
  status               Vérifie tbridge activé + TalkBack activé + fenêtre d'écoute
                       tbridge active. Exit non-zero si un check échoue.
  talkback-on          Active TalkBack via Settings.Secure (ajoute son ComponentName
                       à enabled_accessibility_services) + setprop verbose log.
  talkback-off         Désactive TalkBack (le retire de enabled_accessibility_services).
  talkback-restart     am force-stop com.google.android.marvin.talkback (Android
                       respawn auto). À utiliser après setprop log.tag.talkback
                       VERBOSE pour que TalkBack pick la config (le toggle du
                       service ne suffit pas si le process est déjà up).

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

Capture scenario (repro/validation tickets accessibilité) :
  record start [--max <s>]  Démarre screenrecord côté device. Hard cap ${RECORD_DEFAULT_MAX}s
                            par défaut (= 5 min) — Android stop tout seul à expiration,
                            JAMAIS de fuite même si stop oublié. Cleanup auto des
                            screenrecord orphelins avant de démarrer.
  record stop [<out_path>]  SIGINT (flush propre du .mp4) + pull en local.
                            Défaut out_path = ./tb_record_<epoch>.mp4
  record cleanup            Kill tout screenrecord traînant + supprime les .mp4
                            orphelins sur /sdcard/. À utiliser en debug.
  utterances [clear]        Sans arg : dump les utterances TalkBack (text=...)
                            depuis le dernier 'logcat -c'. Avec 'clear' : juste
                            clear le buffer (pour démarrer une nouvelle capture).

Volume d'accessibilité (voix TalkBack) :
  volume [N]           Sans arg : affiche le niveau. Avec N : règle le niveau (borné
                       au min/max du stream, typiquement 1..15). Passe par tbridge :
                       Android refuse ce réglage à adb (CHANGE_ACCESSIBILITY_VOLUME).
                       Ne vide pas logcat (les utterances restent capturables).

Affichage (réglages système via adb, sans tbridge, appliqués tout de suite) :
  font-scale [X|reset] Taille de police. Sans arg : valeur actuelle. X = facteur
                       (1.0 = 100 %, 2.0 = 200 %, le max des Réglages sur Android 14+).
  display-size [DPI|max|reset]
                       Taille d'affichage (densité). Sans arg : densité actuelle et
                       largeur d'écran en dp. 'max' = le cran le plus grand des
                       Réglages : écran à 320 dp de large, au plus 1,5× la densité
                       par défaut (règle AOSP DisplayDensityUtils).
  bold-text [on|off]   Texte en gras (Réglages → Affichage → Texte en gras).
  a11y-reset           Remet police 100 %, densité par défaut, gras désactivé.

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
  # Info screenrecord en cours
  if [[ -f "$RECORD_PID_FILE" ]]; then
    local started elapsed max pid
    started=$(cat "$RECORD_STARTED_FILE" 2>/dev/null || echo 0)
    max=$(cat "$RECORD_MAX_FILE" 2>/dev/null || echo "?")
    pid=$(cat "$RECORD_PID_FILE")
    elapsed=$(( $(date +%s) - started ))
    echo "ℹ️  screenrecord en cours depuis ${elapsed}s (pid=${pid}, hard cap=${max}s)"
  fi
  # Auto-activer le verbose logging côté Android log system. Idempotent.
  "$ADB" shell "setprop log.tag.talkback VERBOSE" >/dev/null 2>&1 || true
  # Check empirique : si on a 0 ligne talkback récente, WARN. Pour que TalkBack
  # pick up le setprop verbose il faut un process frais — NE PAS utiliser
  # talkback-restart (force-stop = Android retire TalkBack des services activés,
  # cf talkback_restart plus bas). Le cycle sûr = talkback-off puis talkback-on.
  local tb_lines
  tb_lines=$("$ADB" logcat -d -t 500 2>/dev/null | grep -c "talkback:" || true)
  if (( tb_lines < 3 )); then
    echo "WARN  Aucune ligne talkback récente dans logcat. Pour activer la capture verbose : './tb.sh talkback-off && ./tb.sh talkback-on' (process frais qui pick up 'setprop log.tag.talkback VERBOSE'). NE PAS utiliser talkback-restart (désactive TalkBack)."
  fi
  return $ok
}

record_start() {
  local max="$RECORD_DEFAULT_MAX"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --max) max="$2"; shift 2 ;;
      *) echo "Usage: $(basename "$0") record start [--max <seconds>]" >&2; exit 1 ;;
    esac
  done
  # Cleanup défensif : kill tout screenrecord orphelin avant de démarrer
  "$ADB" shell "pkill -INT -f screenrecord" >/dev/null 2>&1 || true
  sleep 0.3
  local remote_path="/sdcard/tb_record_$(date +%s).mp4"
  # Lancer screenrecord en background **côté device** via nohup + redirections.
  # Sans ça, le screenrecord est lié à la session adb shell et l'arrêt de cette
  # session le tue (ou bloque le caller). Le `&` est interprété côté device
  # grâce aux guillemets — adb shell retourne immédiatement.
  # --time-limit = hard cap Android, garantie anti-fuite même si stop oublié.
  "$ADB" shell "nohup screenrecord --time-limit $max $remote_path </dev/null >/dev/null 2>&1 &"
  sleep 1.2
  local remote_pid
  remote_pid=$("$ADB" shell "pidof screenrecord" 2>/dev/null | tr -d '[:space:]')
  if [[ -z "$remote_pid" ]]; then
    echo "ERR : screenrecord ne s'est pas lancé côté device" >&2
    exit 1
  fi
  printf '%s' "$remote_pid" > "$RECORD_PID_FILE"
  printf '%s' "$remote_path" > "$RECORD_PATH_FILE"
  printf '%s' "$max" > "$RECORD_MAX_FILE"
  printf '%s' "$(date +%s)" > "$RECORD_STARTED_FILE"
  echo "Record démarré : pid=$remote_pid path=$remote_path hard_cap=${max}s"
}

record_stop() {
  local out="${1:-./tb_record_$(date +%s).mp4}"
  if [[ ! -f "$RECORD_PID_FILE" ]]; then
    echo "WARN : pas de record en cours (pas de $RECORD_PID_FILE). Cleanup défensif." >&2
    "$ADB" shell "pkill -INT -f screenrecord" >/dev/null 2>&1 || true
    exit 1
  fi
  local remote_pid remote_path
  remote_pid=$(cat "$RECORD_PID_FILE")
  remote_path=$(cat "$RECORD_PATH_FILE")
  # SIGINT (= équiv Ctrl+C) = flush propre du .mp4 (header MOOV écrit).
  # SIGKILL = mp4 corrompu non lisible.
  "$ADB" shell "kill -INT $remote_pid" >/dev/null 2>&1 || true
  sleep 1.5
  if "$ADB" pull "$remote_path" "$out" >/dev/null 2>&1; then
    "$ADB" shell "rm $remote_path" >/dev/null 2>&1 || true
    echo "Record stoppé + pull : $out"
  else
    echo "ERR : pull du record a échoué ($remote_path → $out)" >&2
    rm -f "$RECORD_PID_FILE" "$RECORD_PATH_FILE" "$RECORD_MAX_FILE" "$RECORD_STARTED_FILE"
    exit 1
  fi
  rm -f "$RECORD_PID_FILE" "$RECORD_PATH_FILE" "$RECORD_MAX_FILE" "$RECORD_STARTED_FILE"
}

record_cleanup() {
  "$ADB" shell "pkill -INT -f screenrecord" >/dev/null 2>&1 || true
  sleep 0.5
  # Supprime les .mp4 orphelins (notre naming)
  "$ADB" shell "rm /sdcard/tb_record_*.mp4 2>/dev/null" || true
  rm -f "$RECORD_PID_FILE" "$RECORD_PATH_FILE" "$RECORD_MAX_FILE" "$RECORD_STARTED_FILE"
  echo "Cleanup OK : screenrecord orphelins killed + /sdcard/tb_record_*.mp4 supprimés"
}

utterances_dump() {
  # Extrait l'utterance vocalisée par TalkBack depuis logcat. Deux formats gérés :
  #  - TalkBack récent (Play Store ~2024+, vu sur Samsung) : ligne Pipeline
  #    `talkback: Pipeline ... execute() feedback=Feedback{... action=SPEAK  text="<utterance complète + rôle>" ...}`
  #    L'utterance inclut le rôle final ("Bouton", "Titre", etc.) et la ponctuation
  #    réelle (utile pour les bugs type "point avant le rôle"). On matche la ligne
  #    `Pipeline` et PAS `Actors` (même contenu) pour éviter le doublon.
  #  - TalkBack plus ancien (ex. Pixel) : `D talkback: SpeechCacheManager:
  #    speakWithSpeechCachePlayer:...text=...,` (fallback).
  #
  # PRÉREQUIS : niveau de log INTERNE de TalkBack = Verbeux (Paramètres TalkBack →
  # Paramètres avancés → Développeur → Niveau de journalisation = Verbeux), EN PLUS
  # de `setprop log.tag.talkback VERBOSE`. Le setprop seul ne suffit pas : le niveau
  # interne gate l'émission des lignes speech. (Découvert 29 mai 2026, tablette Samsung.)
  #
  # Note : `logcat -d -s talkback:V` rate les lignes Debug sur certains devices →
  # on utilise `logcat -d` brut + perl (pas `grep -P` : absent du grep BSD de
  # macOS). Multibyte-safe (bornes " et , en ASCII).
  # `uniq` : le pipeline TalkBack loggue souvent la même feedback 2-3× d'affilée →
  # on collapse les doublons CONSÉCUTIFS (n'affecte pas des répétitions distantes).
  "$ADB" logcat -d 2>&1 \
    | perl -ne 'if (/talkback: Pipeline.*?action=SPEAK\s+text="([^"]*)/) { print "$1\n" } elsif (/speakWithSpeechCachePlayer.*?text=([^,]+)/) { print "$1\n" }' \
    | uniq
}

TALKBACK_COMPONENT="com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"

talkback_on() {
  local current
  current=$("$ADB" shell settings get secure enabled_accessibility_services 2>/dev/null)
  [[ "$current" == "null" ]] && current=""
  if echo "$current" | grep -q "com.google.android.marvin.talkback"; then
    echo "TalkBack déjà activé"
    # Setprop + force-stop pour s'assurer que le verbose log marche (le toggle
    # du service ne suffit pas si le process est déjà up et le setprop a été
    # fait après son démarrage — il faut le respawn pour qu'il pick up).
    "$ADB" shell "setprop log.tag.talkback VERBOSE" >/dev/null 2>&1 || true
    return 0
  fi
  local new
  if [[ -z "$current" ]]; then
    new="$TALKBACK_COMPONENT"
  else
    new="${current}:${TALKBACK_COMPONENT}"
  fi
  "$ADB" shell "setprop log.tag.talkback VERBOSE" >/dev/null 2>&1 || true
  "$ADB" shell settings put secure enabled_accessibility_services "$new" >/dev/null
  "$ADB" shell settings put secure accessibility_enabled 1 >/dev/null
  echo "TalkBack activé"
}

talkback_restart() {
  # force-stop puis Android relance automatiquement (service activé dans
  # enabled_accessibility_services). À utiliser après setprop log.tag.talkback
  # quand TalkBack ne pick pas la nouvelle config — le respawn pick up.
  # Note : contrairement à force-stop sur tbridge (qui DÉSACTIVE le service),
  # force-stop sur TalkBack (system app Google) est safe — Android la respawn.
  "$ADB" shell "am force-stop com.google.android.marvin.talkback" 2>/dev/null || true
  sleep 2
  # Re-check
  if "$ADB" shell settings get secure enabled_accessibility_services 2>/dev/null | grep -q "talkback"; then
    echo "TalkBack restarted (respawn auto Android)"
  else
    echo "WARN : TalkBack absent de enabled_accessibility_services après restart. Re-run tb.sh talkback-on."
    return 1
  fi
}

talkback_off() {
  local current
  current=$("$ADB" shell settings get secure enabled_accessibility_services 2>/dev/null)
  [[ "$current" == "null" ]] && current=""
  if ! echo "$current" | grep -q "com.google.android.marvin.talkback"; then
    echo "TalkBack déjà désactivé"
    return 0
  fi
  # Retire toute entrée TalkBack, nom long (…/com.google.android.marvin.talkback.TalkBackService)
  # comme nom court (…/.TalkBackService) : Android enregistre l'un ou l'autre selon qui a activé le service.
  local new
  new=$(printf '%s' "$current" | tr ':' '\n' | grep -v '^com\.google\.android\.marvin\.talkback/' | paste -sd ':' -)
  if [[ -z "$new" ]]; then
    "$ADB" shell settings delete secure enabled_accessibility_services >/dev/null
  else
    "$ADB" shell settings put secure enabled_accessibility_services "$new" >/dev/null
  fi
  if "$ADB" shell settings get secure enabled_accessibility_services 2>/dev/null | grep -q "com.google.android.marvin.talkback"; then
    echo "WARN : TalkBack toujours présent dans enabled_accessibility_services"
    return 1
  fi
  echo "TalkBack désactivé"
}

case "$cmd" in
  init)         fetch_token ;;
  status)       check_status ;;
  talkback-on)      talkback_on ;;
  talkback-off)     talkback_off ;;
  talkback-restart) talkback_restart ;;
  record)
    sub="${1:-}"; shift || true
    case "$sub" in
      start)   record_start "$@" ;;
      stop)    record_stop "$@" ;;
      cleanup) record_cleanup ;;
      *) echo "Usage: $(basename "$0") record start [--max <s>] | stop [<out>] | cleanup" >&2; exit 1 ;;
    esac
    ;;
  utterances)
    sub="${1:-}"
    case "$sub" in
      "")    utterances_dump ;;
      clear) "$ADB" logcat -c; echo "Logcat cleared" ;;
      *) echo "Usage: $(basename "$0") utterances [clear]" >&2; exit 1 ;;
    esac
    ;;
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
  volume)
    if [[ $# -gt 1 ]] || { [[ $# -eq 1 ]] && ! [[ "$1" =~ ^[0-9]+$ ]]; }; then
      echo "Usage: volume [N]" >&2; exit 1
    fi
    # Read only the lines logged after the broadcast, without clearing logcat.
    since=$("$ADB" shell date +%s.000 | tr -d '[:space:]')
    if [[ $# -eq 1 ]]; then
      broadcast A11Y_VOLUME --ei level "$1"
    else
      broadcast A11Y_VOLUME
    fi
    sleep 0.4
    "$ADB" logcat -d -T "$since" -s TBGesture:D 2>&1 | grep "A11yVolume:" | tail -1 \
      || { echo "(aucun log : tbridge activé et fenêtre ouverte ? cf. 'status')" >&2; exit 1; }
    ;;
  font-scale)
    case "${1:-}" in
      "") ;;
      reset) "$ADB" shell settings put system font_scale 1.0 ;;
      *)
        if ! [[ "$1" =~ ^[0-9]+(\.[0-9]+)?$ ]]; then
          echo "Usage: font-scale [X|reset]  (ex. 1.3, 2.0)" >&2; exit 1
        fi
        "$ADB" shell settings put system font_scale "$1"
        ;;
    esac
    echo "font_scale = $("$ADB" shell settings get system font_scale | tr -d '[:space:]')"
    ;;
  display-size)
    phys=$("$ADB" shell wm density | grep -oE 'Physical density: [0-9]+' | grep -oE '[0-9]+')
    size=$("$ADB" shell wm size | grep -oE 'Physical size: [0-9]+x[0-9]+' | grep -oE '[0-9]+x[0-9]+')
    w=${size%x*}; h=${size#*x}; min_px=$(( w < h ? w : h ))
    # Largest step of Settings: smallest width of 320 dp, capped at 1.5x the default density.
    max_dpi=$(( 160 * min_px / 320 )); cap=$(( phys * 3 / 2 )); (( max_dpi > cap )) && max_dpi=$cap
    case "${1:-}" in
      "") ;;
      reset) "$ADB" shell wm density reset ;;
      max) "$ADB" shell wm density "$max_dpi" ;;
      *)
        if ! [[ "$1" =~ ^[0-9]+$ ]]; then
          echo "Usage: display-size [DPI|max|reset]" >&2; exit 1
        fi
        "$ADB" shell wm density "$1"
        ;;
    esac
    current=$("$ADB" shell wm density | grep -oE 'Override density: [0-9]+' | grep -oE '[0-9]+' || true)
    current=${current:-$phys}
    echo "densité = $current dpi (défaut $phys, max Réglages $max_dpi) · largeur = $(( min_px * 160 / current )) dp"
    ;;
  bold-text)
    case "${1:-}" in
      "") ;;
      on) "$ADB" shell settings put secure font_weight_adjustment 300 ;;
      off) "$ADB" shell settings put secure font_weight_adjustment 0 ;;
      *) echo "Usage: bold-text [on|off]" >&2; exit 1 ;;
    esac
    adj=$("$ADB" shell settings get secure font_weight_adjustment | tr -d '[:space:]')
    if [[ "$adj" =~ ^[1-9][0-9]*$ ]]; then echo "texte en gras : on ($adj)"; else echo "texte en gras : off"; fi
    ;;
  a11y-reset)
    "$ADB" shell settings put system font_scale 1.0
    "$ADB" shell wm density reset
    "$ADB" shell settings put secure font_weight_adjustment 0
    echo "Police 100 %, densité par défaut, gras désactivé"
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
