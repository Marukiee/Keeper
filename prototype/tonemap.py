"""Globale tone map van lineaire HDR naar SDR, per pixel dus geschikt voor tegels.

Luminantie krijgt een curve met een zachte schouder boven de knie; de kleurverhouding blijft
behouden, en wat daarna nog boven 1.0 uitkomt wordt naar grijs gedesatureerd (geen tintverschuiving).
"""
import numpy as np

# Luminantiecoefficienten voor Display P3 (lineair)
LUMA = np.array([0.2289746, 0.6917385, 0.0792869], dtype=np.float32)

# naam: (belichting k, knie, mix). Een lagere knie geeft de lichten meer ruimte (wolken houden tekening).
# Op de tuinfoto staat 1,7% van de pixels op wit bij 'normaal' en 1,1% bij 'lichten-behouden' (basis: 0,1%).
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


# Anker: een lichte pixel die de camera niet heeft opgetild (papierwit, de witte rand van een Hasselblad-omlijsting) blijft zoals de
# camera-SDR hem gaf, anders zet de schouder hem onder wit (220 in plaats van 255). Zie ToneMapper.anchor in de app.
ANCHOR_R0, ANCHOR_R1 = 1.05, 1.30  # opticht van de luminantie: volledig onder R0, weg vanaf R1
ANCHOR_Y0, ANCHOR_Y1 = 0.60, 0.95  # alleen lichte pixels (SDR-luminantie), geleidelijk


def _smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def anchor_weight(hdr, sdr):
    ys = luma(sdr)
    ratio = luma(hdr) / np.maximum(ys, 1e-6)
    return (1.0 - _smooth(ANCHOR_R0, ANCHOR_R1, ratio)) * _smooth(ANCHOR_Y0, ANCHOR_Y1, ys)


def tonemap_anchored(hdr, sdr, peak, k=0.95, knee=0.70, mix=1.0):
    """Als [tonemap], met de SDR van de camera als anker voor lichte pixels zonder opticht."""
    a = anchor_weight(hdr, sdr)[..., None]
    return tonemap(hdr, peak, k, knee, mix) * (1.0 - a) + sdr * a


# ---- lokaal contrast in de lichten (2026-11-17) ----
# De curve drukt de lichten samen, dus ze verliezen ten opzichte van de camera-SDR zo'n 38% lokaal contrast (plat). Daarom krijgt de HDR vóór de
# curve een lokaal contrast terug: Y' = Y * (Y / M)^(alfa * w * g), met M het wazige gemiddelde van de omgeving (gauss, sigma = 0,5% van de
# lange kant, gemeten op het steekproefraster van de eerste pass), w = 0 onder M 0,5 en 1 boven 1,2 (alleen lichte omgeving) en g = 0 onder een
# opticht van 10% en 1 boven 30% (alleen waar de gain map oplicht: witte omlijsting en tekst hebben geen gain en blijven dus zoals ze zijn).
LC_ALPHA = 0.6
LC_SIGMA_FRAC = 0.005
LC_M0, LC_M1 = 0.5, 1.2
LC_R0, LC_R1 = 1.10, 1.30
LC_SMAX = 1.8
LC_GRID_STEP = 4  # het raster is elke vierde pixel van elke vierde rij van het verkleinde beeld
KNEE_LIFT = 0.25  # de knie van de uitvoer ligt dit deel van de resterende ruimte hoger dan waarmee de belichting wordt gematcht: lichten helderder, schaduwen en mids ongemoeid


def lifted_knee(knee):
    return knee + KNEE_LIFT * (1.0 - knee)


def lc_factor(yh, ys, m):
    """Vermenigvuldiger voor de HDR-pixel met luminantie [yh], camera-SDR [ys] en omgevingsgemiddelde [m] (allemaal lineair)."""
    ratio = yh / np.maximum(ys, 1e-4)
    g = _smooth(LC_R0, LC_R1, ratio)
    w = _smooth(LC_M0, LC_M1, m)
    e = LC_ALPHA * w * g
    return np.clip(np.power(np.maximum(yh, 1e-4) / np.maximum(m, 1e-4), e), 1.0 / LC_SMAX, LC_SMAX)


def lc_mean_grid(luma_grid, sigma_cells):
    """Wazig gemiddelde van het raster: gauss, straal int(3 sigma + 0,5), randen herhaald (scipy mode 'nearest')."""
    from scipy.ndimage import gaussian_filter
    return gaussian_filter(luma_grid.astype(np.float64), sigma_cells, mode="nearest", truncate=3.0).astype(np.float32)


def lc_grid_coord(x, sample):
    """Rasterpositie (in cellen) van het midden van volle-resolutiepixel x; raster is elke vierde pixel van het beeld verkleind met [sample]."""
    return ((x + 0.5) / sample - 0.5) / LC_GRID_STEP


def lc_lookup(mean_grid, x, y, sample):
    """Bilineair omgevingsgemiddelde voor volle-resolutiepixels x (rij-vector) en y (kolom-vector)."""
    gh, gw = mean_grid.shape
    gx = np.clip(lc_grid_coord(np.asarray(x, np.float64), sample), 0, gw - 1)
    gy = np.clip(lc_grid_coord(np.asarray(y, np.float64), sample), 0, gh - 1)
    x0 = np.floor(gx).astype(int); x1 = np.minimum(x0 + 1, gw - 1); fx = gx - x0
    y0 = np.floor(gy).astype(int); y1 = np.minimum(y0 + 1, gh - 1); fy = gy - y0
    top = mean_grid[y0][:, x0] * (1 - fx) + mean_grid[y0][:, x1] * fx
    bot = mean_grid[y1][:, x0] * (1 - fx) + mean_grid[y1][:, x1] * fx
    return top * (1 - fy)[:, None] + bot * fy[:, None]


