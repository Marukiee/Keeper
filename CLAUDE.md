# Keeper

Android-app voor Mark's Oppo Find X9 Ultra (CPH2841, Android 17, geen root). Houdt nieuwe foto's bij en maakt
aan het eind van de dag met één knop SDR-JPEG's van de gekozen foto's: kleiner, zonder HDR, alle metadata intact,
in een map die Immich zelf back-upt.

## Stand

Fase 0 (prototype) is klaar en goedgekeurd. Fase 1 (overzicht, alleen lezen) is gebouwd en staat op branch `claude/fase-1-overzicht`:
galerij als startscherm met bewaren (tik op het rondje, ingedrukt houden en slepen), favorieten, groot bekijken met zoomen,
Lightroom-knop op RAW, naar de prullenbak (alleen met Android's bevestiging), Opruimen per dag, overzicht, Bewerken en Instellingen
(kwaliteit en tone map). Het omzetten zelf (fase 2) en de Immich-omschakeling plus release-workflow (fase 3) wachten op Mark's OK.

Fase 2 (omzetten) is bezig op branch `claude/fase-2-omzetten`. Klaar en getest: tone map, Ultra HDR-lezer met gain-map-sampler, eigen
JPEG-encoder (baseline, Annex K-tabellen, kwaliteit zoals libjpeg), en `Converter` (gain map toepassen, tone map, coderen in stroken, parallel).
Gemeten op de emulator: 50 MP-foto in ongeveer 4 s, uitvoer 8,1 MB bij libjpeg-q92, gelijk in grootte en 0,7 ssimulacra2 onder jpegli q94 (kwaliteit q94 in de
UI wordt dus libjpeg-q92 in de encoder, q90 tot q95 wordt q88 tot q93). Nog te doen: metadata schrijven (EXIF-whitelist, ICC, hdrgm en trailer eruit) met exiftool-diff,
DNG-preview als camera-versie, naar MediaStore schrijven (`Pictures/Keeper`, `IS_PENDING`), WorkManager met melding, Convert-knop en Overzicht aansluiten.
Debug-hulp: `ConvertDebugReceiver` (alleen debug-build, via `adb shell am broadcast`) zet een foto uit `files/` om; `prototype/vergelijk.py` meet de afwijking met het prototype.

Warm flash (`warmflash/`): het filter is te benaderen met een 3D-LUT plus radiaal vignet, gefit op 19 foto-paren (bron uit de trailer, eindbeeld): gemiddeld dE76 2,7 op
foto's die niet in de training zaten (zonder correctie 17,3). Scripts in `prototype/warmflash_*.py`. Nog open: met RAW's toetsen (Lightroom-export tegen de camera-versie in de DNG).

Welke preset gebruikt is (gemeten op 2026-11-16, Find X9 Ultra): in de Oppo-trailer van JPG's zit `master.mode.preset.info`
(496 bytes): f32 1.0, u32 sterkte (100), de naam als ASCII op byte 236 ("Warm flash", "Fresh", "Vintage", "Standard"). Geen preset gekozen: dan ontbreekt het blok.
Gewone EXIF en XMP bevatten niets. **DNG's hebben het niet**: de eindversie (17 tot 94 MB) heeft geen trailer. Let op: vlak na het maken staat er kort een
tussenbestand van 2 tot 4 MB met de naam `.dng` dat in werkelijkheid een JPEG is mét trailer; Oppo vervangt dat daarna door de echte DNG. Wie te snel kopieert
(of scant) ziet dus ten onrechte een preset in een "DNG". Daarom: bestandstype altijd aan de kop herkennen (`FF D8` is JPEG, `II*\0` is TIFF/DNG), nooit aan de naam.
De preset voor een DNG moet uit de omgeving (JPG vlak ernaast) of van de gebruiker komen. `prototype/adobe_look.py` schrijft en leest Adobe-profielen (Look) met een 3D-LUT (RGBTable), getest tegen echte Lightroom-bestanden.

Lightroom-preset op RAW's (2026-11-16): Lightroom mobile kent de camerapresets niet, dus Keeper zet de look zelf in de DNG. De knop "Lightroom" (Bewerken en Bekijken) kopieert de DNG naar
`cache/looks/` (hooguit 3 kopieën), voegt via `lightroom/DngXmp` een `crs:Look` met 32³-tabel plus vignet toe aan de XMP (alleen bytes achteraan, het origineel blijft ongemoeid) en opent die kopie via FileProvider
in Lightroom. Preset per foto: kiesvakje in Bewerken; standaard de preset uit de camera-JPG die ≤3 minuten ernaast is genomen (`LookLauncher.guess`, leest de Oppo-trailer), anders de standaard uit Instellingen.
Alleen presets met een bestand in `assets/looks/` komen in de keuze. Gemeten: Warm flash 4 JPG's met trailer als bewijs, gefit op de DNG-preview (die heeft het filter wél) tegen Lightroom's standaardweergave:
dE76 3 tot 5 tegen ongeveer 21 zonder look, op de telefoon bevestigd. Het raden van de preset uit JPG's ernaast (`PresetGuess`) vertrouwt de dichtstbijzijnde JPG alleen als die minstens twee keer zo dichtbij is als een JPG met een andere preset. Scripts `prototype/lr_*.py` (collect, fit, vignet, eval) en `prototype/maak_looks.py` (npz in `looks/` naar asset-XML).
**Fresh en Vintage nog niet gemaakt**: 10 DNG-paren verzameld maar er is geen bewijs welk DNG welke preset had (DNG's noemen het niet; alleen JPG 132736=Vintage en 132906/132909=Fresh); een DNG (132752) past beter op
de andere groep, en de tabellen verschillen ongeveer evenveel als de ruis. Nodig: per preset ~8 DNG's achter elkaar met eerst een JPG in dezelfde preset. Op de telefoon gemeten: Lightroom past de Look ook toe bij een `content://`-adres (MediaStore) en geeft hetzelfde beeld als bij `file://`. Nog niet getest: de knop zelf met Keeper's FileProvider (debug-app moet op de telefoon, wacht op Mark's OK).

Besluiten van Mark (2026-11-16): tone map **normaal** (belichting 0,90, knie 0,45, mix 0,5, piek uit de inhoud gemeten) is de
standaard; JPEG-kwaliteit jpegli **q94** (instelbaar q90 tot q95); OK voor fase 1.

Model van de galerij: bewaard (vinkje) is wat meegaat naar de uitvoermap en blijft staan. Camera-JPG's en RAW's staan standaard
**uit**, Lightroom-exports en Galerij-bewerkingen standaard **aan**. Opruimen (per dag) stuurt alleen niet-bewaarde camera-JPG's
zonder hartje naar de prullenbak; RAW's, exports, bewerkingen en hun originelen blijven altijd staan.

## Conventies (zelfde als Tandem en PixelPlayer)

- UI standaard **Engels** (Mark's wens, 2026-11-16), Nederlands erbij in `values-nl` en te kiezen in Instellingen (`AppLanguage`, Android 13+). Elke nieuwe string in beide bestanden. Nooit em-dashes. Korte commentaren die uitleggen waarom, niet wat.
- Kotlin + Jetpack Compose, Material 3 Expressive. Thema, componenten en stijl overnemen uit
  `~/Documents/Apps/Tandem/android` en `~/Documents/Apps/PixelPlayer`; bij twijfel `Tandem/CLAUDE.md`
  (sectie Designtaal: Google Sans Flex met ROND 100, Material Symbols Rounded, slabs, pure zwart, springs,
  `bouncyClickable`).
- **Icoon in dezelfde stijl** als Tandem en PixelPlayer (Mark's wens): zelfde vormtaal en opbouw, eigen motief.
- **Automatische updates precies zoals Tandem** (Mark's wens, 2026-11-16): update-checker die de GitHub-releasesfeed
  leest, workflow die de APK onder een vaste naam aan de release hangt, en een vaste release-sleutel zodat
  bijwerken zonder verwijderen werkt (`Tandem/CLAUDE.md`, sectie "Signing en updates"). Kopieer de aanpak, de
  checker en de workflow uit Tandem. Een nieuwe sleutel (`~/keystores/keeper-release.jks`) en een GitHub-repo zijn
  uitgaand en onomkeerbaar: eerst Mark's OK.
- Package: `nl.markmaaktmedia.keeper` (app heette eerst Daglicht; Mark koos Keeper op 2026-11-16), minSdk 31, compileSdk 36, arm64 only.
  De projectmap heet nog `Daglicht`. GitHub: `Marukiee/Keeper`.

## Harde regels

- Nooit iets automatisch verwijderen, ook niet op de telefoon. Enige uitzondering (Mark, 2026-11-16): niet-bewaarde RAW's na een
  instelbare termijn. Daar komt eerst een notificatie van; er gaat pas iets naar de prullenbak nadat Mark in de app de lijst heeft
  bekeken en bevestigd heeft (plus Android's eigen bevestiging). Nog niet gebouwd.
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
