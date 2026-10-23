# Native Dateiübergabe

Die gemeinsame Figur-ZIP- und Backup-Erstellung bleibt unverändert. `FileDeliverySession` besitzt die temporäre Datei und ihren vollständigen Vorgang. `NativeFileDelivery` ist der Android-adapter für Auswahl, Statusdialog, Zielstream und Teilen. `WebAppActivity` registriert die Brücke, leitet Activity-Ergebnisse weiter und beendet sie. Das gilt auch für die interne ältere `NativeActivity`.

## Vereinbarte Regeln

- Der Android-Dialog „Export bereit“ behält „Auf Gerät speichern“, „Mit anderer App teilen“ und „Abbrechen“.
- Abgebrochene Speicherortwahl und Fehler beim Öffnen, Schreiben oder Schließen des Ziels führen mit demselben vorbereiteten Archiv zurück zur Auswahl. Keine erneute ZIP-Erstellung und keine zweite Übertragung der Archivbytes sind dafür nötig.
- Die Zielkopie läuft im Hintergrund. Spinner und Prozentstatus sind sichtbar; „Export gespeichert“ erscheint erst nach erfolgreichem Schreiben und Schließen des Ziels. Eine Prozentanzeige von 100 alleine bestätigt den Abschluss nicht.
- Teilen bestätigt ausschließlich die Übergabe an Android. Die empfangende App bestätigt weder Eingang noch Speicherung zurück.

## Besitz und Lebensdauer

Es gibt je Brücke höchstens ein Archiv in Vorbereitung oder Übergabe: empfangen → bereit → Speicherortwahl → kopieren → gespeichert. Abbruch der Speicherortwahl oder Schreibfehler führen zu „bereit“ zurück. Ein weiterer Start oder ein zweiter Abschluss können ein bestehendes Archiv nicht ersetzen.

Explizites „Abbrechen“ im Exportdialog entfernt dessen native temporäre Datei. Die gemeinsame Oberfläche behält ihr vorbereitetes Archiv für eine weitere Übergabe. Erfolgreiches Speichern entfernt die native temporäre Datei. Activity-Ende beendet nicht geteilte Vorgänge und verhindert spätere UI-Ergebnisse; es wartet nicht auf die Zielkopie. Ein noch blockierender Dokumentanbieter kann erst nach Rückkehr seines Aufrufs vollständig aufgeräumt werden.

Geteilte Dateien bleiben unter derselben FileProvider-URI nach Activity-Ende lesbar. Die nächste Initialisierung entfernt ausschließlich entsprechend markierte temporäre Dateien, die älter als 24 Stunden sind. Andere Dateien im Exportverzeichnis werden nicht angefasst. Ein bereits geteilter Inhalt wird beim Activity-Ende nicht gelöscht.

Die bisherigen Grenzen gelten weiter: maximal 256 KiB je nativen Block und maximal 300 MiB je übergebener Datei. Ordnerkataloge, Prüfsummen, Speicherorte und APK-Signatur werden nicht verändert. Die Versionsanzeige liest nun die installierte Paketversion statt einer festen Zeichenfolge.

## Prüfung durch die gemeinsame interface

`FileDeliverySessionTest` prüft die echte Kotlin-implementation mit lokalen Dateien und kontrollierten Zielstreams: Auswahlabbruch, Wiederholung, Schreib- und Abschlussfehler, Fortschritt während blockiertem Abschluss, verspäteter Erfolg nach Beendigung, doppelte Starts, alte Ergebnisse, Bereinigung und Dateilebensdauer beim Teilen. Der Android-adapter bleibt klein; JavaScript-Tests prüfen weiterhin das bestehende Brückenformat.

Ein Emulatortest führt die echte JavaScript-Brücke und die produktive Activity-Ergebnisbehandlung aus. Die Android-Dateiauswahl wird mit abgebrochenem und anschließend erfolgreichem Ergebnis kontrolliert; die Zielbytes müssen dem einmal vorbereiteten Archiv entsprechen. Der Launcher-Test prüft außerdem die Anzeige der tatsächlichen Paketversion.
