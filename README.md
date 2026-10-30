# Dashboard W204

Cockpit-App für den **Mercedes W204 C 220 CDI (OM651)** auf einem Android-Radio (ab Android 10).

## Was die App kann

- **Dashboard** (Querformat): Drehzahl mit rotem Bereich ab 4.400, Geschwindigkeit, Ladedruck in bar, Kühlmittel, Ladeluft, Bordnetz, Außentemperatur, Raildruck, Luftmasse, Motorlast
- **ELM327 über Bluetooth**: Protokoll fest auf ISO 15765-4 CAN 11 Bit / 500 kBaud (W204), automatische Erkennung, welche Werte das Steuergerät liefert, automatisches Neuverbinden bei Zündung aus/an
- **Schildererkennung**: findet runde Tempolimit-Schilder im Kamerabild und liest die Zahl offline (10–130). Ein Wert zählt erst, wenn er mehrfach kurz hintereinander erkannt wurde.
- **Fahrspur-Ansicht** (rechts): eigenes Auto von hinten, erkannte Spur, Autos/Lkw/Zweiräder/Personen davor im geschätzten Abstand, Leitlinien laufen mit der echten Geschwindigkeit mit. Umschalten auf das Kamerabild per „Spur | Kamera“.
- **Abstandshinweis**: Abstand zum Vordermann, gelb unter „halber Tacho“, rot deutlich darunter; Ansage „Abstand.“ ab 50 km/h
- **Sprachassistent**: Begrüßung beim Start, Warnungen (kalter Motor über 3.000 U/min, Kühlmittel ≥ 108 °C, Ladespannung zu niedrig, Ladeluft heiß, Tempolimit überschritten), Fragen per Mikrofon-Taste: „Wie warm ist der Motor?“, „Ladedruck?“, „Batterie?“, „Tempolimit?“, „Abstand?“, „Status?“
- **Demo-Modus** zum Testen ohne Auto

## APK bauen (ohne eigenen PC-Aufwand: GitHub)

1. Kostenloses Konto auf github.com anlegen, neues Repository erstellen (privat ist ok).
2. Den Inhalt dieses Ordners hochladen („Add file → Upload files“). Wichtig: auch den Ordner `.github` – falls er beim Hochladen fehlt, unter „Actions → set up a workflow yourself“ den Inhalt von `.github/workflows/build.yml` einfügen.
3. Unter **Actions** startet der Build automatisch (ca. 5 Minuten).
4. Im fertigen Lauf unten bei „Artifacts“ **JarvisW204-apk** herunterladen, entpacken, `app-debug.apk` per USB-Stick aufs Radio und installieren („Unbekannte Quellen“ erlauben).

Alternativ: Ordner in Android Studio öffnen und auf ▶ drücken.

## Einrichtung im Auto

1. ELM327 in die OBD-Buchse, Zündung an.
2. Im Radio unter Bluetooth koppeln (PIN meist `1234` oder `0000`).
3. App öffnen → Berechtigungen erlauben → in den Einstellungen den Adapter antippen.
4. USB-Kamera mittig hinter der Frontscheibe fest montieren, anstecken, in den Einstellungen ggf. „Kamera 2“ wählen.
5. Kamera einmessen: in den Einstellungen Einbauhöhe und Blickwinkel eintragen, dann rechts auf „Kamera“ und aufs Bild tippen – im Vollbild die gelbe Linie auf den Horizont schieben (am besten auf gerader, ebener Straße).

## Grenzen (ehrlich)

- Über die OBD-Buchse kommen beim W204 nur Diagnosewerte über das Gateway, kein roher Innenraum-CAN. Mercedes-spezifische Werte (Öltemperatur, DPF-Beladung, Getriebeöl) brauchen eigene Diagnose-Befehle und kommen in einer späteren Version.
- USB-Kameras funktionieren nur, wenn das Radio sie Android als Kamera meldet. Falls „Keine Kamera gefunden“ erscheint, braucht es einen direkten UVC-Treiber (nächster Ausbauschritt).
- Die Sprachfragen nutzen die Spracherkennung des Radios. Ohne Google-Dienste fällt nur das Mikrofon weg, Ansagen funktionieren trotzdem.
- Fahrzeuge werden nur vor dir erkannt (eine Frontkamera). Abstände sind geschätzt (etwa ±15 %) – Anzeige und Hinweis, keine Notbremsfunktion.
- Spurerkennung braucht sichtbare Markierungen; bei Nacht, Regen oder Baustellen zeigt die Ansicht eine Standardspur.
- Das KI-Modell (EfficientDet-Lite0) wird beim ersten Build automatisch heruntergeladen.
- Schildererkennung ist eine Hilfe, kein Ersatz für Hinsehen. Zusatzzeichen (nur bei Nässe, Uhrzeiten) und Aufhebungsschilder werden noch nicht erkannt.

## Projektaufbau

```
car/Vehicle.kt        Fahrzeugprofil W204 + Grenzwerte (hier anpassen)
obd/                  ELM327-Verbindung, PIDs, Abfrageschleife, Demo
vision/               Kamera, Schilder, Fahrzeuge, Spur, Abstand
voice/                Sprachausgabe, Jarvis-Regeln, Spracheingabe
ui/                   Dashboard, Spuransicht, Anzeigen, Einstellungen
```
