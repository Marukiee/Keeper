"""Leest de OnePlus/Oppo-trailer achter de JPEG: een JSON-directory aan het eind met ongefilterd bronbeeld en filterinfo.

Staart van het bestand: [..data..][JSON-directory][0x00]["jxrs"][lengte u32 LE]. De offsets in de directory zijn de afstand
vóór het begin van de directory. Gebruik: .venv/bin/python prototype/oppo_trailer.py FOTO.jpg [UITMAP]
"""
import json
import os
import struct
import sys


def read_trailer(data):
    """Geeft {naam: bytes} of None als dit geen trailer heeft."""
    if data[-8:-4] != b"jxrs":
        return None
    n = struct.unpack("<I", data[-4:])[0]
    end = len(data) - 8
    raw = data[end - n:end]  # begint met nullen, dan de JSON, dan een nul
    b = raw.find(b"[")
    if b < 0:
        return None
    try:
        entries = json.loads(raw[b:].rstrip(b"\x00"))
    except ValueError:
        return None
    dir_start = end - n + b  # de offsets tellen terug vanaf het begin van de JSON
    out = {}
    for e in entries:
        s = dir_start - e["offset"]
        out[e["name"]] = data[s:s + e["length"]]
    return out


if __name__ == "__main__":
    d = open(sys.argv[1], "rb").read()
    t = read_trailer(d)
    if not t:
        sys.exit("geen trailer")
    for k, v in t.items():
        print(f"{k:28s} {len(v):>10d} bytes  {v[:16]!r}")
    if len(sys.argv) > 2:
        os.makedirs(sys.argv[2], exist_ok=True)
        for k, v in t.items():
            open(os.path.join(sys.argv[2], os.path.basename(sys.argv[1]) + "." + k), "wb").write(v)
