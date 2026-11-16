"""Maakt fase0/resultaten/vergelijking.html: per foto de vier versies naast elkaar (paneel 1 is het echte HDR-bestand)."""
import html
import json
import os
import sys

out = sys.argv[1]
fotos = sorted(d for d in os.listdir(out) if os.path.exists(os.path.join(out, d, "resultaat.json")))
sizes = json.load(open(os.path.join(out, "groottes.json"))) if os.path.exists(os.path.join(out, "groottes.json")) else []

PANELEN = [
    ("1-origineel-proxdr.jpg", "1. Origineel met ProXDR (echte HDR)", "Het bestand zelf. Chrome en Safari tonen de gain map op een HDR-scherm, zet de helderheid van je Mac op maximum."),
    ("2-basis.jpg", "2. Alleen basisbeeld", "Gain map weg: wat een viewer zonder HDR laat zien."),
    ("3-tonemap.jpg", "3. Mijn tone map", "Normaal (k=0.9, knie=0.45), jpegli q90. Dit is het formaat dat de app later maakt."),
    ("4-referentie-apple.jpg", "4. Apple, headroom uit metadata", "CIToneMapHeadroom zoals Apple hem standaard gebruikt: veel te donker, want de metadata belooft 4,9x headroom die de foto niet gebruikt."),
    ("4b-referentie-apple-inhoudspiek.jpg", "4b. Apple, echte piek van de inhoud", "Dezelfde Apple-curve met de gemeten piek: eerlijker referentie."),
]

h = ["""<!doctype html><html lang="nl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Daglicht fase 0</title><style>
body{background:#000;color:#eee;font:16px/1.5 -apple-system,system-ui,sans-serif;margin:0;padding:24px}
h1{font-size:28px;margin:0 0 4px}h2{font-size:22px;margin:48px 0 8px}p.n{color:#aaa;max-width:80ch}
.g{display:grid;grid-template-columns:repeat(auto-fit,minmax(340px,1fr));gap:12px}
figure{margin:0;background:#141414;border-radius:20px;overflow:hidden}figcaption{padding:12px 16px}
figcaption b{display:block}figcaption span{color:#999;font-size:13px}
img{width:100%;display:block}table{border-collapse:collapse;margin:8px 0}td,th{padding:4px 14px 4px 0;text-align:right}th:first-child,td:first-child{text-align:left}
.v img{border-radius:20px}</style>
<h1>Daglicht fase 0: HDR naar SDR</h1>
<p class="n">Open in Chrome of Safari op je MacBook. Alleen paneel 1 is echte HDR; de rest zijn gewone SDR-bestanden (Display P3).
Klik op een beeld voor volledige resolutie.</p>"""]
for f in fotos:
    r = json.load(open(os.path.join(out, f, "resultaat.json")))
    g = r["groottes"]
    h.append(f'<h2>{html.escape(f)} <small style="color:#888;font-size:14px">{r["afmeting"]}, {r["mp"]} MP, piek van de inhoud {r["piek"]}x</small></h2>')
    trailer = f' + OnePlus-trailer {g["trailer"]/1e6:.2f}' if g.get("trailer", 0) > 1000 else ""
    h.append(f'<p class="n">Origineel {g["origineel"]/1e6:.2f} MB (basis {g["basis_alleen"]/1e6:.2f} + gain map {g["gainmap"]/1e6:.2f}{trailer}). '
             f'Mijn tone map als jpegli q90: {g["tonemap_q90"]/1e6:.2f} MB.</p><div class="g">')
    for fn, titel, uitleg in PANELEN:
        h.append(f'<figure><a href="{f}/{fn}" target="_blank"><img loading="lazy" decoding="async" src="{f}/{fn}"></a>'
                 f'<figcaption><b>{titel}</b><span>{html.escape(uitleg)}</span></figcaption></figure>')
    h.append(f'</div><p class="n" style="margin-top:16px">Drie sterktes van mijn tone map naast elkaar:</p><div class="v"><a href="{f}/varianten.png" target="_blank"><img loading="lazy" src="{f}/varianten.png"></a></div>')
if sizes:
    h.append("<h2>Bestandsgrootte tegen kwaliteit</h2><p class='n'>Grootte van het hele beeld; ssimulacra2 op een uitsnede van 2048x2048 (90 en hoger is praktisch niet van het origineel te onderscheiden, 70 tot 90 is goed).</p>")
    for f in fotos:
        rows = [s for s in sizes if s["foto"] == f]
        h.append(f"<h3>{html.escape(f)}</h3><table><tr><th>codec</th><th>MB</th><th>bits/pixel</th><th>ssimulacra2</th></tr>")
        for s in rows:
            h.append(f'<tr><td>{html.escape(s["codec"])}</td><td>{s["bytes"]/1e6:.2f}</td><td>{s["bpp"]}</td><td>{s["ssimulacra2"]}</td></tr>')
        h.append("</table>")
h.append("</html>")
open(os.path.join(out, "vergelijking.html"), "w").write("\n".join(h))
print("ok", len(fotos), "foto's")
