# SecondScreen

Application Android qui lance une application installée sur l'écran externe (HDMI) pendant que le téléphone garde son propre écran.

Le lancement se fait via `ActivityOptions.setLaunchDisplayId`, en ciblant l'écran secondaire connecté au téléphone.

## Obtenir l'APK sans PC

**Le plus simple (depuis le téléphone) :** page **Releases** du dépôt → release **latest** → télécharger `SecondScreen.apk`. Elle est mise à jour automatiquement à chaque push sur `main`.

Autre méthode, via les artifacts :

1. Sur GitHub, ouvrir l'onglet **Actions** du dépôt.
2. Ouvrir le dernier run **Build APK** réussi (coche verte).
3. Dans la section **Artifacts**, télécharger `SecondScreen-debug-apk`. Le fichier téléchargé est une archive zip contenant `app-debug.apk`.
4. Dézipper l'archive sur le téléphone.
5. Installer `app-debug.apk` (autoriser les sources inconnues si le système le demande).

Remarques :

- Le téléchargement d'un artifact nécessite d'être connecté à GitHub.
- Les artifacts expirent au bout de 90 jours.
- Pour générer un nouvel APK, lancer le workflow manuellement : onglet **Actions** → **Build APK** → **Run workflow** (déclencheur `workflow_dispatch`).

## Dépannage

Si le système refuse le lancement ou si l'application s'ouvre sur le téléphone au lieu de l'écran externe :

- Activer les **Options pour les développeurs** puis, dans les réglages, activer :
  - « Forcer les activités à être redimensionnables »
  - « Activer les fenêtres de forme libre »
  - « Forcer le mode bureau »
- Redémarrer le téléphone.
- Certaines applications et certaines surcouches constructeur bloquent le lancement sur un écran secondaire.
- L'adaptateur HDMI doit exposer un véritable écran secondaire, et non simplement un miroir de l'écran du téléphone. Le téléphone doit donc supporter la sortie vidéo USB-C DisplayPort Alt Mode.
