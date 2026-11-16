"""XMP in een DNG zetten of vervangen zonder de rest van het bestand aan te raken (alleen toevoegen aan het eind).

Een DNG is een gewoon TIFF-bestand (klassiek, geen BigTIFF). De XMP-tekst zit in tag 700 van IFD0. Heeft het bestand die tag niet,
dan schrijven we een kopie van IFD0 mét de tag achteraan het bestand en wijzen de kop ernaar; alle andere offsets (SubIFD's, raw-data,
EXIF) zijn absoluut en blijven kloppen. Zo is hetzelfde algoritme in Kotlin te doen met een kopie plus een paar bytes aan het eind.

Gebruik: .venv/bin/python prototype/dng_xmp.py IN.dng UIT.dng XMP.xml
"""
import re
import shutil
import struct
import sys

TYPE_SIZE = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8, 13: 4, 16: 8}
XMP_TAG = 700


def read_xmp(path):
    with open(path, "rb") as f:
        d = f.read()
    bo, off, entries = _ifd0(d)
    for i in range(entries):
        tag, typ, cnt, val = struct.unpack(bo + "HHII", d[off + 2 + i * 12: off + 14 + i * 12])
        if tag == XMP_TAG:
            start = val if cnt > 4 else off + 2 + i * 12 + 8
            return d[start:start + cnt].decode("utf-8", "replace")
    return None


def _ifd0(d):
    if d[:2] == b"II":
        bo = "<"
    elif d[:2] == b"MM":
        bo = ">"
    else:
        raise ValueError("geen TIFF")
    if struct.unpack(bo + "H", d[2:4])[0] != 42:
        raise ValueError("geen klassiek TIFF (BigTIFF wordt niet ondersteund)")
    off = struct.unpack(bo + "I", d[4:8])[0]
    return bo, off, struct.unpack(bo + "H", d[off:off + 2])[0]


def packet(xmp_xml):
    """XMP-pakket met kop en staart, zodat andere lezers het herkennen."""
    body = xmp_xml.strip()
    return ('<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>\n' + body + '\n<?xpacket end="w"?>').encode("utf-8")


def merge(existing, description_xml):
    """Voegt een rdf:Description toe aan een bestaand XMP-document (of maakt een nieuw)."""
    desc = description_xml.strip()
    if not existing:
        return ('<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Keeper">\n <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">\n'
                + desc + '\n </rdf:RDF>\n</x:xmpmeta>')
    body = re.sub(r"<\?xpacket[^>]*\?>", "", existing).strip()
    i = body.rfind("</rdf:RDF>")
    if i < 0:
        raise ValueError("bestaande XMP heeft geen rdf:RDF")
    return body[:i] + desc + "\n " + body[i:]


def embed(src, dst, xmp_xml):
    """Kopieert src naar dst met [xmp_xml] als XMP (vervangt een bestaande)."""
    shutil.copyfile(src, dst)
    pkt = packet(xmp_xml)
    with open(dst, "r+b") as f:
        head = f.read(8)
        bo = "<" if head[:2] == b"II" else ">"
        if head[:2] not in (b"II", b"MM") or struct.unpack(bo + "H", head[2:4])[0] != 42:
            raise ValueError("geen klassiek TIFF")
        off = struct.unpack(bo + "I", head[4:8])[0]
        f.seek(off)
        n = struct.unpack(bo + "H", f.read(2))[0]
        raw = f.read(n * 12)
        nxt = f.read(4)
        entries = [struct.unpack(bo + "HHI4s", raw[i * 12:i * 12 + 12]) for i in range(n)]
        f.seek(0, 2)
        end = f.tell()
        if end % 2:
            f.write(b"\x00"); end += 1
        data_off = end
        f.write(pkt)
        if len(pkt) % 2:
            f.write(b"\x00")
        new_entry = (XMP_TAG, 1, len(pkt), struct.pack(bo + "I", data_off))
        pos = next((i for i, e in enumerate(entries) if e[0] == XMP_TAG), None)
        if pos is not None:
            # bestaande tag: alleen de ingang omleggen (de oude tekst blijft als dode bytes staan)
            f.seek(off + 2 + pos * 12)
            f.write(struct.pack(bo + "HHI", *new_entry[:3]) + new_entry[3])
        else:
            entries.append(new_entry)
            entries.sort(key=lambda e: e[0])
            f.seek(0, 2)
            ifd_off = f.tell()
            f.write(struct.pack(bo + "H", len(entries)))
            for e in entries:
                f.write(struct.pack(bo + "HHI", e[0], e[1], e[2]) + e[3])
            f.write(nxt)
            f.seek(4)
            f.write(struct.pack(bo + "I", ifd_off))
    return dst


if __name__ == "__main__":
    embed(sys.argv[1], sys.argv[2], open(sys.argv[3], encoding="utf-8").read())
    print("klaar:", sys.argv[2])
