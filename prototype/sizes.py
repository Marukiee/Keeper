"""Bestandsgrootte tegen kwaliteit: jpegli, libjpeg-turbo, mozjpeg en HEIC op de tone-mapped foto's.

Grootte is van het hele beeld; kwaliteit (ssimulacra2, 90+ is visueel verliesvrij) op een centrale uitsnede van 2048x2048.
Gebruik: SCRATCH=<map met *_normaal.npy> .venv/bin/python prototype/sizes.py <uitmap>
"""
import glob
import io
import json
import os
import subprocess
import sys
import time

import numpy as np
import pyjpegli
from PIL import Image

MOZ = "/opt/homebrew/opt/mozjpeg/bin/cjpeg"
CROP = 2048


def ssim2(a, b):
    r = subprocess.run(["ssimulacra2", a, b], capture_output=True, text=True).stdout.strip()
    return float(r.split()[-1]) if r else float("nan")


def crop_of(arr):
    h, w = arr.shape[:2]
    y, x = max((h - CROP) // 2, 0), max((w - CROP) // 2, 0)
    return arr[y : y + CROP, x : x + CROP]


def main(outdir, scratch):
    rows = []
    for npy in sorted(glob.glob(os.path.join(scratch, "*_normaal.npy"))):
        name = os.path.basename(npy).replace("_normaal.npy", "")
        arr = np.load(npy)
        h, w = arr.shape[:2]
        tmp = os.path.join(scratch, "sz-" + name)
        os.makedirs(tmp, exist_ok=True)
        ref = os.path.join(tmp, "ref.png")
        Image.fromarray(crop_of(arr)).save(ref)
        im = Image.fromarray(arr)

        def record(label, data, decoded):
            Image.fromarray(crop_of(np.asarray(decoded.convert("RGB")))).save(os.path.join(tmp, "d.png"))
            rows.append({"foto": name, "mp": round(w * h / 1e6, 1), "codec": label, "bytes": len(data),
                         "bpp": round(len(data) * 8 / (w * h), 3), "ssimulacra2": round(ssim2(ref, os.path.join(tmp, "d.png")), 1)})
            print(rows[-1], flush=True)

        for q in (80, 85, 87, 90, 93, 95):
            d = pyjpegli.encode(arr.tobytes(), w, h, quality=q)
            record(f"jpegli q{q}", d, Image.open(io.BytesIO(d)))
        for q in (85, 90):
            b = io.BytesIO()
            im.save(b, "JPEG", quality=q, optimize=True, subsampling=2)
            record(f"libjpeg-turbo q{q} (4:2:0)", b.getvalue(), Image.open(io.BytesIO(b.getvalue())))
        if os.path.exists(MOZ):
            ppm = os.path.join(tmp, "in.ppm")
            im.save(ppm)
            d = subprocess.run([MOZ, "-quality", "85", ppm], capture_output=True).stdout
            record("mozjpeg q85", d, Image.open(io.BytesIO(d)))
        full = os.path.join(tmp, "full.png")
        im.save(full)
        for q in (55, 70):
            t = time.time()
            out = os.path.join(tmp, f"q{q}.heic")
            subprocess.run(["heif-enc", "-q", str(q), "-o", out, full], check=True, capture_output=True)
            dec = os.path.join(tmp, f"q{q}.png")
            subprocess.run(["heif-dec", out, dec], check=True, capture_output=True)
            data = open(out, "rb").read()
            record(f"HEIC q{q}", data, Image.open(dec))
    json.dump(rows, open(os.path.join(outdir, "groottes.json"), "w"), indent=1)


if __name__ == "__main__":
    main(sys.argv[1], os.environ["SCRATCH"])
