"""Maakt een klein Ultra HDR-testbestand (64x48 met gain map van 16x12) voor de unit tests in Kotlin.

Gain map met gamma 1,5, offsets 0,015625, per kanaal een ander maximum (als rdf:Seq) en MPF met twee beelden.
Gebruik: .venv/bin/python prototype/maak_testbestand.py
"""
import io
import struct
import sys

import numpy as np
from PIL import Image

OUT = "android/app/src/test/resources/ultrahdr-mini.jpg"


def jpeg(arr, q=95):
    b = io.BytesIO()
    Image.fromarray(arr).save(b, "JPEG", quality=q, subsampling=0)
    return b.getvalue()


def segment(marker, payload):
    return bytes([0xFF, marker]) + struct.pack(">H", len(payload) + 2) + payload


def xmp(body):
    packet = ('<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?><x:xmpmeta xmlns:x="adobe:ns:meta/">'
              '<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">' + body + '</rdf:RDF></x:xmpmeta><?xpacket end="w"?>')
    return segment(0xE1, b"http://ns.adobe.com/xap/1.0/\x00" + packet.encode("utf-8"))


H, W = 48, 64
yy, xx = np.mgrid[0:H, 0:W]
base = np.stack([(xx * 4) % 256, (yy * 5) % 256, ((xx + yy) * 2 + 20) % 256], -1).astype(np.uint8)
gh, gw = 12, 16
gy, gx = np.mgrid[0:gh, 0:gw]
gain = np.stack([(gx * 16) % 256, (gy * 20 + 10) % 256, ((gx + gy) * 9) % 256], -1).astype(np.uint8)

gain_jpeg = jpeg(gain)
desc = ('<rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0" hdrgm:GainMapMin="-0.1" '
        'hdrgm:Gamma="1.5" hdrgm:OffsetSDR="0.015625" hdrgm:OffsetHDR="0.015625" hdrgm:HDRCapacityMin="0" hdrgm:HDRCapacityMax="2.5" '
        'hdrgm:BaseRenditionIsHDR="False"><hdrgm:GainMapMax><rdf:Seq><rdf:li>1.5</rdf:li><rdf:li>2.0</rdf:li><rdf:li>2.5</rdf:li></rdf:Seq>'
        '</hdrgm:GainMapMax></rdf:Description>')
gain_full = gain_jpeg[:2] + xmp(desc) + gain_jpeg[2:]

base_jpeg = jpeg(base)
prim_xmp = xmp('<rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"/>')

# MPF-segment (big-endian), vaste grootte; groottes en offsets vullen we in als de rest vaststaat.
def mpf(first_size, second_size, second_offset):
    tiff = b"MM\x00*" + struct.pack(">I", 8)
    ifd = struct.pack(">H", 3)
    ifd += struct.pack(">HHI", 0xB000, 7, 4) + b"0100"
    ifd += struct.pack(">HHII", 0xB001, 4, 1, 2)
    ifd += struct.pack(">HHII", 0xB002, 7, 32, 8 + len(ifd) + 12 + 4)
    ifd += struct.pack(">I", 0)
    entries = struct.pack(">IIIHH", 0x20030000, first_size, 0, 0, 0) + struct.pack(">IIIHH", 0x00000000, second_size, second_offset, 0, 0)
    return segment(0xE2, b"MPF\x00" + tiff + ifd + entries)

head = base_jpeg[:2] + prim_xmp
mpf_len = len(mpf(0, 0, 0))
primary_len = len(head) + mpf_len + len(base_jpeg) - 2
tiff_pos = len(head) + 4 + 4  # marker+lengte (4) en "MPF\0" (4)
primary = head + mpf(primary_len, len(gain_full), primary_len - tiff_pos) + base_jpeg[2:]
assert len(primary) == primary_len
open(OUT, "wb").write(primary + gain_full)
print("geschreven", OUT, len(primary) + len(gain_full), "bytes")

sys.path.insert(0, "prototype")
import uhdr
u = uhdr.UltraHdr(OUT)
print("gain map gevonden:", u.has_gainmap, u.meta, "gm", u.gm.size, "basis", u.base.size, "trailer", u.trailer_len)
for (x, y) in [(0, 0), (10, 5), (31, 23), (63, 47)]:
    sdr, hdr = u.strip(y, y + 1)
    print((x, y), "base", u.base_array()[y, x].tolist(), "sdr", [round(float(v), 6) for v in sdr[0, x]], "hdr", [round(float(v), 6) for v in hdr[0, x]])
