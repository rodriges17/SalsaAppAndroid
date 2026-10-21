# Step by Step – Native Android

Eigenständige native Android-App für lokale Salsa-Tanzfiguren und Videos, angelehnt an das Design von [SalsaAppCloud](https://github.com/rodriges17/SalsaAppCloud).

## Funktionen (erste native Version)
- Figuren mit Name, Beschreibung, Tags und Lernstand (Neu / Am Üben / Sitzt)
- lokale Videodatei über Androids Dateiauswahl auswählen und **vor dem Speichern ansehen und bestätigen**
- persistente Datei-URI-Berechtigung, lokale Figurenverwaltung und Video-Vorschaubilder
- Suche und Filter; Einstellungen
- Lernplayer mit Geschwindigkeiten 0,25× / 0,5× / 0,75× / 1×, 5 Sekunden zurück, Loop und A–B
- Hochformat zu Beginn des Players; manuell zwischen Hoch- und Querformat wechseln
- Fit (Standard) und Crop (bildschirmfüllend)

**Abgrenzung:** Noch kein Cloud-Sync, ZIP-Import/Export und keine vollständige Funktionsparität zur Web-App. Videos bleiben am vom Android-Dateiauswahldialog gewählten Speicherort; Android gewährt einen persistenten Lesezugriff, solange Datei und Berechtigung bestehen. Die App speichert kein zweites Video. Die native App verwendet unabhängig vom Browser eigene Daten.

## Build
Android Studio (JDK 17) öffnen, Gradle synchronisieren und `assembleDebug` starten. Alternativ lokal mit installiertem Gradle 8.9 und Android SDK:

```sh
gradle :app:assembleDebug
```

Die APK liegt dann unter `app/build/outputs/apk/debug/app-debug.apk`.

Ein fertiger APK-Build wurde durch diesen Commit **nicht** ausgeführt; das Projekt enthält derzeit keinen Gradle Wrapper. 
