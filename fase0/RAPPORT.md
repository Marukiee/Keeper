# Daglicht, fase 0: HDR naar SDR

Open `fase0/resultaten/vergelijking.html` in Chrome of Safari op je MacBook (XDR-scherm, helderheid op maximum).
Per foto staan daar vier versies naast elkaar. Paneel 1 is het echte Ultra HDR-bestand, dus alleen daar zie je ProXDR.
Ik heb de pagina zelf niet in een browser kunnen testen. Toont paneel 1 geen HDR, open dan `1-origineel-proxdr.jpg` in Preview.

## Wat ik gemeten heb

**De reconstructie klopt.** Mijn eigen Ultra HDR-decoder geeft dezelfde HDR als Apple's CoreImage: gemiddeld 0,7% relatief
verschil per pixel, en de gemiddelde winst is 1,448 tegen 1,448. De HDR-formule uit de spec is dus goed toegepast.

**Hoe de Oppo-gain map eruitziet.** Min 0, max 2,30 stops (4,9x), gamma 1, offsets 0 (de spec adviseert 1/64), drie
kanalen, half zo groot als de foto. Gain is nooit negatief: HDR is altijd gelijk aan of helderder dan de basis.

**Waarom het zonder ProXDR plat en lelijk is.** De basis is bewust donker: gemiddeld 1,45x helderder in HDR (1,67x bij
de tuinfoto). De hoogste lichten in de basis zitten op 0,51 tot 0,79 van wit, dus er zit geen echt wit in. De HDR-piek van
de inhoud is maar 0,9 tot 1,8x SDR-wit, ver onder de 4,9x die de metadata belooft. Het gaat dus vooral om een halve stop
helderder en meer contrast, geen extreme highlights. Dat betekent ook dat de SDR-versie vooral een goede opheldering met
zachte lichten moet zijn.

**Apple's standaard tone map is niet bruikbaar als doel.** Hij neemt de 4,9x uit de metadata als bron en maakt het beeld
donkerder dan de basis (gemiddelde helderheid 0,31 tegen 0,34, tuinfoto). Met de gemeten piek in plaats van de metadata
(paneel 4b) komt hij dicht bij mijn tone map. Daarom heb ik beide als referentie erbij gezet.

## De tone map

Een globale curve per pixel: lineair tot een knie, daarboven een zachte schouder die de gemeten piek van de foto precies
op wit brengt. Een halve luminantiecurve, een halve per-kanaalcurve, zodat blauwe lucht blauw blijft. De curve werkt per
pixel, dus het gaat gewoon in tegels, en de gain map wordt per rijstrook bilineair opgeschaald. De piek komt uit een
eerste pass op elke vierde pixel.

Mijn eerste versie (knie 0,70) brandde de lucht uit: 4,2% van de pixels op wit, tegen 0,1% in de basis, en de wolken
verloren hun tekening. Ik heb de knie verlaagd. Dat helpt maar deels: op 100% houden de wolken bovenin wat tekening, maar de
lucht rond de horizon is nog vrijwel egaal wit, helderder dan in de basis. Dat is de prijs van de opheldering; lichten-behouden
doet het beter. Drie sterktes staan als `varianten.png` per foto:

| naam | belichting | knie | tuinfoto: pixels op wit |
|---|---|---|---|
| licht | 1,00 | 0,60 | niet gemeten |
| normaal (standaard in de vergelijking) | 0,90 | 0,45 | 1,7% |
| lichten-behouden | 0,80 | 0,40 | 1,1% |

Gemiddelde helderheid tuinfoto: basis 0,344, licht 0,401, normaal 0,384, lichten-behouden 0,368.
Of dit mooi is, is jouw oordeel. Ik kan HDR niet zien; ik heb alleen de SDR-beelden bekeken en de cijfers gemeten.

**Belangrijke beperking van deze test.** Vier van de vijf foto's zijn onscherpe warme verlopen (de twee JPG's en twee DNG's
van 12,5 MP). Daar is de tone map nauwelijks te beoordelen. Alleen de 50 MP-tuinfoto (DNG, ultragroothoek) is een echte
scène met lucht, schaduw en gras. De telefoon was niet bereikbaar via adb, dus ik kon geen extra foto's ophalen.

## Bestandsgrootte

Tuinfoto, 50 MP, tone-mapped (kwaliteit gemeten met ssimulacra2 tegen het tone-mapped beeld; 90 is praktisch niet te
onderscheiden, 85 is hoog):

