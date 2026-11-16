"""Warm flash-model (3D-LUT plus vignet) als tabel voor een Lightroom-Look, in Display P3 met sRGB-curve (het domein van het model)."""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
import warmflash_fit as W
from warmflash_model import enc, lin


def table_from_model(npz, n=33, center_gain=1.0):
    """Knopen [r][g][b] (n^3, 3) van de tabel. center_gain: factor in lineair licht die we in de tabel bakken (rest doet het vignet)."""
    z = np.load(npz)
    g = np.linspace(0, 1, n)
    grid = np.stack(np.meshgrid(g, g, g, indexing="ij"), -1).reshape(-1, 3)
    out = W.apply_lut(z["lut"], grid)
    if center_gain != 1.0:
        out = enc(lin(np.clip(out, 0, 1)) * center_gain)
    return np.clip(out, 0, 1).reshape(n, n, n, 3), z["v"]
