"""Fit een Lightroom-Look (3D-tabel + vignet) per preset uit paren (Lightroom-weergave zonder Look, voorbeeld uit de DNG met filter).

Invoer: de npz-bestanden van lr_collect.py. Domein: Display P3-codes (de tabel wordt in P3 met sRGB-curve opgeslagen).
Uitvoer: tabel (n,n,n,3) en vignet-tabel V(r) (relatief aan het midden, dus <= ~1).
Gebruik: .venv/bin/python prototype/lr_fit_look.py PAREN_MAP UIT.npz [naam_van_toetsbestand ...]
"""
import glob
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
import warmflash_fit as W
import warmflash_model as M
from lr_fit_basis import srgb_to_p3_code


def load_pairs(path, per=60000, seed=0, margin=0.02):
    z = np.load(path)
    lr = z["lr"].astype(float) / 255
    ref = z["ref"].astype(float) / 255
    h, w = lr.shape[:2]
    r = M.radius(h, w)
    ys, xs = np.mgrid[int(h * margin):h - int(h * margin), int(w * margin):w - int(w * margin)]
    idx = (ys * w + xs).ravel()
    i = np.random.default_rng(seed).choice(len(idx), min(per, len(idx)), replace=False)
    flat = lr.reshape(-1, 3)[idx[i]]
    return srgb_to_p3_code(flat), ref.reshape(-1, 3)[idx[i]], r[idx[i]]


def stack(items):
    return tuple(np.concatenate([it[k] for it in items]) for k in range(3))


def refit_lut(x, y, r, vprime, lam=5.0):
    g = M.gain_at(vprime, r)[:, None]
    return W.fit_lut(x, np.clip(M.enc(M.lin(y) / g), 0, 1), lam=lam)


def predict(lut, vprime, x, r):
    return M.enc(M.lin(W.apply_lut(lut, x)) * M.gain_at(vprime, r)[:, None])


def de(a, b):
    return float(W.delta_e(np.clip(a, 0, 1), np.clip(b, 0, 1)).mean())


def fit_look(train, lam=5.0):
    x, y, r = stack(train)
    m = M.fit(x, y, r, "lin", rounds=5, lam=lam)
    ref = float(np.mean(m.v[:6]))
    vprime = m.v / ref
    lut = refit_lut(x, y, r, vprime, lam)
    return lut, vprime, ref


if __name__ == "__main__":
    files = sorted(glob.glob(os.path.join(sys.argv[1], "*.npz")))
    hold = set(sys.argv[3:])
    data = {os.path.basename(f)[:-4]: load_pairs(f) for f in files}
    test = [n for n in data if any(h in n for h in hold)]
    train = [n for n in data if n not in test]
    print("train:", [n[-6:] for n in train], " toets:", [n[-6:] for n in test])
    ident = W.identity_lut()
    flat = np.ones(M.NR)
    lut, vprime, ref = fit_look([data[n] for n in train])
    print(f"V(r)/V0 elke 5e knoop: {np.round(vprime[::5], 3)}  (V0 {ref:.3f})")
    print("dE76 per beeld (zonder Look | met Look), train dan toets:")
    for grp, names in (("train", train), ("toets", test)):
        a, b = [], []
        for n in names:
            x, y, r = data[n]
            a.append(de(x, y)); b.append(de(predict(lut, vprime, x, r), y))
            print(f"  {grp} {n[-6:]}  {a[-1]:5.2f} | {b[-1]:5.2f}")
        if names:
            print(f"  {grp} gemiddeld {np.mean(a):5.2f} | {np.mean(b):5.2f}")
    np.savez(sys.argv[2], lut=lut, v=vprime, ref=ref)