| versie | MB | bits/pixel | score |
|---|---|---|---|
| origineel (basis 13,66 + gain map 4,80, geen trailer) | 18,46 | | |
| jpegli q85 | 3,58 | 0,57 | 81,9 |
| **jpegli q90** | **4,53** | 0,72 | 85,2 |
| jpegli q93 | 5,79 | 0,92 | 87,7 |
| jpegli q95 | 7,26 | 1,15 | 89,2 |
| libjpeg-turbo q90 (4:2:0) | 5,92 | 0,94 | 86,4 |
| mozjpeg q85 | 4,22 | 0,67 | 83,9 |
| HEIC q55 | 3,32 | 0,53 | 83,9 |
| HEIC q70 | 8,46 | 1,34 | 89,5 |

- jpegli q90 is ongeveer 75% kleiner dan het origineel. Je Lightroom-export (0,9 bits/pixel) komt overeen met jpegli q93.
- jpegli is bij gelijke kwaliteit ongeveer 10 tot 20% kleiner dan libjpeg-turbo; mozjpeg zit er vlak naast.
  HEIC is nog ongeveer 20% kleiner dan jpegli, maar je koos JPEG.
- "Visueel verliesvrij" in de zin van score 90 vraagt jpegli q95 (7,3 MB). Q90 tot q93 is voor een gewone foto
  nauwelijks van het origineel te onderscheiden. Jouw keuze.
- De 12,5 MP-foto's komen op 0,3 MB door hun gladde inhoud. Dat zegt niets over echte foto's.
- Een 12,5 MP-JPG van de telefoon is 3,6 MB: basis 2,11, gain map 0,23 en daarna een OnePlus-trailer van 1,26 MB
  (`src.image`, `filter.info`, `hdr.transform.data`), die niet meegaat. De DNG-previews hebben die trailer niet. Dat is een extra besparing, maar de naam van het filter (bijvoorbeeld Warm Flash) gaat dan verloren.
  Voor de latere presets kan de app de filternaam apart bewaren.

## Metadata (eerste controle, de volledige exiftool-diff is fase 2)

Behouden en gecontroleerd op alle vijf: GPS, DateTimeOriginal, SubSecTimeOriginal, OffsetTimeOriginal, orientation,
make, model, lens, ICC (Display P3). Weg: hdrgm-XMP, GContainer, MPF en gain map (de uitvoer bevat geen tweede beeld),
het APP2-segment ISO 21496-1 (`urn:iso:std:iso:ts:21496:-1`), de oude Exif-thumbnail, de OnePlus-trailer. Het prototype maakt een
nieuw bestand en neemt alleen gekozen tags over, dus die segmenten ontbreken vanzelf. De app in fase 2 moet ze expliciet
weglaten als hij APP-segmenten uit de bron kopieert, anders ziet een viewer de foto nog als HDR. De DNG-preview heeft orientation 6; die blijft staan, we draaien geen pixels.

## Voor de app (fase 2)

- jpegli zit niet in Android. Opties: libjxl-jpegli via de NDK, of `Bitmap.compress` (libjpeg-turbo, ongeveer 10 tot 20% groter
  bij gelijke kwaliteit). ICC en Exif schrijven we zelf als APP-segmenten, zodat androidx ExifInterface niet nodig is.
- 200 MP: niet getest, ik heb geen bestand. Het ontwerp is tegelgebaseerd; op Android decoderen we per rijstrook, niet het
  hele beeld.
- Controle op de gain map gebeurt per bestand (hij ontbrak in de uitvoer; de decoder meldt `has_gainmap` apart).

## Auto-update, stijl en icoon (jouw wens)

Staat in `CLAUDE.md` voor fase 1: dezelfde update-checker en release-workflow als Tandem (GitHub releases-feed, vaste
bestandsnaam, vaste release-sleutel), dezelfde designtaal en een icoon in dezelfde stijl. Een nieuwe sleutel en een GitHub-repo
maak ik pas na jouw OK aan.

## Wat ik van je nodig heb

1. Kijk in `vergelijking.html` en kies: licht, normaal of lichten-behouden (of zeg wat je anders wilt).
2. Kwaliteit: q90, q93 of q95.
3. Een paar echte scènes als de telefoon weer via adb bereikbaar is (zon en schaduw, binnen, avond, Warm Flash, 200 MP),
   zodat ik de tone map niet op één foto kies.
4. Akkoord voor fase 1.
