"""Verzamelt paren (Lightroom-weergave zonder Look, voorbeeld in de DNG met het cameranfilter) voor een lijst DNG's.

Het voorbeeld in een Oppo-DNG (IFD0, volledige grootte, Display P3) heeft het filter al toegepast en ligt exact over de RAW heen.
Lightroom toont dat voorbeeld eerst en rendert dan de RAW zelf; wij fotograferen het scherm nadat de RAW is gerenderd.
Gebruik: .venv/bin/python prototype/lr_collect.py UITMAP DNG [DNG ...]
Schrijft UITMAP/<naam>.npz met lr (uint8, sRGB-codes van het scherm) en ref (uint8, Display P3-codes van het voorbeeld, even groot).
"""
import io
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageOps

sys.path.insert(0, os.path.dirname(__file__))
import lr_basis as B
import lr_probe as P

Image.MAX_IMAGE_PIXELS = None


def preview_p3(dng, size):
    data = subprocess.run(["exiftool", "-b", "-PreviewImage", dng], capture_output=True).stdout
    im = Image.open(io.BytesIO(data)); im.draft("RGB", (size[0] * 2, size[1] * 2))
    im = ImageOps.exif_transpose(im.convert("RGB"))
    return im


def collect(dng, outdir, wait=12):
    name = os.path.splitext(os.path.basename(dng))[0]
    base = f'<rdf:Description rdf:about="" {P.CRS} crs:Version="18.4" crs:ProcessVersion="15.4" crs:HasSettings="True"/>'
    png = P.probe("c" + name[-6:], base, dng, outdir, wait=wait)
    P.opruimen(["c" + name[-6:]])
    lr = B.lr_content(png)
    ref = preview_p3(dng, lr.size)
    ratio = (ref.width / ref.height) / (lr.width / lr.height)
    if abs(ratio - 1) > 0.02:
        raise RuntimeError(f"{name}: beeldverhouding wijkt af ({ratio:.3f})")
    ref = ref.resize(lr.size, Image.LANCZOS)
    np.savez_compressed(os.path.join(outdir, name + ".npz"), lr=np.asarray(lr), ref=np.asarray(ref))
    return name, lr.size


if __name__ == "__main__":
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for f in sys.argv[2:]:
        try:
            print(*collect(f, out), flush=True)
        except Exception as e:
            print("MISLUKT", f, e, flush=True)
