# tbridge — brief technique d'implémentation

## Contexte

App Android compagnon pour automatiser TalkBack via adb. Conçu après validation empirique sur Pixel 6 / Android 16 / TalkBack 17 que `input swipe`, `input touchscreen swipe` et `sendevent` ne sont pas vus par TalkBack comme gestes de navigation (interprétés comme touch exploration ou bloqués par SELinux).

La voie qui marche : un `AccessibilityService` custom utilisant `dispatchGesture()` + `performAction()`, exposé via broadcasts adb. Approche standard des outils accessibility (Switch Access, etc.).

## Cible

- Repo : `AcartN/tbridge`
- Package : `fr.acart.tbridge`
- minSdk : 28 (Android 9), targetSdk : 34
- Langage : Kotlin
- Build : Gradle Kotlin DSL (`build.gradle.kts`)
- Architecture : minimaliste — 1 Activity + 1 AccessibilityService

## Composants

### 1. `TalkBackGestureService.kt` (AccessibilityService)

- Config XML avec `canPerformGestures="true"` + `canRetrieveWindowContent="true"`
- Enregistre dynamiquement un `BroadcastReceiver` dans `onServiceConnected()`, désenregistre dans `onDestroy()`
- Receiver en `RECEIVER_EXPORTED` (Android 13+)
- Action prefix : `fr.acart.tbridge.gesture.<NOM>`

#### Actions à exposer

| Action broadcast | Comportement |
|---|---|
| `SWIPE_RIGHT` | Geste swipe horizontal gauche → droite au centre de l'écran |
| `SWIPE_LEFT` | Swipe horizontal droite → gauche |
| `SWIPE_UP` | Swipe vertical bas → haut |
| `SWIPE_DOWN` | Swipe vertical haut → bas |
| `TAP` | Tap simple à coordonnées custom (`--ef x 540 --ef y 1200`), fallback centre |
| `CLICK_FOCUSED` | `performAction(ACTION_CLICK)` sur le node accessibility focused — équivalent fiable et déterministe du double-tap TalkBack |
| `LONG_CLICK_FOCUSED` | `performAction(ACTION_LONG_CLICK)` sur le node focused |
| `SWIPE_TWO_FINGERS_UP` | Geste 2 doigts vers le haut (`addStroke()` x2 sur la même `GestureDescription.Builder`) |

#### Paramètres gestes

- Swipes : durée ~200ms, distance ~600px sur l'axe concerné, partant du centre
- Tap : durée 50ms
- **Coords adaptatives via `windowManager.currentWindowMetrics.bounds`** — pas de hardcode 1080×2400

#### Détails impl à respecter

- `findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)` explicitement (pas `FOCUS_INPUT`)
- Gérer `findFocus()` retournant `null` (aucun focus actuel) — log + no-op
- `dispatchGesture(callback)` : log le résultat (`onCompleted` vs `onCancelled`) — utile pour debug si geste rejeté par le système
- `Log.d("TBGesture", "Action: $action")` à chaque broadcast reçu — pour vérifier dans logcat
- Pas de `!!` sauvages, `.let` / `.also` pour les nullables

### 2. `res/xml/accessibility_service_config.xml`

```xml
accessibilityEventTypes="typeAllMask"
accessibilityFeedbackType="feedbackGeneric"
canPerformGestures="true"
canRetrieveWindowContent="true"
notificationTimeout="100"
description="@string/accessibility_service_description"
```

### 3. `MainActivity.kt`

UI minimale (View XML ou Compose, au choix — préférence Compose pour s'aligner sur le contexte courant) :
- Texte d'explication
- Bouton "Ouvrir les paramètres d'accessibilité" → `Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)`
- Indicateur "service activé / désactivé" — vérif via `Settings.Secure.getString(contentResolver, "enabled_accessibility_services")`
- Refresh statut dans `onResume()`

### 4. `AndroidManifest.xml`

- Activity launcher, exported
- Service avec `permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`, exported
- `intent-filter` du service sur `android.accessibilityservice.AccessibilityService`
- `meta-data` pointant vers le XML config

### 5. `scripts/tb.sh`

Wrapper bash CLI :
```bash
./tb.sh right            # SWIPE_RIGHT
./tb.sh left             # SWIPE_LEFT
./tb.sh up / down        # SWIPE_UP / SWIPE_DOWN
./tb.sh tap [x] [y]      # TAP (coords optionnelles)
./tb.sh click            # CLICK_FOCUSED
./tb.sh longclick        # LONG_CLICK_FOCUSED
./tb.sh two-up           # SWIPE_TWO_FINGERS_UP
```

- `#!/usr/bin/env bash` + `set -euo pipefail`
- Aide affichée si appelé sans argument
- Utilise `adb shell am broadcast -a fr.acart.tbridge.gesture.<NOM>` en interne

## Contraintes code

- Kotlin idiomatique, fonctions courtes, nommées explicitement
- Zéro dépendance tierce (pas de DI, pas de coroutines)
- Gestion propre des erreurs `dispatchGesture` (callback peut retourner false si rejeté)
- Compatible Android 9 (API 28) → 14+

## Vérification finale (acceptance test)

Sur Pixel 6 / Android 16 / TalkBack actif, ces commandes doivent fonctionner :

```bash
adb shell am broadcast -a fr.acart.tbridge.gesture.SWIPE_RIGHT
# → focus TalkBack avance d'un élément, vocalisation émise

adb shell am broadcast -a fr.acart.tbridge.gesture.CLICK_FOCUSED
# → l'élément focused est cliqué

adb logcat -s SpeechCacheManager:D
# → montre les utterances TalkBack avec le pattern :
# speakWithSpeechCachePlayer:speechInfo=SpeechInfo{utteranceId=..., text=..., ...}
```

## Livrables

- Projet Android Studio complet, prêt à `./gradlew installDebug`
- Script `scripts/tb.sh` exécutable
- README.md (déjà rédigé)
- Commits propres avec messages descriptifs

## Notes

- **Activation manuelle obligatoire** une fois : Paramètres → Accessibilité → Services installés → tbridge → activer. Google bloque l'activation programmatique des services accessibility custom pour raisons sécu.
- **Service tourne en parallèle de TalkBack** — pas de conflit, on peut avoir N services accessibility actifs simultanément (déjà observé empiriquement : TalkBack + Bitwarden ensemble).
- **Sécu** : `RECEIVER_EXPORTED` requis pour adb broadcasts → n'importe quelle app peut déclencher les gestes. OK pour dev/test, jamais Play Store.
