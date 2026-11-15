"""Fase 0: Ultra HDR -> SDR prototype. Per foto: reconstructie, tone map (3 sterktes), Apple-referentie,
vergelijkingsbeelden, metadata-diff en bestandsgroottes.

Gebruik: .venv/bin/python prototype/fase0.py <uitmap>  (leest INPUTS hieronder)
"""
import json
import os
import shutil
import subprocess
import sys
import time

import numpy as np
import pyjpegli
from PIL import Image, ImageDraw, ImageFont, ImageOps

from tonemap import PRESETS, luma, peak_from_hist, srgb_oetf_u8, tonemap
from uhdr import UltraHdr

HERE = os.path.dirname(os.path.abspath(__file__))
REF = os.path.join(HERE, "ref_apple")
ROWS = 256
Q_OUT = 90  # jpegli-kwaliteit voor het eindbestand (ongeveer butteraugli-afstand 1.0)

ORIENT = {2: Image.FLIP_LEFT_RIGHT, 3: Image.ROTATE_180, 4: Image.FLIP_TOP_BOTTOM, 5: Image.TRANSPOSE,
          6: Image.ROTATE_270, 7: Image.TRANSVERSE, 8: Image.ROTATE_90}


def exif(path, *tags):
    out = subprocess.run(["exiftool", "-j", "-n", *tags, path], capture_output=True, text=True).stdout
    return json.loads(out)[0]


def encode(arr, q, icc_from=None, meta_from=None, orientation_only=False):
    data = pyjpegli.encode(np.ascontiguousarray(arr).tobytes(), arr.shape[1], arr.shape[0], quality=q)
    return data


def copy_meta(src, dst, orientation_only=False):
    """Metadata van het origineel naar het resultaat. Zonder MPF en hdrgm-XMP, zonder oude thumbnail."""
    if orientation_only:
        cmd = ["exiftool", "-q", "-overwrite_original", "-TagsFromFile", src, "-Orientation", dst]
    else:
        cmd = ["exiftool", "-q", "-overwrite_original", "-TagsFromFile", src, "-all:all", "-ICC_Profile",
               "-XMP-hdrgm:all=", "-XMP-GContainer:all=", "-IFD1:all=", dst]
    subprocess.run(cmd, check=True)


def luma_stats(arr_u8):
    y = (arr_u8[::4, ::4].astype(np.float32) @ np.array([0.2290, 0.6917, 0.0793], np.float32)) / 255.0
    p = np.percentile(y, [5, 50, 95])
    return {"p5": round(float(p[0]), 3), "p50": round(float(p[1]), 3), "p95": round(float(p[2]), 3),
            "gem": round(float(y.mean()), 3), "std": round(float(y.std()), 3)}


def panel(img, orient, long_side, label, font):
    im = img.transpose(ORIENT[orient]) if orient in ORIENT else img
    s = long_side / max(im.size)
    im = im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)
    out = Image.new("RGB", (im.width, im.height + 56), (24, 24, 24))
    out.paste(im, (0, 56))
    ImageDraw.Draw(out).text((12, 10), label, fill=(255, 255, 255), font=font)
    return out


def strip_row(panels, gap=12):
    h = max(p.height for p in panels)
    out = Image.new("RGB", (sum(p.width for p in panels) + gap * (len(panels) - 1), h), (24, 24, 24))
    x = 0
    for p in panels:
        out.paste(p, (x, 0))
        x += p.width + gap
    return out


