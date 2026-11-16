"""Zet een gefitte Look in een DNG, open hem in Lightroom, en meet hoe ver het scherm van het cameravoorbeeld af ligt (dE76).
Gebruik: .venv/bin/python prototype/lr_eval.py MODEL.npz PAREN_MAP UIT_MAP DNG [DNG ...]   (omgeving LOOK_VIG='amount,mid' voor het vignet)"""
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
import adobe_look as A
import lr_basis as B
import lr_probe as P
import warmflash_fit as WF
from lr_fit_basis import srgb_to_p3_code


def table_from(lut, n=32):
    g = np.linspace(0, 1, n)
    grid = np.stack(np.meshgrid(g, g, g, indexing="ij"), -1).reshape(-1, 3)
    return np.clip(WF.apply_lut(lut, grid), 0, 1).reshape(n, n, n, 3)


def vignette_settings(amount, mid, feather=100, roundness=100, style=3):
    return (f'crs:PostCropVignetteAmount="{amount}" crs:PostCropVignetteMidpoint="{mid}" crs:PostCropVignetteFeather="{feather}" '
            f'crs:PostCropVignetteRoundness="{roundness}" crs:PostCropVignetteStyle="{style}"')


def evaluate(model, pairs_dir, outdir, dngs, name="Eval", uuid="5AB1E000000000000000000000000EE1", vig=None, n=32):
    z = np.load(model)
    nodes = table_from(z["lut"], n)
    extra = vignette_settings(*vig) if vig else ""
    desc = A.dng_look_description(name, nodes, uuid, extra_settings=extra)
    res = []
    for dng in dngs:
        stem = os.path.splitext(os.path.basename(dng))[0]
        k = "e" + stem[-6:]
        png = P.probe(k, desc, dng, outdir, wait=12)
        P.opruimen([k])
        lr = B.lr_content(png)
        ref = np.load(os.path.join(pairs_dir, stem + ".npz"))["ref"]
        if lr.size != (ref.shape[1], ref.shape[0]):
            lr = lr.resize((ref.shape[1], ref.shape[0]))
        a = np.asarray(lr, dtype=float) / 255
        pa = srgb_to_p3_code(a.reshape(-1, 3))
        pr = ref.reshape(-1, 3) / 255
        sub = slice(None, None, 7)
        e = float(WF.delta_e(np.clip(pa[sub], 0, 1), pr[sub]).mean())
        res.append((stem[-6:], e)); print(stem[-6:], round(e, 2), flush=True)
    return res


if __name__ == "__main__":
    vig = tuple(int(v) for v in os.environ["LOOK_VIG"].split(",")) if os.environ.get("LOOK_VIG") else None
    evaluate(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:], vig=vig)
