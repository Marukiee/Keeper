"""Vergelijkt de omzetting van de Android-app met het Python-prototype, pixel voor pixel.

Gebruik: .venv/bin/python prototype/vergelijk.py ORIGINEEL.jpg UIT_VAN_DE_APP.jpg [tonemap]
Het prototype levert de referentie (tone map, zonder JPEG); de app-uitvoer is gecodeerd, dus een kleine afwijking is normaal.
"""
import io
import sys

import numpy as np
import pyjpegli
from PIL import Image

sys.path.insert(0, "prototype")
import uhdr
from tonemap import PRESETS, luma, peak_from_hist, srgb_oetf_u8, tonemap

Image.MAX_IMAGE_PIXELS = None
src, app = sys.argv[1], sys.argv[2]
name = sys.argv[3] if len(sys.argv) > 3 else "normaal"
ex, knee, mix = PRESETS[name]

u = uhdr.UltraHdr(src)
edges = np.concatenate([[0.0], np.geomspace(1e-4, 8, 2000)])
counts = np.zeros(2000, dtype=np.int64)
for _y0, _y1, _sdr, hdr in u.strips(256):
    counts += np.histogram(luma(hdr[::4, ::4]), bins=edges)[0]
peak = peak_from_hist(counts, edges)
print("piek prototype", round(float(peak), 4))

dec = np.asarray(Image.open(app).convert("RGB"))
h, w = dec.shape[:2]
assert (h, w) == (u.height, u.width), ((h, w), (u.height, u.width))
sq = np.zeros(3); n = 0; hist = np.zeros(256, dtype=np.int64)
ref_rows = []
for y0, y1, _sdr, hdr in u.strips(256):
    ref = srgb_oetf_u8(tonemap(hdr, peak, ex, knee, mix)).astype(np.int16)
    d = dec[y0:y1].astype(np.int16) - ref
    sq += (d.astype(np.float64) ** 2).sum(axis=(0, 1)); n += d.shape[0] * d.shape[1]
    hist += np.bincount(np.abs(d).ravel().clip(0, 255), minlength=256)
    if y0 % 1024 == 0:
        ref_rows.append(ref[::4, ::4].astype(np.uint8))
mse = sq / n
cum = np.cumsum(hist) / hist.sum()
print("MAE (via histogram)", round(float((np.arange(256) * hist).sum() / hist.sum()), 3),
      "p50", int(np.searchsorted(cum, 0.5)), "p99", int(np.searchsorted(cum, 0.99)), "p99.9", int(np.searchsorted(cum, 0.999)), "max", int(np.nonzero(hist)[0].max()))
print("PSNR per kanaal", [round(float(10 * np.log10(255 ** 2 / m)), 2) for m in mse])

# Bestandsgrootte: dezelfde referentie in jpegli q94 en libjpeg-turbo, op de eerste 2048 rijen (rekenen met een uitsnede)
_a, _b, _s, hdr1 = next(u.strips(1024))
crop = srgb_oetf_u8(tonemap(hdr1, peak, ex, knee, mix))
bpp = lambda b, shape: round(len(b) * 8 / (shape[0] * shape[1]), 3)
print("uitsnede 1024 rijen:", crop.shape)
print("  jpegli q94    bpp", bpp(pyjpegli.encode(np.ascontiguousarray(crop).tobytes(), crop.shape[1], crop.shape[0], quality=94), crop.shape))
for q in (88, 90, 91, 92, 93):
    for opt in (False, True):
        b = io.BytesIO(); Image.fromarray(crop).save(b, "JPEG", quality=q, optimize=opt, subsampling=2)
        print(f"  libjpeg q{q} optimize={opt!s:5} bpp", bpp(b.getvalue(), crop.shape))
appcrop = dec[:1024]
print("  app (q92)     bpp over de hele foto:", round(len(open(app, 'rb').read()) * 8 / (w * h), 3))
