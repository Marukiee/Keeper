"""Meet het vignet van Lightroom (PostCropVignette*) en zoekt de instelling die het best bij een gewenst radiaal profiel past."""
import glob
import os
import re
import sys

import numpy as np
from PIL import Image
from scipy import ndimage as ndi

sys.path.insert(0, os.path.dirname(__file__))
from warmflash_model import gain_at, lin, radius

BINS = np.linspace(0, 1, 11)
MIDS = (BINS[:-1] + BINS[1:]) / 2


def profile(base_png, with_png, y_top=331, y_bottom=1769):
    """Mediaan van lineair licht met / zonder vignet, per straalbin (corner = 1). Beide schermafdrukken van dezelfde foto."""
    a0 = np.asarray(Image.open(base_png).convert("RGB"))[y_top:y_bottom]
    a1 = np.asarray(Image.open(with_png).convert("RGB"))[y_top:y_bottom]
    m = a0.max(2) > 4
    cols = np.where(m.any(0))[0]; rows = np.where(m.any(1))[0]
    sl = (slice(rows.min(), rows.max() + 1), slice(cols.min(), cols.max() + 1))
    b = a0[sl].astype(float) / 255; w = a1[sl].astype(float) / 255
    r = radius(*b.shape[:2]).reshape(b.shape[:2])
    lb = lin(ndi.gaussian_filter(b, (2, 2, 0))).mean(2); lw = lin(ndi.gaussian_filter(w, (2, 2, 0))).mean(2)
    ok = lb > 0.03
    out = []
    for i in range(10):
        s = ok & (r >= BINS[i]) & (r < BINS[i + 1])
        out.append(np.median(lw[s] / lb[s]))
    return np.array(out)


def known(scherm_dir, base="scherm-m0.png"):
    """Alle bekende (amount, midpoint) -> profiel uit de schermafdrukken scherm-h<amount>_<mid>.png in scherm_dir."""
    res = {}
    for f in glob.glob(os.path.join(scherm_dir, "scherm-h*_*.png")):
        m = re.search(r"scherm-h(\d+)_(\d+)\.png", f)
        res[(-int(m.group(1)), int(m.group(2)))] = profile(os.path.join(scherm_dir, base), f)
    return res


def best(target_v, profiles):
    t = gain_at(target_v, MIDS)
    return sorted((float(np.sqrt(((p - t) ** 2).mean())), k) for k, p in profiles.items())
