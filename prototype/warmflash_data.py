"""Laadt de Warm flash-paren (bron uit de trailer, eindbeeld) verkleind en uitgelijnd, en bewaart ze als .npz.

Gebruik: .venv/bin/python prototype/warmflash_data.py MAP_MET_FOTOS UITMAP [langste_zijde]
Beide beelden zijn de SDR-basis (Display P3, sRGB-transfer), gedraaid naar de weergavestand.
"""
import glob
import io
import os
import sys

import numpy as np
from PIL import Image, ImageOps

sys.path.insert(0, os.path.dirname(__file__))
import oppo_trailer
import uhdr

Image.MAX_IMAGE_PIXELS = None


def base_of(jpeg_bytes, size):
    parts = uhdr.split_mpf(jpeg_bytes)
    im = Image.open(io.BytesIO(parts[0]))
    im.draft("RGB", (size, size))
    im = ImageOps.exif_transpose(im.convert("RGB"))
    return im


def load_pair(path, long_side):
    d = open(path, "rb").read()
    t = oppo_trailer.read_trailer(d)
    if not t or "src.image" not in t:
        return None
    fin = base_of(d, long_side)
    src = base_of(t["src.image"], long_side)
    if fin.size != src.size:
        src = src.resize(fin.size, Image.LANCZOS)
    scale = long_side / max(fin.size)
    if scale < 1:
        new = (round(fin.width * scale), round(fin.height * scale))
        fin, src = fin.resize(new, Image.LANCZOS), src.resize(new, Image.LANCZOS)
    return np.asarray(src), np.asarray(fin)


if __name__ == "__main__":
    folder, out = sys.argv[1], sys.argv[2]
    side = int(sys.argv[3]) if len(sys.argv) > 3 else 1024
    os.makedirs(out, exist_ok=True)
    for f in sorted(glob.glob(os.path.join(folder, "*.jpg"))):
        r = load_pair(f, side)
        if r is None:
            continue
        src, fin = r
        np.savez_compressed(os.path.join(out, os.path.basename(f)[:-4] + ".npz"), src=src, fin=fin)
        print(os.path.basename(f), src.shape)