# ---- lokale tone map in de lichten (2026-11-21) ----
# Vervangt het lokale contrast hierboven (lc_*), dat de lichten achteraf nog door dezelfde curve perste: de curve heeft bij de piek een helling van
# ~0,07, dus brede lichte vlakken (lucht, zon) verloren hun tekening en liepen dicht naar wit; de camera-SDR houdt die tekening wel.
# Nu krijgt elke pixel een eigen gain: Y_uit = c(M) * (Y / M)^1, met M het randbewuste gemiddelde van de omgeving (zelfsturend filter op ln Y,
# steekproefraster van de eerste pass) en c dezelfde schouder als de globale curve, maar met de piek op [top] < 1 in plaats van op wit: zo is er boven
# de lichten ruimte voor het detail. De globale curve (met dezelfde top) blijft gelden waar de gain map niet oplicht (omlijsting, tekst) of de omgeving
# donker is; het gewicht daarvoor is wl = smooth(M0, M1, M) * smooth(R0, R1, Y / Y_sdr). Een zachte klem (tanh) boven [LT_SOFT] laat het detail
# in de lichten niet hard afknippen.
LT_SIGMA_FRAC = 0.01
LT_EPS = 0.1          # zelfsturend filter op ln Y: variantie waarboven een rand als rand telt (kleiner = scherper, minder halo, minder detail)
LT_RAD = 2.0          # straal van het filter in sigma's
LT_DCAP = 1.8         # grens voor Y / M (detail naar boven en, als 1 / DCAP, naar beneden)
LT_CHCAP = 4.0        # idem voor de per-kanaalcurve
LT_M0, LT_M1 = 0.5, 1.2
LT_R0, LT_R1 = 1.10, 1.30
LT_HEADROOM = 0.10    # de piek komt op 1 - HEADROOM uit bij een piek van 2 of meer (bij piek 1 op wit, dus sterkte 0 blijft de identiteit)
LT_SOFT = 0.85


def lt_top(peak):
    return 1.0 - LT_HEADROOM * _smooth(1.0, 2.0, peak)


def lt_box_size(sigma_cells):
    return 2 * int(LT_RAD * sigma_cells) + 1


def lt_mean_grid(luma_grid, sigma_cells):
    """Randbewust gemiddelde (He et al., zelfsturend filter) van ln Y op het raster; vensters van `lt_box_size`, randen herhaald."""
    from scipy.ndimage import uniform_filter
    L = np.log(np.maximum(luma_grid.astype(np.float64), 1e-4))
    s = lt_box_size(sigma_cells)
    m = uniform_filter(L, s, mode="nearest")
    v = uniform_filter(L * L, s, mode="nearest") - m * m
    a = v / (v + LT_EPS)
    b = m - a * m
    q = uniform_filter(a, s, mode="nearest") * L + uniform_filter(b, s, mode="nearest")
    return np.exp(q).astype(np.float32)


def lt_curve(v, e, knee, peak, top):
    """Als `_curve`, maar de piek komt uit op [top] en daarboven loopt de curve door (tot hooguit wit)."""
    vs = v * e
    w = max((peak * e - knee) / (1.0 - knee), 1e-3)
    x = np.maximum(vs - knee, 0.0) / (1.0 - knee)
    g = x * (1.0 + x / (w * w)) / (1.0 + x)
    return np.minimum(np.where(vs <= knee, vs, knee + (top - knee) * g), 1.0)


def _lt_soft(x, top):
    s = LT_SOFT
    return np.where(x > s, s + (1.0 - s) * np.tanh((x - s) / (1.0 - s)), x)


def lt_map(hdr, sdr, m, e, knee, peak):
    """hdr, sdr: (...,3) lineair; m: omgevingsgemiddelde per pixel. Geeft lineaire SDR in [0,1], met het anker voor pixels zonder gain."""
    top = lt_top(peak)
    yh, ys = luma(hdr), luma(sdr)
    m = np.maximum(m, 1e-4)
    wl = _smooth(LT_M0, LT_M1, m) * _smooth(LT_R0, LT_R1, yh / np.maximum(ys, 1e-4))
    cm = lt_curve(m, e, knee, peak, top)
    yo = (1.0 - wl) * lt_curve(yh, e, knee, peak, top) + wl * _lt_soft(cm * np.clip(yh / m, 1.0 / LT_DCAP, LT_DCAP), top)
    scale = np.where(yh > 1e-6, yo / np.maximum(yh, 1e-6), e)
    out = hdr * scale[..., None]
    mx = out.max(axis=-1)
    over = mx > 1.0
    if over.any():
        yc = np.minimum(yo, 1.0)
        t = np.where(over, (1.0 - yc) / np.maximum(mx - yc, 1e-6), 1.0)
        out = yc[..., None] + (out - yc[..., None]) * t[..., None]
    w3 = wl[..., None]
    ch = (1.0 - w3) * lt_curve(hdr, e, knee, peak, top) + w3 * _lt_soft(cm[..., None] * np.minimum(hdr / m[..., None], LT_CHCAP), top)
    out = 0.5 * out + 0.5 * np.minimum(ch, 1.0)
    a = anchor_weight(hdr, sdr)[..., None]
    return np.clip(out * (1.0 - a) + sdr * a, 0.0, 1.0)
