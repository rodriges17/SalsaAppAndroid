# Step by Step – Android 0.7.6

Android-App ab Android 8 (API 26) mit derselben Oberfläche wie SalsaAppCloud: identische Seiten, Navigation, Karten, Dialoge und Hell-/Dunkeldesign. Die Oberfläche ist lokal im APK enthalten; eine Internetverbindung ist nur für Cloud-Inhalte erforderlich.

## Funktionsumfang

- Figuren mit Titel, Beschreibung, Lernstand, Tags, Suche, Tag-/Lernstandfiltern, Sortierung und Vorschaubild.
- Mehrere Videos je Figur: hinzufügen, einzeln abspielen und bestätigen, umbenennen, sortieren, ersetzen und entfernen. Neue oder importierte Videos müssen vor dem Speichern bestätigt sein. Ungespeicherte Änderungen haben einen Verwerfen-Dialog.
- Lernplayer mit Videowahl, 0,25× / 0,5× / 0,75× / 1×, fünf Sekunden zurück, Fit/Crop ohne Verzerrung, Gesamtloop und A–B-Schleife. Gesamtloop und A–B schließen sich aus. Videowechsel entfernt A–B und behält Tempo, Skalierung und Gesamtloop. Start im Hochformat, manueller Formatwechsel und immersiver Vollbildmodus. Hintergrundwechsel pausiert; der Bildschirm bleibt beim Abspielen wach.
- ZIP-Austausch im PWA-Format mit Videoauswahl, Größen-/Struktur-/CRC-Prüfung, Hinweis bei wiederholtem Import und Bestätigung der importierten Videos. Speichern und Teilen verwenden Android-Systemdialoge. ZIP enthält keine persönlichen Lernstände.
- Vollständige, mehrteilige Backups inklusive Videos, Vorschaubildern, Erstellungsdatum und Lernständen. Wiederherstellung prüft alle Teile, zeigt neue/identische/konfliktbehaftete Figuren und bietet Behalten, Kopieren und ausdrücklich gewähltes Ersetzen.
- Geräteordner über Androids Storage Access Framework mit dauerhaft gespeicherten Berechtigungen; PWA-kompatible `SalsaTrainer/library.json` und `media/`. App- und Ordnerfiguren stehen gemeinsam in der Übersicht. Geprüftes Übertragen in beide Richtungen; bisherige Kopien werden erst nach zusätzlicher Bestätigung gelöscht. Verlorene Berechtigungen lassen sich durch erneute Auswahl des Ordners verbinden.
- Cloud: öffentliche/private Bibliotheken, persönliche Anmeldelinks, eigene Cloud-Figuren, Videoauswahl und Download in den lokalen Bestätigungseditor, Upload neuer Figuren/Versionen, Versionsverlauf, öffentliche Freigaben, Bibliothekszuordnungen, Moderation, Mitglieder und Bibliothekseinstellungen. Berechtigte Administratoren erhalten Benutzer-/Quotenverwaltung und Anmeldelinkerstellung. Die bestehenden serverseitigen Berechtigungen und Limits gelten weiter (50 MB pro Video).
- Hell-/Dunkeldesign. Android-Teilen-Empfang für einzelne/mehrere Videos, Figuren-ZIP und Anmeldelinks. APK-Oberfläche und lokale Videos funktionieren ohne Internet; Cloud benötigt Internet.

## Architektur und bestehende Daten

`CloudUiActivity` startet die originale, lokal gebündelte SalsaAppCloud-Oberfläche in einer Android-WebView. Layout, Styles, Icons und Bedienabläufe entsprechen SalsaAppCloud (Referenzstand `648144142c8abd3563991119fdb09a7f4f56afb3`). Eine separate Android-Layoutdatei fixiert die Kopfzeilen beim Scrollen und hält die kompakte Navigation bündig am unteren Rand; Systemabstände werden nur einmal vom Android-Container berücksichtigt. Android-Brücken ergänzen Dateiauswahl, dauerhaft verbundene Ordner, Speichern/Teilen, Bildschirmrotation und Vollbild. Die bisherigen Kotlin-Ansichten bleiben intern für die bestehenden Regressionstests erhalten, sind aber nicht mehr der App-Startbildschirm. Bestehende Android-Daten werden weiter unter `https://appassets.androidplatform.net/assets/` verwendet.

Ein Startschritt außerhalb des alten Service-Worker-Bereichs entfernt veraltete Android-App-Shells. Figuren, Medien-Caches und Ordnerberechtigungen bleiben dabei erhalten; ein Integrationstest prüft diesen Updatefall.

Die native HTTPS-Brücke ist auf den konfigurierten Supabase-Host und dessen API-/Auth-/Storage-Pfade beschränkt. Sie überträgt Dateien in begrenzten Blöcken und umgeht die für Browser geltende Origin-Beschränkung der bestehenden PWA-API. Administrative Schlüssel werden nicht in die App eingebettet; Berechtigungen bleiben serverseitig geprüft.

Bereits mit Version 0.6 übernommene Daten stehen unverändert zur Verfügung. Die frühere separate Kotlin-Offline-Ansicht wird durch diese Oberfläche nicht geöffnet. Separat im Browser installierte PWAs behalten ihren eigenen Speicher; deren Daten können per ZIP oder vollständigem Backup übernommen werden. Das Backend selbst ist nicht im APK enthalten.

