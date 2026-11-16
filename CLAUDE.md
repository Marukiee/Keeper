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
UI wordt dus libjpeg-q92 in de encoder, q90 tot q95 wordt q88 tot q93). Ontwerp van de run staat hieronder ("Omzetten").
Debug-hulp: `ConvertDebugReceiver` (alleen debug-build, via `adb shell am broadcast`) zet een foto uit `files/` om, of draait met `--es ids 8371,8100` de echte keten (`ConvertJob` met `ConvertOptions`: plan, lezen via MediaStore, schrijven naar `Pictures/KeeperTest/`, nooit de echte uitvoermap; `--ez original false` leest zonder `setRequireOriginal`, voor als de locatie-toestemming ontbreekt); `prototype/vergelijk.py` meet de afwijking met het prototype.

Omzetten (ontwerp, 2026-11-16). De Convert-knop (galerij en Overzicht) draait `ConvertJob` in een voorgrondservice (`dataSync`, melding met voortgang en Pauzeren; WorkManager staat niet in de offline Gradle-cache). De job scant en classificeert op het moment zelf opnieuw en doet alles wat `Photo.goesAlong` is, oudste eerst, één foto tegelijk (de `Converter` rekent zelf parallel).
- **Naam in de uitvoermap** (`scan/OutputName`): camera-JPG en Galerij-bewerking houden hun naam, de camera-versie van een DNG wordt `<naam>.jpg`, een Lightroom-export wordt `<naam>-lr.<ext>`. Zo botsen een JPG of DNG en zijn export nooit (MediaStore zou stilletjes "naam (1).jpg" maken). "Klaar" is de omgekeerde vraag: staat die naam in de uitvoermap (`Classifier`; een DNG telt alleen als hij bewaard is). `ConvertPlan` (zuiver, getest) slaat een naam die er al staat over en meldt een tweede foto met dezelfde naam. Camera-JPG en DNG met dezelfde naam komen op deze telefoon niet voor (gecontroleerd in DCIM/Camera); mocht het gebeuren, dan wordt de tweede gemeld en niet overschreven.
- **Wat er in de uitvoer komt**: de Exif van de camera byte voor byte (datum, GPS, lens, MakerNote, oriëntatie) en het ICC-profiel (Display P3); weg gaan XMP (hdrgm), MPF, gain map, QTI-segmenten en de Oppo-trailer (`OutputMetadata`). Geen whitelist: wat de camera schreef is goed. Zonder HDR (AsIs, Lightroom-export, Galerij-kopie zonder gain map) wordt het bestand byte voor byte gekopieerd, nooit opnieuw gecomprimeerd.
- **DNG**: `DngPreview` zoekt de JPEG in IFD0 (StripOffsets/StripByteCounts, compression 7), die gaat naar een tijdelijk bestand in `cacheDir/convert` (de MPF-offsets tellen vanaf die JPEG) en daar draait de `Converter` op. De Exif van die preview heeft dezelfde oriëntatie als de DNG (gemeten op een staande foto).
- **GPS**: MediaStore haalt de locatie uit elk origineel tenzij je `ACCESS_MEDIA_LOCATION` hebt én `MediaStore.setRequireOriginal(uri)` gebruikt; dat doet elke lezing (ook het kopiëren). Zonder die toestemming start de run niet (geen stil verlies van locatie).
- **Pauzeren en voortgang** (2026-11-16): de Stop-knop is Pauzeren (`ConvertRepository.requestPause`, ook de melding en de Android 15-timeout). Pauzeren stopt na de foto waar hij mee bezig is, of midden in (het halve bestand verdwijnt); `ConvertReport.paused/left` meldt hoeveel er nog stond. Doorgaan is gewoon weer starten: wat in de uitvoermap staat telt als Klaar (`Classifier`), dus er is geen aparte lijst om bij te houden, en wat Mark uit de map verwijdert komt vanzelf weer aan de beurt. `KeeperPrefs.convertPaused` onthoudt alleen dat de knop Doorgaan moet heten. Het venster toont het voorbeeld van de foto die bezig is, "Foto n van m", de naam, wat ermee gebeurt (HDR naar SDR, camera-versie van de RAW, kopie) en hoeveel er al van eerder klaarstond.
- **Snelheid** (2026-11-16): de JPEG-encoder was het trage deel (een thread, ~85% van de tijd). `JpegEncoder` zet nu na elke MCU-rij een restart-marker (DRI) en codeert MCU-rijen parallel op een pool (`pool`); de bytes zijn per rij dezelfde als bij een thread, dus de pixels veranderen niet (test: precies dezelfde bytes met en zonder pool, behalve de RSTn-markeringen die er in beide gevallen staan). `Converter` decodeert bovendien de volgende strook terwijl de huidige gerekend wordt. 50 MP HDR-JPG op de telefoon (debug-app, warm): 6,2 s naar 4,2 s; de rest is rekenwerk (decoderen ~0,9 s wachttijd, pixels ~2,5 s, coderen op de pool).
- **Schrijven**: `MediaStore` insert in `Pictures/Keeper/` met `IS_PENDING=1`, afmaken zet het op 0; bij mislukken of stoppen wordt alleen de eigen halve rij verwijderd. Per foto afgevangen: één mislukte foto stopt de run niet. Een NoGainMapException valt terug op kopiëren.
- **Gemeten op de telefoon (2026-11-16, debug-keten, 5 foto's)**: HDR-JPG 3,1 naar 1,2 MB, Galerij-bewerking van 75 MP 17,3 naar 9,6 MB (~12 s), staande DNG (oriëntatie 90) 60 MB naar 4,9 MB uit een preview van 18 MB, liggende DNG naar 6,0 MB; Lightroom-export byte voor byte gelijk. Exif (datum, GPS, lens, ISO, oriëntatie), afmetingen en ICC (Display P3) kloppen met de bron, `hdrgm` en MPF zijn weg, de staande uitvoer is rechtop en komt overeen met de preview. **Galerij-bewerkingen van de Oppo houden de gain map** (hdrgm + MPF), dus `MediaScanner` probeert ze ook (eerst niet: dan werden ze als HDR gekopieerd). Op deze telefoon bleef GPS ook zonder `ACCESS_MEDIA_LOCATION` bewaard (de toestemming stond uit); we eisen hem toch, want Android's regel is dat zonder die toestemming de locatie eruit gehaald wordt. Niet op de telefoon geprobeerd: de service, de melding, het dialoogvenster en het pad mét de toestemming (`setRequireOriginal`), omdat dat de toestemming vraagt of de echte selectie omzet. Kwaliteit is de gekozen q min 2 (zie hierboven), tone map uit de instellingen.

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
Daarna (2026-11-16, na de eerste echte omzetting op 38 foto's): Mark vond de uitvoer te fel en bij een DNG-camera-versie te weinig contrast. Gemeten
op 30 HDR-foto's: de omzetting was gemiddeld 1,29 keer zo licht (lineair) als de SDR-basis die de camera zelf maakt, tot 1,6 bij avondfoto's, met
opgetilde zwarten (p5 +7) en bleke huid, omdat de gain map midden- en schaduwtonen optilt. Nu zoekt `ToneMapper.matching` per foto de belichting
(hooguit de belichting van de gekozen sterkte, minimaal 0,5) waarbij de gemiddelde helderheid gelijk is aan die van de camera-SDR; de eerste pass
(`Converter.measure`) levert daarvoor een steekproef van elke vierde pixel van elke vierde rij. Op de telefoon: helderheid x1,01 (was x1,16 tot x1,32),
Hasselblad-DNG p5 39 naar 41 en p50 77 naar 78 (was 46 en 87). De sterkten verschillen nu vooral in de knie (0,60 / 0,45 / 0,40). Wat al in de
uitvoermap staat, wordt niet opnieuw gedaan: wie oudere (te felle) uitvoer wil vervangen, verwijdert die uit `Pictures/Keeper`.

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
Instellingen zijn ingedeeld zoals in Tandem: het tabblad toont categorieën met een regeltje over wat er gekozen is (`SettingsScreen`, groepen `Look` / `Folders, Convert, Raw` / `Updates`), elke categorie opent een eigen pagina (`ui/settings/SettingsPages.kt`, `AppearancePage.kt`; `SettingsPage` in `AppRoot` als `Sub.Page`, Terug van Uiterlijk gaat naar Uiterlijk en taal). Het thema is een instelling in `KeeperPrefs.appearance` (modus systeem/licht/donker, accent uit `ColourSeed`, paletstijl, puur zwart) en `MainActivity` leest het vóór het eerste beeld. **Puur zwart (OLED) staat standaard uit**, donker is dan Material's donkergrijs. De previews in `ui/appearance` komen uit Tandem. Geen zoekveld: voor vijf categorieën is dat teveel.

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