def process(name, path, outdir, font):
    t0 = time.time()
    d = os.path.join(outdir, name)
    os.makedirs(d, exist_ok=True)
    u = UltraHdr(path)
    assert u.has_gainmap, "geen gain map gevonden"
    ex = exif(path, "-Orientation")
    orient = int(ex.get("Orientation", 1))
    res = {"naam": name, "afmeting": f"{u.width}x{u.height}", "mp": round(u.width * u.height / 1e6, 1),
           "gainmap": f"{u.gm.size[0]}x{u.gm.size[1]}", "gain_max_log2": float(u.meta["max"].max()),
           "orientation": orient}

    # Pass 1: piek van de inhoud (histogram op elke 4e pixel)
    edges = np.concatenate([[0], np.geomspace(1e-4, 8, 2000)])
    counts = np.zeros(len(edges) - 1)
    for _y0, _y1, _sdr, hdr in u.strips(ROWS):
        counts += np.histogram(luma(hdr[::4, ::4]), bins=edges)[0]
    peak = peak_from_hist(counts, edges)
    res["piek"] = round(peak, 3)

    # Pass 2: tone map per tegel (rijstroken), alle sterktes tegelijk
    outs = {k: np.empty((u.height, u.width, 3), np.uint8) for k in PRESETS}
    for y0, y1, _sdr, hdr in u.strips(ROWS):
        for k, (ex_k, knee, mix) in PRESETS.items():
            outs[k][y0:y1] = srgb_oetf_u8(tonemap(hdr, peak, ex_k, knee, mix))
    base = u.base_array()
    res["stats"] = {"basis": luma_stats(base), **{k: luma_stats(v) for k, v in outs.items()}}
    np.save(os.path.join(os.environ["SCRATCH"], name + "_normaal.npy"), outs["normaal"])

    # Bestanden per foto
    shutil.copyfile(path, os.path.join(d, "1-origineel-proxdr.jpg"))
    open(os.path.join(d, "2-basis.jpg"), "wb").write(u.primary_bytes)
    f3 = os.path.join(d, "3-tonemap.jpg")
    open(f3, "wb").write(encode(outs["normaal"], Q_OUT))
    copy_meta(path, f3)
    f4 = os.path.join(d, "4-referentie-apple.jpg")
    subprocess.run([REF, "tonemap", path, f4], check=True, capture_output=True)
    copy_meta(path, f4, orientation_only=True)
    apple = np.asarray(Image.open(f4).convert("RGB"))
    res["stats"]["apple"] = luma_stats(apple)
    # Zelfde Apple-curve, maar met de echte piek van de inhoud in plaats van de headroom uit de metadata
    f5 = os.path.join(d, "4b-referentie-apple-inhoudspiek.jpg")
    subprocess.run([REF, "tonemap", path, f5, str(peak)], check=True, capture_output=True)
    copy_meta(path, f5, orientation_only=True)
    apple2 = np.asarray(Image.open(f5).convert("RGB"))
    res["stats"]["apple_piek"] = luma_stats(apple2)

    # Metadata-diff (origineel tegen resultaat); volledige test is fase 2
    def dump(p):
        r = subprocess.run(["exiftool", "-a", "-G1", "-s", "-u", "-e", p], capture_output=True, text=True).stdout
        return set(l.strip() for l in r.splitlines() if not l.startswith(("[File]", "[System]", "[ExifTool]")))
    a, b = dump(path), dump(f3)
    with open(os.path.join(d, "metadata-diff.txt"), "w") as fh:
        fh.write("Alleen in origineel:\n" + "\n".join(sorted(a - b)) + "\n\nAlleen in resultaat:\n" + "\n".join(sorted(b - a)) + "\n")
    res["groottes"] = {"origineel": os.path.getsize(path), "basis_alleen": len(u.primary_bytes),
                       "gainmap": os.path.getsize(path) - len(u.primary_bytes), "tonemap_q90": os.path.getsize(f3),
                       "apple": os.path.getsize(f4), "apple_piek": os.path.getsize(f5)}

    # Vergelijkingsbeelden (Display P3-profiel mee, zodat kleuren op het Mac-scherm kloppen)
    L = 1100
    p2 = Image.fromarray(base)
    p3 = Image.fromarray(outs["normaal"])
    p4 = Image.fromarray(apple)
    row = strip_row([panel(p2, orient, L, "2. Alleen basisbeeld (gain map weg)", font),
                     panel(p3, orient, L, "3. Mijn tone map (normaal: k=0.9, knie=0.45)", font),
                     panel(p4, orient, L, "4. Apple tone map (headroom uit metadata)", font),
                     panel(Image.fromarray(apple2), orient, L, "4b. Apple tone map (echte piek)", font)])
    row.save(os.path.join(d, "vergelijking.png"), icc_profile=u.icc)
    var = strip_row([panel(Image.fromarray(outs[k]), orient, L, f"Tone map: {k} (k={PRESETS[k][0]}, knie={PRESETS[k][1]})", font)
                     for k in PRESETS])
    var.save(os.path.join(d, "varianten.png"), icc_profile=u.icc)
    res["tijd_s"] = round(time.time() - t0, 1)
    json.dump(res, open(os.path.join(d, "resultaat.json"), "w"), indent=1)
    print(json.dumps(res), flush=True)
    return res


if __name__ == "__main__":
    outdir = sys.argv[1]
    os.makedirs(outdir, exist_ok=True)
    font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 30)
    T = "/Users/mark/Documents/Android/HDR-testfotos-2026-11-14"
    S = sys.argv[2]  # map met uit de DNG's gehaalde camera-JPEG's
    inputs = {
        "jpg-120713": f"{T}/IMG20261114120713.jpg",
        "jpg-120816": f"{T}/IMG20261114120816.jpg",
        "dng-120548": f"{S}/IMG20261114120548.prev.jpg",
        "dng-120831": f"{S}/IMG20261114120831.prev.jpg",
        "dng-uw-50mp": f"{S}/IMG20261113122553.prev.jpg",
    }
    only = sys.argv[3:] or list(inputs)
    for n in only:
        process(n, inputs[n], outdir, font)
