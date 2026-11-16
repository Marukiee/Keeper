"""Schrijft de standaardtabellen uit JPEG Annex K (Huffman en kwantisatie) als Kotlin, uit een gewoon bestand van libjpeg-turbo.

Zo staan er geen overgetypte getallen in de encoder. Gebruik: .venv/bin/python prototype/maak_jpegtabellen.py
"""
import io
import struct

import numpy as np
from PIL import Image

OUT = "android/app/src/main/java/nl/markmaaktmedia/keeper/convert/JpegTables.kt"
ZIGZAG = [0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
          35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63]

arr = (np.random.default_rng(1).random((32, 32, 3)) * 255).astype(np.uint8)
b = io.BytesIO()
Image.fromarray(arr).save(b, "JPEG", quality=50, optimize=False, subsampling=2)
data = b.getvalue()

dht, dqt = {}, {}
pos = 2
while pos < len(data):
    marker = data[pos + 1]
    length = struct.unpack(">H", data[pos + 2:pos + 4])[0]
    seg = data[pos + 4:pos + 2 + length]
    if marker == 0xC4:
        i = 0
        while i < len(seg):
            tc_th = seg[i]; counts = list(seg[i + 1:i + 17]); n = sum(counts)
            dht[tc_th] = (counts, list(seg[i + 17:i + 17 + n])); i += 17 + n
    elif marker == 0xDB:
        i = 0
        while i < len(seg):
            pq_tq = seg[i]; assert pq_tq >> 4 == 0
            dqt[pq_tq & 15] = list(seg[i + 1:i + 65]); i += 65
    elif marker == 0xDA:
        break
    pos += 2 + length

def natural(zz):  # tabel in zigzag-volgorde (zoals in het bestand) naar natuurlijke volgorde
    out = [0] * 64
    for k, p in enumerate(ZIGZAG):
        out[p] = zz[k]
    return out

def kt(name, values, per_line=16, hexa=False):
    f = (lambda v: f"0x{v:02x}") if hexa else str
    lines = [", ".join(f(v) for v in values[i:i + per_line]) for i in range(0, len(values), per_line)]
    return f"    val {name} = intArrayOf(\n        " + ",\n        ".join(lines) + ",\n    )\n"

src = ["package nl.markmaaktmedia.keeper.convert\n",
       "/** Standaardtabellen uit JPEG Annex K. Gegenereerd door `prototype/maak_jpegtabellen.py`, niet met de hand aanpassen. */",
       "object JpegTables {",
       "    /** Basiskwantisatietabellen bij kwaliteit 50, in natuurlijke (rij na rij) volgorde. */",
       kt("LUMA_Q50", natural(dqt[0])), kt("CHROMA_Q50", natural(dqt[1])),
       "    /** Zigzag-index naar natuurlijke positie. */", kt("ZIGZAG", ZIGZAG),
       "    // Huffman: aantal codes per lengte (1 tot 16) en de symbolen.",
       kt("DC_LUMA_COUNTS", dht[0x00][0]), kt("DC_LUMA_SYMBOLS", dht[0x00][1], hexa=True),
       kt("DC_CHROMA_COUNTS", dht[0x01][0]), kt("DC_CHROMA_SYMBOLS", dht[0x01][1], hexa=True),
       kt("AC_LUMA_COUNTS", dht[0x10][0]), kt("AC_LUMA_SYMBOLS", dht[0x10][1], hexa=True),
       kt("AC_CHROMA_COUNTS", dht[0x11][0]), kt("AC_CHROMA_SYMBOLS", dht[0x11][1], hexa=True),
       "}\n"]
open(OUT, "w").write("\n".join(src))
print({k: (sum(v[0]), len(v[1])) for k, v in dht.items()}, "q-luma[0:8]", natural(dqt[0])[:8])
