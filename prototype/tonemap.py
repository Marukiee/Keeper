"""Globale tone map van lineaire HDR naar SDR, per pixel dus geschikt voor tegels.

Luminantie krijgt een curve met een zachte schouder boven de knie; de kleurverhouding blijft
behouden, en wat daarna nog boven 1.0 uitkomt wordt naar grijs gedesatureerd (geen tintverschuiving).
"""
import numpy as np

# Luminantiecoefficienten voor Display P3 (lineair)
LUMA = np.array([0.2289746, 0.6917385, 0.0792869], dtype=np.float32)

# naam: (belichting k, knie, mix). Een lagere knie geeft de lichten meer ruimte (wolken houden tekening).
# Op de tuinfoto: 'licht' brandt de lucht nog deels uit, 'lichten-behouden' niet.
PRESETS = {
    "licht": (1.00, 0.60, 0.5),
    "normaal": (0.90, 0.45, 0.5),
    "lichten-behouden": (0.80, 0.40, 0.5),
}


def luma(rgb):
    return rgb @ LUMA


def peak_from_hist(counts, edges, pct=99.95, lo=1.0, hi=4.93):
    """Piek van de inhoud uit een luminantiehistogram (eerste pass over alle tegels)."""
    c = np.cumsum(counts) / max(counts.sum(), 1)
    p = float(edges[min(np.searchsorted(c, pct / 100.0), len(edges) - 2) + 1])
    return float(np.clip(p, lo, hi))


def _curve(v, k, knee, peak):
    """Schouder: lineair tot de knie, daarboven een zachte rolloff die de piek precies op wit brengt."""
    vs = v * k
    w = max((peak * k - knee) / (1.0 - knee), 1e-3)
    x = np.maximum(vs - knee, 0.0) / (1.0 - knee)
    g = x * (1.0 + x / (w * w)) / (1.0 + x)  # g(w) = 1
    return np.minimum(np.where(vs <= knee, vs, knee + (1.0 - knee) * g), 1.0)


def tonemap(hdr, peak, k=0.95, knee=0.70, mix=1.0):
    """hdr: (...,3) float32 lineair. Geeft lineaire SDR in [0,1], zelfde gamut.

    mix=1: alleen luminantie krijgt de curve (kleurverhouding blijft, rest naar grijs).
    mix=0: elk kanaal apart (lichten desatureren vanzelf, blauwe lucht blijft langer blauw).
    """
    y = luma(hdr)
    yo = _curve(y, k, knee, peak)
    scale = np.where(y > 1e-6, yo / np.maximum(y, 1e-6), k)
    out = hdr * scale[..., None]
    m = out.max(axis=-1)
    over = m > 1.0
    if over.any():
        t = np.where(over, (1.0 - yo) / np.maximum(m - yo, 1e-6), 1.0)
        out = yo[..., None] + (out - yo[..., None]) * t[..., None]
    if mix < 1.0:
        out = mix * out + (1.0 - mix) * _curve(hdr, k, knee, peak)
    return np.clip(out, 0.0, 1.0)


def srgb_oetf_u8(lin):
    lin = np.clip(lin, 0.0, 1.0)
    v = np.where(lin <= 0.0031308, lin * 12.92, 1.055 * np.power(lin, 1 / 2.4) - 0.055)
    return (v * 255.0 + 0.5).astype(np.uint8)