## APK installieren

[GitHub Actions – Android APK](https://github.com/rodriges17/SalsaAppAndroid/actions/workflows/android-apk.yml) → erfolgreicher aktueller Lauf → **Artifacts → StepByStep-Android-debug-apk**. ZIP entpacken und `app-debug.apk` installieren. GitHub-Anmeldung kann für Artefakte erforderlich sein. Die APK ist eine Testversion. Veröffentlichte APKs verwenden einen festen privaten Signierschlüssel aus GitHub Actions Secrets; ihr Zertifikat wird vor dem Upload gegen einen hinterlegten SHA-256-Fingerabdruck geprüft. Fehlen Schlüssel oder Fingerabdruck, bricht die Veröffentlichung ab. Ein Update-Test installiert eine vorherige Versionsnummer und anschließend die neue APK mit `adb install -r` und prüft, dass App-Daten erhalten bleiben.

Frühere Versionen bis einschließlich 0.7.2 wurden wegen eines fehlerhaften Cache-Pfads mit wechselnden temporären Schlüsseln signiert. Deren private Schlüssel sind nicht wiederherstellbar, sofern keine separate Sicherung existiert. Vor dem einmaligen Wechsel auf die neue Signatur ein vollständiges Backup außerhalb des App-Speichers sichern und die alte Installation danach ersetzen. Alle folgenden Updates müssen denselben neuen Schlüssel verwenden.

## Einmalige Einrichtung der Signierung

GitHub → Settings → Secrets and variables → Actions:

- Secret `STEPBYSTEP_SIGNING_KEYSTORE_BASE64`: Base64-Inhalt einer privat gesicherten PKCS12-Keystore-Datei mit Schlüsselalias `stepbystep`.
- Secret `STEPBYSTEP_SIGNING_PASSWORD`: gemeinsames Store-/Schlüsselpasswort.
- Variable `STEPBYSTEP_SIGNING_CERT_SHA256`: SHA-256 des öffentlichen Signierzertifikats, 64 Hex-Zeichen (Doppelpunkte sind erlaubt).

Der Schlüssel wird einmalig erzeugt und zusätzlich an einem sicheren Ort gesichert. Keystore und Passwort gehören niemals in das Repository, in Logs oder in ein öffentliches Actions-Artefakt. Der Actions-Cache wird zur Schlüsselablage nicht mehr verwendet. Den Zertifikats-Fingerabdruck nur bei einem ausdrücklich beabsichtigten Identitätswechsel ändern.


## Entwicklung und Prüfungen

Java 17, Android SDK 35, Build Tools 35.0.0 und Gradle 8.9:

```sh
npm ci
npm test
npx playwright install chromium
npm run test:bridge
gradle :app:testDebugUnitTest :app:assembleDebug
gradle :app:connectedDebugAndroidTest
```

- 49 Tests der übernommenen Logik prüfen unter anderem Originalbytes, ZIP-Validierung, Quoten-/Schreibfehler, Transaktionskonflikte, Backup-Konflikte und Sitzungserneuerung.
- Chromium-Integrationstests prüfen den nativen Modellvertrag einschließlich vorhandener PWA-Daten, Entwurfsänderungen, Medienbytes, ZIP-Bestätigungspflicht, Backup-Wiederherstellung, persistierter Android-Ordnerverweise und HTTPS-Abbruch.
- Kotlin-Tests prüfen den Lernzustand und die Ausschließlichkeit der Wiederholungsmodi.
- Android-Instrumentierung prüft native Navigation, den Verwerfen-Dialog, Bestätigung mehrerer geteilter Videos und den Player im Emulator. Der Workflow archiviert deren Berichte separat.

Der aktuelle CI-Lauf ist die Quelle für tatsächliche Testergebnisse. Physische Gerätetests mit unterschiedlichen Dokumentanbietern, großen Dateien und Codecs sowie angemeldete Cloud-Rollen bleiben zusätzlich erforderlich. Automatisch verifizierte HTTPS-App-Links benötigen weiterhin eine passende Domain-Zuordnung; Einfügen oder Teilen des persönlichen Anmeldelinks funktioniert unabhängig davon.

## Quellcode

- `NativeActivity.kt`: native Übersicht, Editor, Vorschauen und Navigation.
- `NativePlayer.kt` / `PlaybackState.kt`: nativer Player und Lernsteuerung.
- `NativeWorkflows.kt`: native Cloud-, Ordner-, Austausch- und Backup-Ansichten.
- `NativeFiles.kt`, `NativeMedia.kt`, `NativeNetwork.kt`, `WebAppActivity.kt`: Android-Brücken und begrenzter lokaler Asset-Host.
- `app/src/main/assets/`: PWA-Module und Android-Adapter. Webstand: SalsaAppCloud vom 21.10.2026, ergänzt um Android-Integrationen.

Geräteordner-Videos werden im Lernplayer einzeln bei Auswahl geladen und weiterhin vor der Wiedergabe auf Originalgröße und CRC geprüft. Bereits in diesem Player geladene Videos werden beim Zurückwechseln wiederverwendet; beim erneuten Öffnen der Figur erfolgt eine neue Prüfung. Export, Bearbeitung und Backup prüfen weiterhin alle benötigten Originaldateien.
