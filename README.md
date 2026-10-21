# Step by Step – Android 0.3 (Hybrid)
Die Android-App bündelt die komplette PWA-Oberfläche aus dem öffentlichen [SalsaAppCloud](https://github.com/rodriges17/SalsaAppCloud) als lokale Assets im APK. Ein zusätzliches natives Kotlin-Grundgerüst für die Offline-App bleibt erhalten. Anders als die frühere Version startet Version 0.2 mit der erweiterten Bibliotheks-/Cloud-Oberfläche.

## Funktionen aus dem gebündelten PWA-Stand
- Mehrere Videos, Vorschau und Lernplayer, Filter und Tags, Figurenteilen als ZIP, Import und vollständige Backups
- Cloud-Ansicht und Supabase-Verbindung (Online-Zugriff erforderlich)
- Offline-UI aus APK-Assets, IndexedDB für lokale App-Daten
- Android-Dateiauswahl über WebChromeClient
- Native Offline-Ansicht weiterhin über das Android-Menü

## Einschränkungen und Teststatus
**Hybrid statt vollständig nativ:** Bibliothek und Cloud sind über Android WebView integriert. Nicht alle Browser-APIs sind in WebView unterstützt (z.B. File System Access API, Web Share API, Service-Worker- und Download-Flows). ZIP- und Backup-Downloads können über die Android-Dateiauswahl gespeichert oder über den Android-Freigabedialog geteilt werden; dieser Pfad ist noch nicht auf echten Geräten validiert. Magic-Link-Weitergabe aus Androids Teilen-Menü ist als erster Integrationsweg vorhanden; direkte HTTPS-App-Links und echte Geräteordner sind noch offen. Die PWA-Datenbank ist von der nativen Kotlin-Datenbank sowie den separat installierten Browser-PWAs getrennt. Bestehende Figuren werden nicht automatisch migriert.

## APK
[GitHub Actions – Android APK](https://github.com/rodriges17/SalsaAppAndroid/actions/workflows/android-apk.yml) → erfolgreicher Build → Artifacts → `StepByStep-Android-debug-apk`. Im ZIP liegt `app-debug.apk`.

## Quellcode
App-Webassets: `app/src/main/assets`. Android-Integration: `WebAppActivity.kt`. Der letzte importierte Webstand entspricht dem Main-Baum von SalsaAppCloud am 21.10.2026. Das Backend selbst ist **nicht** im APK enthalten.
