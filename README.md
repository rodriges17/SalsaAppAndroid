# Step by Step – Android 0.6

Android-App ab Android 8 (API 26), mit nativen Kotlin-Ansichten für Figuren, Editor, Lernplayer, Cloud, Einstellungen und Backups. App-Symbol und Header verwenden das Step-by-Step-Logo.

## Funktionsumfang

- Figuren mit Titel, Beschreibung, Lernstand, Tags, Suche, Tag-/Lernstandfiltern, Sortierung und Vorschaubild.
- Mehrere Videos je Figur: hinzufügen, einzeln abspielen und bestätigen, umbenennen, sortieren, ersetzen und entfernen. Neue oder importierte Videos müssen vor dem Speichern bestätigt sein. Ungespeicherte Änderungen haben einen Verwerfen-Dialog.
- Nativer Lernplayer mit Videowahl, 0,25× / 0,5× / 0,75× / 1×, fünf Sekunden zurück, Fit/Crop ohne Verzerrung, Gesamtloop und A–B-Schleife. Gesamtloop und A–B schließen sich aus. Videowechsel entfernt A–B und behält Tempo, Skalierung und Gesamtloop. Start im Hochformat, manueller Formatwechsel und immersiver Vollbildmodus. Hintergrundwechsel pausiert; der Bildschirm bleibt beim Abspielen wach.
- ZIP-Austausch im PWA-Format mit Videoauswahl, Größen-/Struktur-/CRC-Prüfung, Hinweis bei wiederholtem Import und Bestätigung der importierten Videos. Speichern und Teilen verwenden Android-Systemdialoge. ZIP enthält keine persönlichen Lernstände.
- Vollständige, mehrteilige Backups inklusive Videos, Vorschaubildern, Erstellungsdatum und Lernständen. Wiederherstellung prüft alle Teile, zeigt neue/identische/konfliktbehaftete Figuren und bietet Behalten, Kopieren und ausdrücklich gewähltes Ersetzen.
- Geräteordner über Androids Storage Access Framework mit dauerhaft gespeicherten Berechtigungen; PWA-kompatible `SalsaTrainer/library.json` und `media/`. App- und Ordnerfiguren stehen gemeinsam in der Übersicht. Geprüftes Übertragen in beide Richtungen; bisherige Kopien werden erst nach zusätzlicher Bestätigung gelöscht. Verlorene Berechtigungen lassen sich durch erneute Auswahl des Ordners verbinden.
- Cloud: öffentliche/private Bibliotheken, persönliche Anmeldelinks, eigene Cloud-Figuren, Videoauswahl und Download in den lokalen Bestätigungseditor, Upload neuer Figuren/Versionen, Versionsverlauf, öffentliche Freigaben, Bibliothekszuordnungen, Moderation, Mitglieder und Bibliothekseinstellungen. Berechtigte Administratoren erhalten Benutzer-/Quotenverwaltung und Anmeldelinkerstellung. Die bestehenden serverseitigen Berechtigungen und Limits gelten weiter (50 MB pro Video).
- Hell-/Dunkeldesign. Android-Teilen-Empfang für einzelne/mehrere Videos, Figuren-ZIP und Anmeldelinks. APK-Oberfläche und lokale Videos funktionieren ohne Internet; Cloud benötigt Internet.

## Architektur und bestehende Daten

Die sichtbaren Ansichten und der Player sind native Android-Views. Ein nicht sichtbarer WebView führt die aus SalsaAppCloud übernommenen JavaScript-Module für IndexedDB, Cache Storage, Ordnerkataloge, ZIP, Backup und Cloud-Verträge aus. Das ist **keine vollständige Neuschreibung der Geschäftslogik in Kotlin**. Dadurch nutzen native Ansichten und die erhaltene Standardansicht dieselbe bestehende Android-Bibliothek unter `https://appassets.androidplatform.net/assets/` statt zwei getrennte Datenbestände.

Ein Startschritt außerhalb des alten Service-Worker-Bereichs entfernt veraltete Android-App-Shells. Figuren, Medien-Caches und Ordnerberechtigungen bleiben dabei erhalten; ein Integrationstest prüft diesen Updatefall.

Die native HTTPS-Brücke ist auf den konfigurierten Supabase-Host und dessen API-/Auth-/Storage-Pfade beschränkt. Sie überträgt Dateien in begrenzten Blöcken und umgeht die für Browser geltende Origin-Beschränkung der bestehenden PWA-API. Administrative Schlüssel werden nicht in die App eingebettet; Berechtigungen bleiben serverseitig geprüft.

Daten der früheren separaten Kotlin-Offline-Ansicht werden beim ersten Start kopiert, sofern deren ursprüngliche Video-URIs noch lesbar sind. Alte Daten werden dabei nicht gelöscht. Separat im Browser installierte PWAs behalten ihren eigenen Speicher; deren Daten können per ZIP oder vollständigem Backup übernommen werden. Das Backend selbst ist nicht im APK enthalten.

## APK installieren

[GitHub Actions – Android APK](https://github.com/rodriges17/SalsaAppAndroid/actions/workflows/android-apk.yml) → erfolgreicher aktueller Lauf → **Artifacts → StepByStep-Android-debug-apk**. ZIP entpacken und `app-debug.apk` installieren. GitHub-Anmeldung kann für Artefakte erforderlich sein. Die APK ist eine mit dem Debug-Schlüssel signierte Testversion. Der Workflow behält diesen Schlüssel für kommende Builds im Actions-Cache. Ältere APK-Generationen können eine andere Signatur haben: vor einem notwendigen Neuinstallieren zuerst ein vollständiges Backup speichern; Deinstallieren löscht den internen App-Speicher. Für eine dauerhafte Veröffentlichung ist eine eigene Release-Signierung erforderlich.

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
