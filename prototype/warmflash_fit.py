"""Past een kleurtransformatie (3D-LUT) op de Warm flash-paren (bron -> eindbeeld) en meet hoe goed die generaliseert.

Gebruik: .venv/bin/python prototype/warmflash_fit.py PAREN_MAP [UITMAP]
Fit op alle foto's behalve de laatste drie (die blijven apart voor de toets). Alles in gecodeerde waarden 0..1 (P3, sRGB-transfer).
"""
import glob
import os
import sys

import numpy as np
import scipy.sparse as sp

N = 17


def trilinear(x, n=N):
    """x: (m,3) in 0..1 -> (idx (m,8), w (m,8)) naar roosterpunten."""
    p = np.clip(x, 0, 1) * (n - 1)
    i0 = np.minimum(p.astype(int), n - 2)
    f = p - i0
    idx, wts = [], []
    for dr in (0, 1):
        for dg in (0, 1):
            for db in (0, 1):
                w = (f[:, 0] if dr else 1 - f[:, 0]) * (f[:, 1] if dg else 1 - f[:, 1]) * (f[:, 2] if db else 1 - f[:, 2])
                idx.append(((i0[:, 0] + dr) * n + (i0[:, 1] + dg)) * n + (i0[:, 2] + db))
                wts.append(w)
    return np.stack(idx, 1), np.stack(wts, 1)


def design(x, n=N):
    idx, w = trilinear(x, n)
    m = x.shape[0]
    return sp.csr_matrix((w.ravel(), (np.repeat(np.arange(m), 8), idx.ravel())), shape=(m, n ** 3))


def laplacian(n=N):
    rows = []
    ids = np.arange(n ** 3).reshape(n, n, n)
    mats = []
    for ax in range(3):
        a = np.moveaxis(ids, ax, 0)
        c, l, r = a[1:-1].ravel(), a[:-2].ravel(), a[2:].ravel()
        m = len(c)
        mats.append(sp.csr_matrix((np.concatenate([np.full(m, -2.0), np.ones(m), np.ones(m)]),
                                   (np.tile(np.arange(m), 3), np.concatenate([c, l, r]))), shape=(m, n ** 3)))
    return sp.vstack(mats)


def identity_lut(n=N):
    g = np.linspace(0, 1, n)
    r, gg, b = np.meshgrid(g, g, g, indexing="ij")
    return np.stack([r.ravel(), gg.ravel(), b.ravel()], 1)


def fit_lut(x, y, lam=0.5, mu=0.02):
    A = design(x)
    L = laplacian()
    ident = identity_lut()
    AtA = (A.T @ A).toarray() + lam * (L.T @ L).toarray() + mu * np.eye(N ** 3)
    # delta = LUT - identiteit; regulariseer delta (ook naar identiteit toe)
    rhs = A.T @ (y - A @ ident)
    delta = np.linalg.solve(AtA, rhs)
    return ident + delta


def apply_lut(lut, x):
    idx, w = trilinear(x)
    return np.einsum("mk,mkc->mc", w, lut[idx])


def to_lab(rgb):
    lin = np.where(rgb <= 0.04045, rgb / 12.92, ((rgb + 0.055) / 1.055) ** 2.4)
    m = np.array([[0.4866, 0.2657, 0.1982], [0.2290, 0.6917, 0.0793], [0.0, 0.0451, 1.0439]])  # Display P3 naar XYZ (D65)
    xyz = lin @ m.T / np.array([0.9505, 1.0, 1.089])
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    return np.stack([116 * f[:, 1] - 16, 500 * (f[:, 0] - f[:, 1]), 200 * (f[:, 1] - f[:, 2])], 1)


def delta_e(a, b):
    return np.sqrt(((to_lab(a) - to_lab(b)) ** 2).sum(1))


def sample(files, per=40000, seed=0):
    rng = np.random.default_rng(seed)
    xs, ys = [], []
    for f in files:
        z = np.load(f)
        s = z["src"].reshape(-1, 3) / 255.0
        t = z["fin"].reshape(-1, 3) / 255.0
        i = rng.choice(len(s), min(per, len(s)), replace=False)
        xs.append(s[i]); ys.append(t[i])
    return np.concatenate(xs), np.concatenate(ys)


def report(name, pred_fn, files):
    rms, de = [], []
    for f in files:
        z = np.load(f)
        s = z["src"].reshape(-1, 3)[::7] / 255.0
        t = z["fin"].reshape(-1, 3)[::7] / 255.0
        p = np.clip(pred_fn(s), 0, 1)
        rms.append(np.sqrt(((p - t) ** 2).mean())); de.append(delta_e(p, t).mean())
    print(f"{name:34s} rms {np.mean(rms):.4f}  dE76 {np.mean(de):.2f}   (per foto dE: {' '.join(f'{v:.1f}' for v in de)})")


if __name__ == "__main__":
    files = sorted(glob.glob(os.path.join(sys.argv[1], "*.npz")))
    train, test = files[:-3], files[-3:]
    x, y = sample(train)
    print(f"{len(train)} trainfoto's, {len(test)} toetsfoto's, {len(x)} samples")
    report("niets doen (toets)", lambda s: s, test)
    # alleen 3x3 matrix + offset
    X1 = np.c_[x, np.ones(len(x))]
    M = np.linalg.lstsq(X1, y, rcond=None)[0]
    report("matrix 3x3+offset (toets)", lambda s: np.c_[s, np.ones(len(s))] @ M, test)
    # per kanaal een curve (256 punten, gladgestreken met 17 knopen) via 1D-LUT
    curves = []
    for c in range(3):
        bins = np.clip((x[:, c] * 16).round().astype(int), 0, 16)
        curves.append(np.array([y[bins == k, c].mean() if (bins == k).any() else k / 16 for k in range(17)]))
    report("per-kanaal curve (toets)", lambda s: np.stack([np.interp(s[:, c], np.linspace(0, 1, 17), curves[c]) for c in range(3)], 1), test)
    for lam in (0.05, 0.5, 5.0):
        lut = fit_lut(x, y, lam=lam)
        report(f"3D-LUT 17 lam={lam} (toets)", lambda s: apply_lut(lut, s), test)
        report(f"3D-LUT 17 lam={lam} (train)", lambda s: apply_lut(lut, s), train[:3])
    if len(sys.argv) > 2:
        os.makedirs(sys.argv[2], exist_ok=True)
        np.save(os.path.join(sys.argv[2], "lut17.npy"), fit_lut(*sample(files), lam=0.5))
