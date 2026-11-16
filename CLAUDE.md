# Daglicht

Android-app voor Mark's Oppo Find X9 Ultra (CPH2841, Android 17, geen root). Houdt nieuwe foto's bij en maakt
aan het eind van de dag met één knop SDR-JPEG's van de gekozen foto's: kleiner, zonder HDR, alle metadata intact,
in een map die Immich zelf back-upt.

## Stand

Fase 0 (prototype) is klaar en goedgekeurd. Fase 1 (overzicht, alleen lezen) is gebouwd en staat op branch `claude/fase-1-overzicht`:
galerij als startscherm met bewaren (tik op het rondje, ingedrukt houden en slepen), favorieten, groot bekijken met zoomen,
Lightroom-knop op RAW, naar de prullenbak (alleen met Android's bevestiging), Opruimen per dag, overzicht, Bewerken en Instellingen
(kwaliteit en tone map). Het omzetten zelf (fase 2) en de Immich-omschakeling plus release-workflow (fase 3) wachten op Mark's OK.

Besluiten van Mark (2026-11-16): tone map **normaal** (belichting 0,90, knie 0,45, mix 0,5, piek uit de inhoud gemeten) is de
standaard; JPEG-kwaliteit jpegli **q94** (instelbaar q90 tot q95); OK voor fase 1.

Model van de galerij: bewaard (vinkje) is wat meegaat naar de uitvoermap en blijft staan. Camera-JPG's en RAW's staan standaard
**uit**, Lightroom-exports en Galerij-bewerkingen standaard **aan**. Opruimen (per dag) stuurt alleen niet-bewaarde camera-JPG's
zonder hartje naar de prullenbak; RAW's, exports, bewerkingen en hun originelen blijven altijd staan.

## Conventies (zelfde als Tandem en PixelPlayer)

- Nederlandse UI. Nooit em-dashes. Korte commentaren die uitleggen waarom, niet wat.
- Kotlin + Jetpack Compose, Material 3 Expressive. Thema, componenten en stijl overnemen uit
  `~/Documents/Apps/Tandem/android` en `~/Documents/Apps/PixelPlayer`; bij twijfel `Tandem/CLAUDE.md`
  (sectie Designtaal: Google Sans Flex met ROND 100, Material Symbols Rounded, slabs, pure zwart, springs,
  `bouncyClickable`).
- **Icoon in dezelfde stijl** als Tandem en PixelPlayer (Mark's wens): zelfde vormtaal en opbouw, eigen motief.
- **Automatische updates precies zoals Tandem** (Mark's wens, 2026-11-16): update-checker die de GitHub-releasesfeed
  leest, workflow die de APK onder een vaste naam aan de release hangt, en een vaste release-sleutel zodat
  bijwerken zonder verwijderen werkt (`Tandem/CLAUDE.md`, sectie "Signing en updates"). Kopieer de aanpak, de
  checker en de workflow uit Tandem. Een nieuwe sleutel (`~/keystores/daglicht-release.jks`) en een GitHub-repo zijn
  uitgaand en onomkeerbaar: eerst Mark's OK.
- Package-voorstel: `nl.markmaaktmedia.daglicht`, minSdk 31, compileSdk 36, arm64 only.

## Harde regels

- Nooit iets automatisch verwijderen, ook niet op de telefoon.
- Geen accessibility service (Triodos ziet de telefoon dan als geroot).
- Op de telefoon installeren alleen met Mark's OK.
- Alleen zwaar werk tijdens het opladen, tenzij Mark de knop toch indrukt (WorkManager plus voortgangsnotificatie).
- 50 MP en 200 MP in tegels verwerken; nooit een volledige float-buffer.
- Overslaan: `.trashed-*` en `DCIM/Immich`. Galerij-bewerkingen herkennen aan `IMG_YYYYMMDD_HHMMSS.jpg`.
- Metadata: behouden GPS, DateTimeOriginal, SubSecTimeOriginal, OffsetTimeOriginal, orientation, make, model,
  lens en het ICC-profiel (Display P3). Weg: hdrgm-XMP, MPF/gain map, GContainer, het APP2-segment ISO 21496-1 (`urn:iso:std:iso:ts:21496:-1`, anders
  zien viewers de foto nog als HDR), oude thumbnail, OnePlus-trailer. Neem APP-segmenten nooit blind over uit de bron.
  Verplichte test: exiftool-diff op de Mac, origineel tegen resultaat.

## Prototype (`prototype/`)

Python (venv in `.venv`, niet in git): `uhdr.py` leest Ultra HDR, `tonemap.py` is de tone map, `fase0.py` draait alles,
`sizes.py` meet groottes, `ref_apple.swift` is de Apple-referentie. Bouwen en draaien: zie `fase0/RAPPORT.md`.
