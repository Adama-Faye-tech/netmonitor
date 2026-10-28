# NetMonitor — moniteur de qualité réseau Android

Surveille Wi-Fi et données mobiles en arrière-plan et classe le réseau en **4 états exclusivement** : Excellent, Moyen, Faible, Mauvais. Une notification d'alerte est émise à chaque changement d'état confirmé.

Deux versions, **même service natif** (`kotlin-app/.../NetworkMonitorService.kt`) :

| Dossier | Contenu |
|---|---|
| `kotlin-app/` | App Android native complète (UI en XML, aucune dépendance lourde) |
| `flutter-app/` | UI Flutter + pont natif ; réutilise le service Kotlin de `kotlin-app/` |

## Architecture (et pourquoi elle est légère)

- **Pas de polling pour détecter un changement** : `registerDefaultNetworkCallback` prévient le service (Wi-Fi ↔ mobile, perte, retour d'internet).
- **Re-test adaptatif** : toutes les 30 s si Faible/Mauvais ; 60 s → 2 min → 5 min tant que l'état est stable ; durées doublées écran éteint.
- **Anti-clignotement** : un nouvel état doit être mesuré 2 fois (re-mesure après 8 s) avant l'alerte. La perte totale de connexion alerte immédiatement.
- **Ping** : handshake TCP vers 1.1.1.1:443 (l'ICMP est interdit sans root), 8 échantillons liés au `Network` actif. **Gigue** = moyenne des écarts entre RTT consécutifs. **Perte** = échecs / 8.
- **Débit** : 1 Mo, rare (15 min en Wi-Fi, 30 min en mobile), jamais sur un réseau déjà mauvais.
- **Signal** : lecture passive (RSSI Wi-Fi ; niveau cellulaire), aucun scan radio.
- Aucun WakeLock. Le processus ne travaille qu'au réveil d'un cycle.

## Seuils (modifiables dans `QualityEvaluator`, `NetworkQuality.kt`)

Évalués du pire au meilleur ; le premier critère rempli gagne.

| État | Condition |
|---|---|
| Mauvais | pas d'internet, ou perte > 0 %, ou ping > 100 ms, ou gigue > 40 ms |
| Faible | ping 51–100 ms, ou gigue 21–40 ms, ou signal faible |
| Excellent | ping < 20 ms, gigue < 5 ms, et signal très fort **ou** débit > 25 Mbps |
| Moyen | tout le reste (ping 20–50, gigue 5–20, signal modéré) |

Si le signal est illisible (certains appareils), il ne pénalise pas : ping et gigue décident.

---

## Version Kotlin — compiler et installer

### 1. Préparer le téléphone
1. Paramètres → À propos du téléphone → touchez 7 fois « Numéro de build ».
2. Paramètres → Options pour les développeurs → activez **Débogage USB**.
3. Branchez le câble USB, acceptez « Autoriser le débogage USB ».

### 2. Option A — Android Studio (le plus simple)
1. Installez Android Studio (inclut SDK et JDK).
2. **Open** → sélectionnez le dossier `kotlin-app/`. Laissez la synchronisation Gradle se terminer (acceptez la proposition de mise à jour d'AGP si elle apparaît).
3. Choisissez votre téléphone dans la barre du haut, puis **Run ▶**.

### 3. Option B — ligne de commande
Prérequis : JDK 17+, Android SDK (`ANDROID_HOME` défini, plateforme 35 installée).

```bash
cd kotlin-app
./gradlew assembleRelease          # Windows : gradlew.bat assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Sans câble : copiez l'APK sur le téléphone, ouvrez-le, autorisez « Installer des applis inconnues ».
(Le build release est signé avec la clé de debug : suffisant pour un usage personnel, pas pour le Play Store.)

### 4. Premier lancement
1. Autorisez les **notifications** (Android 13+).
2. La surveillance démarre seule ; une notification permanente discrète apparaît.
3. **Autonomie** : Paramètres → Applications → NetMonitor → Batterie → « Sans restriction » si votre constructeur (Xiaomi, Huawei, Samsung…) tue le service.

---

## Version Flutter — compiler et installer

Prérequis : Flutter SDK à jour (`flutter doctor` sans erreur Android).

```bash
cd flutter-app
flutter create --org com.netmonitor --project-name app --platforms=android netmonitor_flutter
./apply.sh netmonitor_flutter
cd netmonitor_flutter
flutter run                         # téléphone branché, débogage USB actif
flutter build apk --release         # APK : build/app/outputs/flutter-apk/app-release.apk
adb install -r build/app/outputs/flutter-apk/app-release.apk
```

`apply.sh` copie l'UI Dart, le pont natif et le service Kotlin partagé, fixe `minSdk` à 24 et ajoute deux dépendances. Les deux versions ont le même `applicationId` : désinstallez l'une avant d'installer l'autre.

Pourquoi le service reste natif : un service Dart en arrière-plan impose de garder un moteur Flutter vivant (plus de RAM et de batterie). Ici Dart n'écoute le flux d'état que lorsque l'écran est visible.

---

## Tester sur le téléphone

1. **Changement de connexion** : coupez/activez le Wi-Fi → l'état se met à jour en quelques secondes.
2. **Mode avion** : alerte immédiate « Mauvais » (aucun accès internet).
3. **Mauvais réseau simulé** : éloignez-vous du routeur, ou utilisez un hotspot saturé → passage à Faible/Mauvais après confirmation (~8 s).
4. **Logs** : `adb logcat | grep -i netmonitor`.
5. **Batterie** : `adb shell dumpsys batterystats --reset`, laissez 24 h, puis `adb shell dumpsys batterystats | grep -A5 netmonitor`.

## Limites connues
- Le ping est un temps de connexion TCP, pas un ICMP : il suit de près le ping classique mais peut en différer de quelques ms.
- Le code n'a pas pu être compilé dans l'environnement de rédaction (dépôts Android bloqués) ; seule la logique des 4 états a été testée (16 cas limites, tous passés). Si Gradle signale une erreur à la première compilation, envoyez-moi le message exact.
