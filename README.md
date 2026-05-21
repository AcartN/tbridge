# tbridge

> Projet généré via IA (briefing + code).

Pont entre adb et TalkBack via un `AccessibilityService` custom.

Permet de simuler des **vrais gestes TalkBack** (next/previous element, activate, scroll…) depuis adb sur un device Android non rooté, en passant par `dispatchGesture()` — l'API officielle Android utilisée par Switch Access et autres outils accessibility.

## Pourquoi

Les approches adb classiques (`input swipe`, `input keyevent`, `sendevent`) ne marchent **pas** pour automatiser TalkBack :
- `input swipe` envoie des MotionEvents marqués synthétiques → consommés par TalkBack comme touch exploration (pas comme geste de navigation)
- `sendevent` au niveau kernel → bloqué par SELinux sur device non rooté
- Raccourcis clavier TalkBack → requièrent un clavier modifier physique

Seule voie viable : un `AccessibilityService` custom qui appelle `dispatchGesture()` et `performAction()`. Ces APIs passent par le pipeline système d'accessibilité et sont vues par TalkBack comme des gestes utilisateur réels.

## Cas d'usage

- Tests accessibility runtime automatisés (CI, scripts de QA)
- Debug TalkBack pendant le dev d'un fix accessibility
- Reproduction de bugs sensibles à la séquence de gestes

## Setup une-fois

1. `./gradlew installDebug` pour build + push l'APK
2. Sur le device : **Paramètres → Accessibilité → Services installés → tbridge** → activer.
   (Google bloque l'activation programmatique des services accessibility pour raisons de sécu, c'est manuel.)
3. Le service tourne **en parallèle de TalkBack**, pas en conflit (multiple AccessibilityServices actifs simultanément = OK).

## Usage

Wrapper bash `scripts/tb.sh` pour ergonomie :
```bash
./tb.sh right [N]        # N swipe-right TalkBack (défaut 1). Avance le focus.
./tb.sh left [N]         # N swipe-left (focus précédent).
./tb.sh up / down        # swipe vertical (1 seul)
./tb.sh tap [x] [y]      # tap simple (coords optionnelles, défaut = centre)
./tb.sh click            # ACTION_CLICK sur le node accessibility focused
./tb.sh longclick        # ACTION_LONG_CLICK
./tb.sh two-up           # swipe 2 doigts vers le haut (scroll TalkBack)
```

Sous le capot c'est un `adb shell am broadcast -a fr.acart.tbridge.gesture.<NOM> --es token <secret>`.

### Règle dure : `click` jamais chaîné, toujours raisonné

Les commandes de **navigation** (`right`/`left`) peuvent être enchaînées en `[N]` (ex: `./tb.sh right 5` envoie 5 broadcasts avec ~700ms entre, configurable via `TBRIDGE_INTER_DELAY`). C'est un déplacement réversible — on traverse la page.

Les commandes d'**action** (`click`, `longclick`, `tap`) sont **toujours unitaires** et **jamais chaînées** avec autre chose. Avant chaque `click` :
1. Lire l'utterance TalkBack la plus récente (`adb logcat | grep speakWithSpeechCachePlayer`)
2. Confirmer que c'est bien l'élément voulu (label, role)
3. Raisonner sur la conséquence (cette action est-elle réversible ? destructive ? requiert biométrique ?)
4. Seulement alors → `./tb.sh click`

Une action irréfléchie = un état corrompu (élément cliqué dans le vide, action bancale, biométrique sauté). Comme un user TalkBack aveugle : on n'active rien sans avoir entendu et compris ce qui est focused.

## Vérification

Avec TalkBack actif :
```bash
./tb.sh right
# → focus TalkBack avance d'un élément, vocalisation émise
```

Capture des utterances TalkBack en logcat (paramètres développeur TalkBack à activer une fois) :
```bash
adb logcat -s SpeechCacheManager:D | grep "speakWithSpeechCachePlayer"
```

## Sécurité

L'app expose son `BroadcastReceiver` en `RECEIVER_EXPORTED` (requis pour qu'adb puisse envoyer les broadcasts). N'importe quelle app installée sur le device peut donc déclencher les gestes — **outil de dev/test, ne jamais publier sur le Play Store en l'état**.

## Status

Initial brief. Implémentation à venir.
