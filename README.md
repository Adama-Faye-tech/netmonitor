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
- **Débits descendant et montant** : deux tests de 1 Mo, rares (15 min en Wi-Fi, 30 min en mobile), jamais sur un réseau déjà mauvais. Le montant est affiché séparément et n'influence pas le score ; le descendant utilise un seuil adapté au transport.
- **Signal** : lecture passive (RSSI Wi-Fi ; niveau cellulaire), aucun scan radio.
- Aucun WakeLock ni animation continue de l'interface. L'image de fond est dessinée une fois ; le service ne mesure le réseau qu'aux changements détectés ou au réveil d'un cycle adaptatif.

## Seuils (modifiables dans `QualityEvaluator`, `NetworkQuality.kt`)

Évalués du pire au meilleur ; le premier critère rempli gagne.

| État | Condition |
|---|---|
| Mauvais | pas d'internet, ou perte > 0 %, ou ping > 100 ms, ou gigue > 40 ms |
| Faible | ping 51–100 ms, ou gigue 21–40 ms, ou signal faible |
| Excellent | ping < 20 ms, gigue < 5 ms, et signal très fort **ou** débit descendant > 25 Mbps (Wi-Fi/autre) ou > 5 Mbps (cellulaire) |
| Moyen | tout le reste (ping 20–50, gigue 5–20, signal modéré) |

Si le signal est illisible (certains appareils), il ne pénalise pas : ping et gigue décident. Un débit descendant de plus de 5 Mbps peut remplacer le signal très fort sur le cellulaire, contre 25 Mbps en Wi-Fi/autre.

## Onglet « Usages »

Les indicateurs sont calculés à partir du débit descendant mesuré et, pour le cloud gaming, du ping :

| Usage | Seuil |
|---|---|
| Cloud gaming, 1080p 60 fps / 4K | ≥ 25 Mbps, ping < 30 ms, gigue ≤ 20 ms et aucune perte |
| Cloud gaming, 720p | ≥ 15 Mbps, ping ≤ 40 ms, gigue ≤ 20 ms et aucune perte |
| Film UHD / 4K | ≥ 25 Mbps |
| Film HD / 1080p | ≥ 5 Mbps |
| Web et musique | ≥ 1 Mbps |

Tant qu'une mesure nécessaire n'est pas disponible ou que la surveillance est arrêtée, l'indicateur reste « — » plutôt que d'afficher un résultat trompeur. Un débit suffisant conserve le badge « Oui » pour le streaming et le Web ; si le diagnostic est « Mauvais » à cause de pertes de paquets, un avertissement signale le risque de coupures. Les temps estimés utilisent `taille en Mo × 8 / débit descendant en Mbps` avec des fichiers types de 5 Mo, 1 500 Mo et 7 000 Mo. Ce sont des estimations théoriques : la charge réseau et les serveurs peuvent allonger le téléchargement réel.

## Identité visuelle

L'interface utilise un thème noir et néon avec l'image locale `IMG.jpeg`, assombrie par un calque noir à 80 %, des onglets translucides et un bouton de surveillance bordé de vert. L'arrière-plan est une image statique : aucune boucle de rendu, aucun cube animé et aucune animation d'introduction ne sollicitent le processeur en continu. Le script Flutter copie également l'image dans les assets du projet généré.

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
- La compilation Android debug a été validée avec `gradlew assembleDebug`. Les tests de débit utilisent Cloudflare et peuvent échouer si le réseau bloque ce service.
