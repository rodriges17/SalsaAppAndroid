# Step by Step – Android

Native Kotlin Android-App für Salsa-Videos (Android 8.0+).

## Features
- Offline-Figurenverwaltung mit Name, Tags, Beschreibung und Lernstand.
- Lokale Android-Dateiauswahl für Videos mit verpflichtender Vorschau vor Übernahme.
- Vorschaubild aus ausgewähltem Video und Filter/Suche.
- Lernplayer: 0,25× / 0,5× / 0,75× / 1×, −5 Sekunden, A–B-Schleife, Gesamt-Loop.
- Fit als Standard, Crop als Option, manueller Hoch-/Querformatwechsel.
- Einstellungsansicht.

## APK herunterladen
[GitHub Actions → Android APK](https://github.com/rodriges17/SalsaAppAndroid/actions/workflows/android-apk.yml) öffnen, den neuesten **erfolgreichen** Lauf wählen und unter **Artifacts** das ZIP `StepByStep-Android-debug-apk` herunterladen. ZIP entpacken und `app-debug.apk` auf Android installieren.

Der Build erfolgt automatisch bei jedem Push auf `main` und kann über **Run workflow** manuell ausgelöst werden.

Die APK ist als Debug-Build signiert, nicht für den Google Play Store geeignet. Eine lokal installierte Debug-APK sollte nur aus vertrauenswürdigen Quellen installiert werden.

## Lokal bauen
Mit Android Studio und JDK 17 importieren. Alternativ Gradle 8.9 installieren und `gradle :app:assembleDebug` ausführen.

## Noch ausstehend
Keine vollständige Funktionsparität mit SalsaAppCloud: insbesondere Cloud-Synchronisation, Bibliothek, ZIP-Import/Export, Mehrfachvideos pro Figur und vollständiger Backup-Workflow sind noch nicht umgesetzt. Aktuelle App-Daten sind getrennt von PWA-Daten. Native Tests auf echten Geräten stehen aus.
