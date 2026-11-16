"""Schat de correctie C tussen Lightroom's standaardweergave en wat het Warm flash-model als bron verwacht, uit een geregistreerd paar
(schermafdruk van Lightroom, JPG van dezelfde scène). Model: per kanaal een monotone curve (9 knopen) en daarna een 3x3-matrix, in
Display P3-gecodeerde waarden (dat is het domein van de tabel). Doel: model(C(LR)) * vignet(r) gelijk aan het eindbeeld van de camera.
"""
import io
import os
import sys

import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage as ndi
from scipy.optimize import least_squares

sys.path.insert(0, os.path.dirname(__file__))
import warmflash_fit as WFIT
from warmflash_model import enc, lin, gain_at, radius
import lr_basis as B

NK = 9
KNOTS = np.linspace(0, 1, NK)
SRGB_TO_XYZ = np.array([[0.4124564, 0.3575761, 0.1804375], [0.2126729, 0.7151522, 0.0721750], [0.0193339, 0.1191920, 0.9503041]])
P3_TO_XYZ = np.array([[0.4865709, 0.2656677, 0.1982173], [0.2289746, 0.6917385, 0.0792869], [0.0000000, 0.0451134, 1.0439444]])
SRGB_TO_P3 = np.linalg.inv(P3_TO_XYZ) @ SRGB_TO_XYZ


def srgb_to_p3_code(a):
    """a: (...,3) sRGB-codes 0..1 -> Display P3-codes 0..1 (zelfde kleur, andere ruimte)."""
    return enc(np.clip(lin(a) @ SRGB_TO_P3.T, 0, 1))


def apply_c(params, x):
    d = params[:3 * NK].reshape(3, NK)
    m = params[3 * NK:].reshape(3, 3)
    y = np.empty_like(x)
    for c in range(3):
        v = np.maximum.accumulate(KNOTS + d[c])
        y[:, c] = np.interp(x[:, c], KNOTS, v)
    y = y @ (np.eye(3) + m).T
    return np.clip(y, 0, 1)


def identity_params():
    return np.zeros(3 * NK + 9)


def register(lr_im, ref_im, W=200):
    """Zoek schaal en verschuiving zodat ref over lr ligt; geeft ref (PIL, afmeting lr) terug. ref moet al de goede draaiing hebben."""
    def prep(im):
        h = int(im.height * W / im.width)
        return np.asarray(im.resize((W, h), Image.LANCZOS).filter(ImageFilter.GaussianBlur(1)), dtype=float)
    L = prep(lr_im); R = prep(ref_im.resize(lr_im.size, Image.LANCZOS))
    edges = lambda a: np.hypot(ndi.sobel(a.mean(2), 1), ndi.sobel(a.mean(2), 0))
    EL, ER = edges(L), edges(R)
    h, w = ER.shape
    best = None
    for s in np.linspace(0.92, 1.12, 21):
        Rs = ndi.zoom(ER, s, order=1)
        out = np.zeros_like(ER)
        if s >= 1:
            y0 = (Rs.shape[0] - h) // 2; x0 = (Rs.shape[1] - w) // 2; out = Rs[y0:y0 + h, x0:x0 + w]
        else:
            y0 = (h - Rs.shape[0]) // 2; x0 = (w - Rs.shape[1]) // 2; out[y0:y0 + Rs.shape[0], x0:x0 + Rs.shape[1]] = Rs
        a = (EL - EL.mean()) / EL.std(); b = (out - out.mean()) / (out.std() + 1e-9)
        c = np.fft.ifft2(np.fft.fft2(a) * np.conj(np.fft.fft2(b))).real / a.size
        iy, ix = np.unravel_index(np.argmax(c), c.shape)
        dy = iy if iy < h // 2 else iy - h; dx = ix if ix < w // 2 else ix - w
        if best is None or c.max() > best[0]:
            best = (c.max(), s, dy, dx)
    corr, s, dy, dx = best
    # toepassen op volle resolutie van lr_im
    fy = lr_im.height / h; fx = lr_im.width / w
    arr = np.asarray(ref_im.resize(lr_im.size, Image.LANCZOS), dtype=float)
    zoomed = ndi.zoom(arr, (s, s, 1), order=1)
    H, Wd = arr.shape[:2]
    out = np.zeros_like(arr)
    if s >= 1:
        y0 = (zoomed.shape[0] - H) // 2; x0 = (zoomed.shape[1] - Wd) // 2; out = zoomed[y0:y0 + H, x0:x0 + Wd]
    else:
        y0 = (H - zoomed.shape[0]) // 2; x0 = (Wd - zoomed.shape[1]) // 2; out[y0:y0 + zoomed.shape[0], x0:x0 + zoomed.shape[1]] = zoomed
    out = ndi.shift(out, (dy * fy, dx * fx, 0), order=1, mode="nearest")
    return out, best


def sample_pairs(lr_im, reg, n=12000, seed=0, blur=3.0):
    """Pixelparen (LR in P3-code, doel in P3-code, straal) uit de geregistreerde beelden; flink uitgesmeerd tegen registratiefouten."""
    a = ndi.gaussian_filter(np.asarray(lr_im, dtype=float) / 255, (blur, blur, 0))
    b = ndi.gaussian_filter(reg / 255, (blur, blur, 0))
    h, w = a.shape[:2]
    r = radius(h, w).reshape(h, w)
    m = 12  # rand weg: de registratie loopt daar leeg
    ys, xs = np.mgrid[m:h - m, m:w - m]
    ys = ys.ravel(); xs = xs.ravel()
    rng = np.random.default_rng(seed)
    i = rng.choice(len(ys), n, replace=False)
    x = srgb_to_p3_code(a[ys[i], xs[i]])
    return x, b[ys[i], xs[i]], r[ys[i], xs[i]]


def predict(params, x, r, lut, vtable):
    y = apply_c(params, x)
    p = WFIT.apply_lut(lut, y)
    return enc(lin(p) * gain_at(vtable, r)[:, None])


def fit_c(x, t, r, lut, vtable, lam=0.02):
    def resid(p):
        e = predict(p, x, r, lut, vtable) - t
        return np.concatenate([e.ravel(), lam * p])
    p0 = identity_params()
    res = least_squares(resid, p0, method="trf", max_nfev=60, diff_step=1e-3)
    return res.x


def de(a, b):
    return float(WFIT.delta_e(np.clip(a, 0, 1), np.clip(b, 0, 1)).mean())
