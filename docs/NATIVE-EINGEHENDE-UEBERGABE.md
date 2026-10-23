# Eingehende Übergaben unter Android

`CloudUiActivity` registriert das Modul `NativeIntake` und reicht Start- sowie Folge-Intents an dieselbe Schnittstelle weiter. `NativeIntake` besitzt die Übergabe bis zum Abschluss oder Abbruch. Die alte interne `NativeActivity` behält ihren eigenen Ablauf; die gemeinsame Anmeldelink-Klassifikation liegt in `IncomingLink`. Externe URLs werden niemals in der WebView mit ihren privilegierten Bridges geöffnet.

`IncomingSession` reserviert eine Übergabe bereits vor der asynchronen Prüfung der Oberfläche. Beim Kaltstart bleibt die erste Übergabe vorgemerkt, bis JavaScript bereit ist. Weitere Übergaben überschreiben sie nicht. Wiederholte Bereitschaftsmeldungen starten keine zweite Verarbeitung. Ticket-IDs verhindern, dass verspätete Antworten eine neuere Übergabe abschließen oder abbrechen. Nach einer Anmeldung muss das neue Dokument erneut Bereitschaft melden.

Ein offener Editor, Import, Export, Player oder Dialog verhindert die Annahme. Android zeigt dann einen Hinweis; die bestehende Arbeit bleibt erhalten. Geteilte Dateien werden auf Content-URIs und höchstens 100 Einträge begrenzt. Anzeigenamen und MIME-Typen werden außerhalb des UI-Threads gelesen. Der sichtbare Vorbereitungsdialog bleibt bis zum vollständigen Einlesen der Dateiinhalte geöffnet und kann abgebrochen werden.

JavaScript reserviert die Oberfläche ebenfalls, liest die temporären lokalen URLs mit einem AbortSignal und prüft nach jedem asynchronen Lesen, ob die Vorbereitung noch aktuell ist. Erst danach werden die bestehenden Video-, ZIP- oder Sicherungs-Importabläufe bedient. Beim Abbruch werden Provider-Abfragen, Lesevorgänge und temporäre URL-Registrierungen freigegeben. Provider, die einen Abbruch ignorieren, können mit späteren Ergebnissen keine Oberfläche mehr öffnen. Beim Zerstören der Activity wird die gesamte Sitzung geschlossen.

Die Tests prüfen die Kotlin-Schnittstelle für Reservierung und Link-Klassifikation, den tatsächlichen JavaScript-Lese- und Abbruchablauf sowie reale Android-Share-Intents, warm eintreffende Links und die native Abbruchschaltfläche.
