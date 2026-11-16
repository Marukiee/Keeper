"""Hoe ver ligt Lightroom's standaardweergave van een Oppo-RAW van de weergave van de camera (src.image in de trailer van de JPG)?

Leest een schermafdruk van Lightroom (alleen het beeldgebied) en de JPG van hetzelfde moment, legt ze over elkaar en schat een kleine
correctie C (tooncurve per kanaal + 3x3-matrix) van Lightroom naar camera. Gebruik als module.
"""
import io
import os
import sys

import numpy as np
from PIL import Image, ImageCms

sys.path.insert(0, os.path.dirname(__file__))
from oppo_trailer import read_trailer

Image.MAX_IMAGE_PIXELS = None


def to_srgb(im):
    icc = im.info.get("icc_profile")
    if icc:
        im = ImageCms.profileToProfile(im, ImageCms.ImageCmsProfile(io.BytesIO(icc)), ImageCms.createProfile("sRGB"), outputMode="RGB")
    return im


def load_jpeg_srgb(data, size):
    im = Image.open(io.BytesIO(data)); im.draft("RGB", size)
    return to_srgb(im.convert("RGB"))


def oppo_pair(jpg_path, size=(1200, 1200)):
    d = open(jpg_path, "rb").read()
    t = read_trailer(d)
    return load_jpeg_srgb(t["src.image"], size), load_jpeg_srgb(d, size)


def lr_content(png, y_top=331, y_bottom=1769):
    a = np.asarray(Image.open(png).convert("RGB"))[y_top:y_bottom]
    m = a.max(2) > 4
    cols = np.where(m.any(0))[0]; rows = np.where(m.any(1))[0]
    return Image.fromarray(a[rows.min():rows.max() + 1, cols.min():cols.max() + 1])


def small_gray(im, w=96):
    h = max(1, int(round(im.height * w / im.width)))
    return np.asarray(im.resize((w, h), Image.BILINEAR).convert("L"), dtype=float)


def align(lr, ref):
    """Draait/schaalt ref zodat hij over lr ligt; geeft ref als array met dezelfde afmeting als lr (klein). Zoekt rotatie en verschuiving."""
    best = None
    for k in range(4):
        r = ref.rotate(90 * k, expand=True)
        r2 = r.resize(lr.size, Image.BILINEAR)
        a = small_gray(lr); b = small_gray(r2)
        a = (a - a.mean()) / (a.std() + 1e-6); b = (b - b.mean()) / (b.std() + 1e-6)
        score = (a * b).mean()
        if best is None or score > best[0]:
            best = (score, k, r2)
    return best
