"""Warm flash als model: 3D-LUT (kleur) plus radiale lichtafval (vignet), afwisselend gefit op de paren.

eind = decode(V(r) * lineair(LUT(bron)))  met V(r) een tabel over de straal (0 in het midden, 1 in de hoek).
Gebruik: .venv/bin/python prototype/warmflash_model.py PAREN_MAP [UITMAP]
"""
import glob
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
import warmflash_fit as W

NR = 48  # knopen van de radiale tabel


def lin(x):
    return np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)


def enc(l):
    l = np.clip(l, 0, 1)
    return np.where(l <= 0.0031308, l * 12.92, 1.055 * l ** (1 / 2.4) - 0.055)


def radius(h, w):
    yy, xx = np.mgrid[0:h, 0:w]
    return (np.hypot(xx - (w - 1) / 2, yy - (h - 1) / 2) / np.hypot((w - 1) / 2, (h - 1) / 2)).ravel()


def gain_at(table, r):
    return np.interp(r, np.linspace(0, 1, len(table)), table)


class Model:
    def __init__(self, lut, vtable, space="lin"):
        self.lut, self.v, self.space = lut, vtable, space

    def predict(self, s, r):
        p = W.apply_lut(self.lut, s)
        g = gain_at(self.v, r)[:, None]
        if self.space == "lin":
            return enc(lin(p) * g)
        return np.clip(p * g, 0, 1)


def load(files, per=50000, seed=0):
    rng = np.random.default_rng(seed)
    xs, ys, rs = [], [], []
    for f in files:
        z = np.load(f)
        h, w = z["src"].shape[:2]
        s = z["src"].reshape(-1, 3) / 255.0
        t = z["fin"].reshape(-1, 3) / 255.0
        r = radius(h, w)
        i = rng.choice(len(s), min(per, len(s)), replace=False)
        xs.append(s[i]); ys.append(t[i]); rs.append(r[i])
    return np.concatenate(xs), np.concatenate(ys), np.concatenate(rs)


def fit(x, y, r, space="lin", rounds=5, lam=5.0):
    v = np.ones(NR)
    lut = W.identity_lut()
    knots = np.linspace(0, 1, NR)
    for _ in range(rounds):
        g = gain_at(v, r)[:, None]
        target = enc(lin(y) / g) if space == "lin" else np.clip(y / g, 0, 1.5)
        lut = W.fit_lut(x, np.clip(target, 0, 1), lam=lam)
        p = W.apply_lut(lut, x)
        pl = lin(p) if space == "lin" else p
        yl = lin(y) if space == "lin" else y
        num = np.zeros(NR); den = np.zeros(NR)
        wgt = np.clip(1 - np.abs(r[:, None] - knots[None, :]) * (NR - 1), 0, 1)  # hoedjesfuncties
        num = (wgt * (yl * pl).sum(1)[:, None]).sum(0); den = (wgt * (pl * pl).sum(1)[:, None]).sum(0)
        v = num / np.maximum(den, 1e-9)
        v = np.convolve(np.pad(v, 2, mode="edge"), np.ones(5) / 5, mode="valid")
    return Model(lut, v, space)


def evaluate(model, files, name):
    des, rms = [], []
    for f in files:
        z = np.load(f)
        h, w = z["src"].shape[:2]
        s = z["src"].reshape(-1, 3)[::5] / 255.0
        t = z["fin"].reshape(-1, 3)[::5] / 255.0
        r = radius(h, w)[::5]
        p = model.predict(s, r)
        des.append(W.delta_e(p, t).mean()); rms.append(np.sqrt(((p - t) ** 2).mean()))
    print(f"{name:34s} rms {np.mean(rms):.4f}  dE76 {np.mean(des):.2f}  (per foto: {' '.join(f'{d:.1f}' for d in des)})")
    return float(np.mean(des))


if __name__ == "__main__":
    files = sorted(glob.glob(os.path.join(sys.argv[1], "*.npz")))
    train, test = files[:-3], files[-3:]
    x, y, r = load(train)
    for space in ("lin", "enc"):
        m = fit(x, y, r, space)
        evaluate(m, test, f"LUT + vignet ({space}) toets")
        evaluate(m, train[:3], f"LUT + vignet ({space}) train")
        print("   V(r):", np.round(m.v[::6], 3))
    if len(sys.argv) > 2:
        os.makedirs(sys.argv[2], exist_ok=True)
        x, y, r = load(files)
        m = fit(x, y, r, "lin")
        np.savez(os.path.join(sys.argv[2], "warmflash-model.npz"), lut=m.lut, v=m.v)
        print("opgeslagen")
