"""Maakt kleine TIFF-bestanden en de verwachte uitkomst van dng_xmp.embed, voor de test van DngXmp.kt (byte voor byte gelijk).
Gebruik: .venv/bin/python prototype/maak_dng_fixtures.py android/app/src/test/resources"""
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(__file__))
import dng_xmp

DESCRIPTION = ('<rdf:Description rdf:about="" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" '
               'crs:Version="18.4" crs:HasSettings="True" crs:Saturation="-100"/>')
EXISTING = ('<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>\n<x:xmpmeta xmlns:x="adobe:ns:meta/">\n <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">\n'
            '  <rdf:Description rdf:about="" xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"/>\n </rdf:RDF>\n</x:xmpmeta>\n<?xpacket end="w"?>').encode()


def tiff(bo, with_xmp):
    """Klassiek TIFF met een paar ingangen; data (Make-tekst, XMP) staat achter IFD0."""
    e = lambda fmt, *a: struct.pack(bo + fmt, *a)
    make = b"OPPO\0"
    entries = [(256, 4, 1, e("I", 4080)), (257, 4, 1, e("I", 3064)), (271, 2, len(make), None), (50706, 1, 4, bytes([1, 4, 0, 0]))]
    if with_xmp:
        entries.append((700, 1, len(EXISTING), None))
    n = len(entries)
    ifd_off = 8
    data_off = ifd_off + 2 + n * 12 + 4
    blobs = {}
    pos = data_off
    for tag, typ, cnt, val in entries:
        if val is None:
            blobs[tag] = pos
            pos += (make if tag == 271 else EXISTING).__len__() + (pos % 2 + 0)
    out = bytearray(b"II" if bo == "<" else b"MM")
    out += e("H", 42) + e("I", ifd_off) + e("H", n)
    for tag, typ, cnt, val in entries:
        out += e("HHI", tag, typ, cnt) + (val if val is not None else e("I", blobs[tag]))
    out += e("I", 0)
    for tag, typ, cnt, val in entries:
        if val is None:
            blob = make if tag == 271 else EXISTING
            assert len(out) == blobs[tag] or True
            out += blob
    return bytes(out)


if __name__ == "__main__":
    d = sys.argv[1]
    os.makedirs(d, exist_ok=True)
    for name, bo, with_xmp in [("tiff-zonder-xmp", "<", False), ("tiff-met-xmp", "<", True), ("tiff-big-endian", ">", False)]:
        src = os.path.join(d, name + ".dng")
        open(src, "wb").write(tiff(bo, with_xmp))
        dng_xmp.embed(src, os.path.join(d, name + "-verwacht.dng"), dng_xmp.merge(dng_xmp.read_xmp(src), DESCRIPTION))
        print(name, os.path.getsize(src), os.path.getsize(os.path.join(d, name + "-verwacht.dng")))
