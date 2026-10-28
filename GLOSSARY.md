# Begriffe

**Cloud-Lesestand**:
Der zuletzt geladene Stand der zugänglichen Cloud-Figuren und Bibliotheken einschließlich ihrer Vorschaubilder und des Ladezeitpunkts.

**Medien-Sitzung**:
Der geordnete Zugriff auf die Videos einer Figur für einen geöffneten Bildschirm oder einen vollständigen Lesevorgang. Parallele Zugriffe teilen die laufende Prüfung; beim erneuten Öffnen beginnt eine neue Sitzung.

**Native Dateiübergabe**:
Die Übergabe eines vorbereiteten Archivs an Android, um es an einem gewählten Ziel zu speichern oder mit einer anderen App zu teilen. Ein abgebrochener Speicherortdialog beendet das vorbereitete Archiv nicht.

**Vorbereitetes Archiv**:
Eine vollständig erstellte Datei für eine Figur-ZIP oder einen Teil eines vollständigen Backups, die ohne erneute Erstellung gespeichert oder geteilt werden kann.

## Eingehende Übergabe
Eine über Android geteilte Dateiauswahl oder ein unterstützter Bibliotheks- oder Anmeldelink. Sie wartet bei einem Kaltstart auf die bereite Oberfläche und wird genau einmal übernommen. Eine laufende Übergabe oder ein offener Bearbeitungsvorgang wird durch eine weitere Übergabe nicht ersetzt.

## Übergabevorbereitung
Die abbrechbare Prüfung und das Einlesen geteilter Dateien vor dem Öffnen des Import- oder Bearbeitungsdialogs. Ein Abbruch verwirft diese Vorbereitung; verspätete Ergebnisse dürfen keinen Dialog mehr öffnen.

**Geräteordner-Schreibvorgang**:
Das vorbereitete Schreiben einer Datei in einen verbundenen Geräteordner. Bis zum Abschluss bleiben die Daten vorläufig. Mit dem Abschluss beginnt die Übernahme in die Zieldatei; danach wird sie nicht mehr durch einen Abbruch unterbrochen.

**Lokale Cloud-Verfügbarkeit**:
Die Verfügbarkeit einer Cloud-Version als einzelne vollständige, lesbare lokale Kopie. Teilkopien, fehlende Dateien, fehlender Ordnerzugriff und eine vorhandene ältere Version werden unterschieden. Mehrere Teilkopien ergeben zusammen keine vollständige Kopie.

**Cloud-Herkunft**:
Die Zuordnung einer lokalen Figur zu ihrer Cloud-Figur und der übernommenen oder hochgeladenen Version. Lokale Inhaltsänderungen bleiben erkennbar; Lernstand und Verfügbarkeit sind davon unabhängig.

**Figuren-Upload**:
Der gemeinsame Ablauf, der Videos einer lokalen Figur prüft und bei Bedarf überträgt, die Cloud-Figur endgültig speichert und erreichte Ergebnisse für getrennte Nacharbeiten erhält. Bibliothekszuordnung und lokale Cloud-Herkunft können nach erfolgreichem Speichern unabhängig wiederholt werden. Ein unbekannter Speicherstand wird anhand des eindeutigen Upload-Vorgangs geklärt.

**Bibliotheks-Bearbeitungssitzung**:
Der im Arbeitsspeicher gehaltene Entwurf einer bestehenden Bibliothek mit Angaben, Figuren und Reihenfolge. Die Sitzung umfasst Bearbeiten, Unterbrechen für einen Figuren-Upload, Fortsetzen, Speichern, Verwerfen sowie Fehler- und Konfliktzustände. Sie ist an die aktuelle Anmeldesitzung gebunden und wird nach einem App-Neustart nicht wiederhergestellt. Bereits hochgeladene Cloud-Figuren bestehen unabhängig vom Bibliotheksentwurf.
