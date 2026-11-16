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

Warm flash als eigen Lightroom-preset (2026-11-16, op Mark's telefoon gemaakt): een los `.xmp`-voorinstelling importeren (Presets, drie puntjes, Import Presets) meldt "1 preset imported" maar er verschijnt niets in de lijst, dus die route werkt niet.
Wel: open een DNG met de Look erin (zoals Keeper die maakt, of `prototype/dng_xmp.py` plus `assets/looks/<id>.xml`) in Lightroom, ga naar Presets, drie puntjes, Create Preset. Lightroom noemt de look dan "Profile: Warm flash";
vink alleen Profile en Effects (het vignet) aan, Light, Color, Detail en Optics uit (anders zet de preset de belichting van die ene foto vast) en geef hem een naam. De preset staat dan in User Presets, heeft een Amount-schuif en
werkt op elke foto, ook zonder het camerafilter. Voor Fresh en Vintage dezelfde route zodra die looks er zijn.

Besluiten van Mark (2026-11-16): tone map **normaal** (belichting 0,90, knie 0,45, mix 0,5, piek uit de inhoud gemeten) is de
standaard; JPEG-kwaliteit jpegli **q94** (instelbaar q90 tot q95); OK voor fase 1.

Selecteren in de galerij (2026-11-16): lang indrukken start de selectiemodus en slepen selecteert een reeks zoals Google Foto's: het anker is de foto waar je begint, alles in leesvolgorde
tussen anker en vinger gaat aan of uit (de eerste foto bepaalt welk van de twee), schuif je terug dan krimpt de reeks en krijgen de foto's buiten de reeks hun oude stand terug; autoscroll bij de boven- en onderrand.
Het gebaar zit in `PhotoGrid` als eigen `awaitEachGesture` in de Initial-pass met sleutel `Unit`: een sleutel op de fotolijst brak het gebaar na de eerste foto af, want elke selectie levert een nieuwe lijst.
Het kruisje in de balk deselecteert alles en sluit de modus (voorheen zette het de selectie terug naar het begin van de modus, waardoor oude keuzes terugkwamen).
Bekijken: knoppen Niet bewaren en Bewaren, allebei gaan door naar de volgende foto; een tegel in de galerij toont buiten de selectiemodus een vinkje (bewaard) of een kruisje (bewust niet bewaard, set `rejected` in `KeeperPrefs`).
Het kruisje is alleen een markering: Opruimen blijft werken op alles wat niet bewaard is. Op de telefoon getest met `adb shell input motionevent` en `input draganddrop`.
Oudere foto's: de scanner kijkt alleen naar `DCIM/Camera` en de Lightroom-map sinds `sinceMs` (standaard 7 dagen). Mark's telefoon is nieuw met zo'n 30 foto's, dus een "Toon foto's vanaf"-instelling is niet nodig (Mark, 2026-11-16).
De galeriekop telt wel ongeveer 1460 bestanden uit de Lightroom-map mee (allemaal uitgezet in `skipped`), daarom staat er 1512 foto's.
Herontwerp van de galerij (2026-11-16): selecteren en beslissen zijn twee aparte dingen. De selectie is tijdelijk: blauwe cirkel linksboven, de tegel wordt kleiner met ronde hoeken, en cirkel, hartje, label en vinkje staan in één laag die samen schaalt. Vanuit de selectiebalk kies je Bewaren, Niet bewaren of de prullenbak. De beslissing blijft staan: groen vinkje rechtsonder (bewaard) of rood kruisje (niet bewaard).
Bewerkte foto's zijn gewoon te selecteren (RAW met Lightroom-export, JPG met export of Galerij-kopie; `SELECTABLE` in `Model.kt`). `Photo.edit` (`Edit.Exported` of `Edit.Replaced`) staat los van `status` en houdt het label "Edited" of "Original" op de tegel, ook als de foto bewaard is. Bewaren wint van "er bestaat een bewerking": dan wordt een RAW `CameraVersion` en een JPG `Convert` of `AsIs`, en gaat hij naast de export mee. `cleanable` is niet veranderd, de originelen van bewerkingen blijven staan.
"Edited" op een RAW betekent alleen dat er een bestand met dezelfde naam in de Lightroom-map staat. De eigenaar zie je met `adb shell content query --uri content://media/external/images/media --projection _display_name:owner_package_name:date_added`. `IMG20261114133431.dng` kreeg het label van `Pictures/AdobeLightroom/IMG20261114133431.jpg`, door `com.adobe.lrmobile` geschreven op 16 november om 00:11. Weg is dat label zodra dat bestand weg is (prullenbak in de selectiebalk).
Tegels van een DNG gaan door `MediaScanner.decode()` (ImageDecoder), net als de viewer. Android's `loadThumbnail` geeft het camerafilter niet mee, waardoor de tegel anders was dan de grote foto.
Viewer, naar beneden slepen (zoals Immich): eigen `awaitEachGesture` in de Initial-ronde op een vaste `Box` om de pager heen (zo schuiven de aanraakcoördinaten niet mee). De foto blijft onder je vinger in alle richtingen en krimpt tot 0,6; kop en knoppen zijn na een vijfde van het sleepbereik weg en de achtergrond wordt doorzichtig, want de galerij blijft in `AppRoot` onder de viewer staan (de viewer is een `AnimatedVisibility`-laag). Loslaten sluit bij meer dan 15% van de hoogte of een snelle worp, anders veert de foto terug met de snelheid van je vinger. Een tweede vinger (zoomen) en een zijwaartse beweging laten we aan de pager. Bij sluiten (slepen, pijl of terug) vliegt de foto terug naar zijn eigen tegel en snijdt zich daarbij bij tot het vierkantje (`Hero` in `ViewerScreen`): de tegels melden hun plek in `TileBounds`, de tegel van de open foto blijft leeg (`hiddenId`, het gat dat je in Immich ook ziet) en de lijst scrolt mee met de foto die je in de viewer toont. Openen werkt andersom: de foto groeit uit zijn tegel (de viewer meldt pas met `onCurrent` dat de tegel leeg moet, op het moment dat de animatie begint, anders staat er twee frames een gat) en de achtergrond en balken komen geleidelijk binnen (`openT`). De pager staat uit tot dat klaar is, en gebaren worden genegeerd zolang `opening` of `closing` geldt. Staat de tegel niet in beeld, of is de foto ingezoomd, dan vervaagt hij zoals eerst. De Lightroom-knop staat in de kop (alleen bij een DNG): een knop onderin liet de rij met Niet bewaren, Bewaren en prullenbak bij elke RAW omhoog springen.
Knoppen (`PrimaryPillButton`) centreren hun inhoud ook als `weight()` ze breder maakt dan de tekst.
Bewaren van een origineel waarvan al een bewerking meegaat (`Photo.askBeforeKeeping`: `edit != None` en zelf niet bewaard) vraagt eerst via `EditedKeepDialog`, in de viewer en in de selectiebalk, om geen dubbele foto's te krijgen (camera-versie naast de Lightroom-export). Drie keuzes: "Bewerkte versie" (origineel niet bewaard, kruisje), "Origineel" (origineel bewaard, de bewerking krijgt het kruisje en blijft in Lightroom of de Galerij staan) of "Allebei". `Photo.editIds` zijn de bestanden van de bewerking (door `Classifier` bepaald op bestandsnaam in de Lightroom-map, of opnametijd bij een Galerij-kopie). Wegvegen of terugtikken zonder te kiezen verandert niets. Is het origineel al bewaard voordat er een export kwam, dan vraagt hij niets; zet dan de export uit.
In de viewer draaien Bewaren en Niet bewaren terug door er nog een keer op te tikken (`onSelect(.., false)`, `KeeperPrefs.undoReject`) en blijf je dan op die foto; alleen het zetten van de keuze gaat door naar de volgende. Het kruisje weghalen zet wat standaard aanstaat (export, Galerij-kopie) ook weer aan.
Dubbeltikken om in of uit te zoomen is een animatie van 300 ms (`ZOOM_MS` in `ViewerScreen`, `zoomJob`); een vinger die beweegt breekt hem af en neemt het over, aanraken alleen niet (anders breekt de tweede tik zijn eigen animatie af).
Zuinig rekenen: `MediaScanner.scan()` loopt achter een `Mutex` en geeft de vorige `Snapshot` terug zolang `MediaStore.getGeneration` en de instellingen gelijk zijn (de standaard `sinceMs` is daarom één vaste waarde per start); de JPEG-kop wordt per id en grootte één keer gelezen; beeld maken loopt via `limitedParallelism(3)` en `loadThumbnail` stopt zodra de tegel uit beeld is (`CancellationSignal`); DNG-tegels staan als webp in `cacheDir/tiles` (id, grootte en rand in de naam); `MainViewModel.state` rekent op `Dispatchers.Default` en de keuzes-flows hebben `distinctUntilChanged`; `TileBounds` bewaart de `LayoutCoordinates` en rekent de rechthoek pas bij het sluiten uit. De update-controle bij openen is hooguit elke zes uur. De viewer toont alles in SDR (software-bitmap, geen gain map, geen HDR-kleurmodus); HDR is er alleen in de JPG die Keeper later schrijft.

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
