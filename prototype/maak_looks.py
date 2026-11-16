"""Bouwt de Look-bestanden voor Keeper (assets/looks/<id>.xml) uit gefitte modellen (looks/<id>-model.npz).
Elk bestand is één rdf:Description voor in de XMP van een DNG: een Look met 32^3-tabel (Display P3, sRGB-curve) en de vignet-instellingen.
Gebruik: .venv/bin/python prototype/maak_looks.py [id ...]"""
import hashlib
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
import adobe_look as A
from lr_eval import table_from, vignette_settings

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PRESETS = {
    # id: (naam zoals Oppo het noemt, vignet (amount, midpoint))
    "warm-flash": ("Warm flash", (-25, 65)),
    "fresh": ("Fresh", None),
    "vintage": ("Vintage", None),
}


def build(pid):
    name, vig = PRESETS[pid]
    z = np.load(os.path.join(ROOT, "looks", f"{pid}-model.npz"))
    uuid = hashlib.md5(f"keeper-look-{pid}".encode()).hexdigest().upper()
    vig = tuple(z["vig"]) if "vig" in z.files else vig
    extra = vignette_settings(*[int(v) for v in vig]) if vig else ""
    xml = A.dng_look_description(name, table_from(z["lut"], 32), uuid, extra_settings=extra)
    out = os.path.join(ROOT, "android", "app", "src", "main", "assets", "looks", f"{pid}.xml")
    open(out, "w", encoding="utf-8").write(xml)
    return out, os.path.getsize(out)


if __name__ == "__main__":
    for pid in sys.argv[1:] or list(PRESETS):
        if os.path.exists(os.path.join(ROOT, "looks", f"{pid}-model.npz")):
            print(pid, *build(pid))
