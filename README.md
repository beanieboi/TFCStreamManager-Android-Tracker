# TFC Stream Manager: Tracker

Ein altes Handy hängt über dem Kickertisch und liest die Torzähler ab: **Tore, Sätze und
Auszeiten**. Den Stand schickt es an den
[TFC Stream Manager](https://github.com/beanieboi/TFCStreamManager-linux), und im Stream-Overlay
steht immer der echte Spielstand. Keiner muss mehr nachtippen.

Gebaut für die Tische vom TFC Leipzig, ein Handy pro Tischseite.

## So läuft's

- **Punkte zählen:** Jeder Stein auf den Torzählern hat einen Punkt, weiß auf den schwarzen und
  rosa auf den grünen Steinen. Die App sucht die Punkte, findet die Reihe und teilt sie auf: 5
  Steine für Sätze und Auszeiten, 7 für die Tore. Wo die Stange frei ist, steht der Spielstand.
  Passt die Zahl der Punkte nicht genau, liest sie lieber gar nichts als Mist.
- **Einmessen:** Beim ersten Start sucht die App selbst raus, wie das Handy hängt, wie weit sie
  zoomen kann und wie hell belichtet werden muss. Wenn sie die Zähler verliert, misst sie neu ein.
- **Belichtung von Hand:** Die Automatik vom Handy misst das ganze Bild, und das ist meistens
  dunkler Boden. Dann sind die Zähler überbelichtet. Also stellt die App die Belichtung selbst
  ein und zieht sie nach, wenn sich das Licht über den Tag ändert.
- **Bleibt kühl:** Beim Lesen macht die Kamera nur alle 2 Sekunden ein paar Bilder, ohne
  Vorschau und mit dunklem Bildschirm.
- **Ab zum Server:** Tore und Sätze gehen raus, sobald sie ein paarmal gleich gelesen wurden.
  Es wird immer der ganze Stand geschickt, also macht es nichts, wenn mal was verloren geht oder
  doppelt ankommt.
- **Strom weg?** Dann ruht das Handy: Kamera aus, WLAN bleibt an. Ist es ganz aus und kriegt
  wieder Strom, startet es von selbst (`tools/install-autoboot.sh`). Mit fast leerem Akku lädt es
  erst mal, bevor es wieder liest.
- **Von weitem:** Jedes Handy meldet sich beim Server mit Akku, Spielstand und Kamera. Unter
  `http://<ip>:8081/` gibt's eine Debug-Seite mit Bild, Spielstand, Belichtung, Zoom und
  Aufnahmen zum Auswerten.

## Was du brauchst

- Ein **Oukitel WP5** mit entsperrtem Bootloader und TrebleDroid (Android 13). Andere Handys gehen
  bestimmt auch, probiert haben wir nur das.
- Dauerstrom per USB und eine Halterung über dem Tisch, mit Blick aufs Tischende.

## Einrichten

Alles Schritt für Schritt in [docs/flashing.md](docs/flashing.md) (auf Englisch). Kurz:

1. TrebleDroid draufspielen
2. `tools/setup-phone.sh`: App als Startbildschirm, adb übers WLAN, Bildschirm bleibt an
3. `tools/install-autoboot.sh`: Handy geht von selbst an, sobald Strom da ist
4. am Tisch einmessen

## Mitmachen

Alles, was du brauchst (JDK, Android SDK, adb, fastboot, e2fsprogs), kommt aus dem Nix-Flake:

```
nix develop
./gradlew :app:testDebugUnitTest :app:ktlintCheck :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Die Tests laufen ohne Handy, mit gemalten Zählern und den Fotos in `testdata/`. Aufnahmen von
der Debug-Seite kannst du am Rechner gegen den eingetippten echten Stand prüfen:

```
RECORDING=recordings/<aufnahme> ./gradlew :app:testDebugUnitTest --tests '*ReaderComparisonTest*' --rerun
```

## Achtung, Sicherheit

Die Handys gehören in ein **eigenes Netz**, in dem keine Fremden sind:

- **adb** läuft übers WLAN **ohne Schlüssel**, und `su` gibt Root. Wer im Netz ist, hat eine Shell
  auf dem Handy.
- **Die Debug-Seite** (Port 8081) hat **kein Passwort**. Wer sie aufruft, sieht den Spielstand
  und kann Kamera, Einmessen und Aufnahmen steuern.

Das abzusichern steht noch auf der Liste (siehe [docs/remote-management.md](docs/remote-management.md)).

## Mehr lesen

Die restliche Doku ist auf Englisch:

| Datei | Worum's geht |
|---|---|
| [docs/counter-reader.md](docs/counter-reader.md) | wie die Torzähler gelesen werden |
| [docs/on-site-debugging.md](docs/on-site-debugging.md) | am Tisch testen, Aufnahmen machen und auswerten |
| [docs/remote-management.md](docs/remote-management.md) | Fernwartung, Stromausfall, Autostart |
| [docs/flashing.md](docs/flashing.md) | sichern, flashen, zurückspielen, neues Handy einrichten |

## Halterung

Die Handyhalterung zum Selberdrucken liegt in [hardware/phone-mount](hardware/phone-mount):
STLs zum Drucken, dazu das FreeCAD-Modell und das Skript dazu, falls du sie anpassen willst.

## Lizenz

MIT, siehe [LICENSE](LICENSE). Gilt auch für die Halterung in `hardware/`.
