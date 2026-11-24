# PlayerCannon

Paper-Plugin für Minecraft Java **1.21.11**: Eine Enderpearl Player Cannon.
Mit einer speziellen Angel werden die Enderpearls verlinkter Spieler an die Stelle des Hooks geschossen.

## Befehle (`playercannon.use`, Standard: OP)

| Befehl | Beschreibung |
|---|---|
| `/playercannon link <spieler\|all>` | Spieler (oder alle Online-Spieler) verlinken |
| `/playercannon unlink <spieler\|all>` | Spieler entlinken |
| `/playercannon list` | Verlinkte Spieler anzeigen |
| `/playercannon clear` | Alle Links entfernen |
| `/playercannon rod` | Player-Cannon-Angel erhalten |
| `/playercannon reload` | Config neu laden |

Alias: `/pc`, `/cannon`

## Features

- Pearls fliegen per Ballistik-Berechnung zum Hook (flach oder hoch, Blöcke im Weg werden umgangen)
- Kein Pearl-Schaden (einstellbar), Partikel-Spur, Sounds und Ziel-Effekt
- Pearls werden nacheinander abgefeuert (einstellbarer Abstand)
- Alles in `config.yml` einstellbar (Cooldown, Reichweite, Effekte, Angel-Name)

## Download

Jeder Push erzeugt unter **Releases** ein Build mit der direkten `PlayerCannon.jar`.
**Nicht** die Datei aus *Actions → Artifacts* nutzen, das ist eine Zip.

## Bauen

```bash
mvn package
```
