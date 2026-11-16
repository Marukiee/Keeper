"""Ultra HDR JPEG (basisbeeld + MPF + gain map) lezen en HDR reconstrueren.

Prototype voor fase 0. Volgt de Ultra HDR-spec (Android / ISO 21496-1):
  HDR = (SDR_lineair + offset_sdr) * 2^(gain * gewicht) - offset_hdr
met gain = min + (max - min) * G^(1/gamma), alles in log2.
"""
import io
import re
import struct

import numpy as np
from PIL import Image

SRGB_LUT = np.array(
    [(v / 255 / 12.92) if v / 255 <= 0.04045 else (((v / 255) + 0.055) / 1.055) ** 2.4 for v in range(256)],
    dtype=np.float32,
)


def _segments(data):
    """Geeft (marker, start_van_payload, payload) tot de eerste SOS."""
    pos = 2
    while pos + 4 <= len(data) and data[pos] == 0xFF:
        marker = data[pos + 1]
        if marker == 0xDA:
            return
        length = struct.unpack(">H", data[pos + 2 : pos + 4])[0]
        yield marker, pos + 4, data[pos + 4 : pos + 2 + length]
        pos += 2 + length


def split_mpf(data):
    """Splitst een MPF-bestand in losse JPEG-streams via de MPF-directory."""
    for marker, start, payload in _segments(data):
        if marker == 0xE2 and payload[:4] == b"MPF\x00":
            tiff = payload[4:]
            tiff_abs = start + 4
            end = "<" if tiff[:2] == b"II" else ">"
            ifd = struct.unpack(end + "I", tiff[4:8])[0]
            count = struct.unpack(end + "H", tiff[ifd : ifd + 2])[0]
            for i in range(count):
                tag, _typ, cnt, val = struct.unpack(end + "HHII", tiff[ifd + 2 + 12 * i : ifd + 14 + 12 * i])
                if tag == 0xB002:  # MPEntry
                    out = []
                    for n in range(cnt // 16):
                        _attr, size, off = struct.unpack(end + "III", tiff[val + 16 * n : val + 16 * n + 12])
                        s = 0 if n == 0 else tiff_abs + off
                        out.append(data[s : s + size])
                    return out
    return [data]


def parse_gainmap_xmp(jpeg):
    """Leest de hdrgm-parameters uit de XMP van het gain-map-beeld (per kanaal indien opgegeven)."""
    xmp = ""
    for marker, _s, payload in _segments(jpeg):
        if marker == 0xE1 and payload.startswith(b"http://ns.adobe.com/xap/1.0/\x00"):
            xmp += payload.split(b"\x00", 1)[1].decode("utf-8", "replace")
    if "hdrgm" not in xmp:
        return None

    def get(name, default):
        m = re.search(r'hdrgm:%s="([^"]+)"' % name, xmp)
        if m:
            return [m.group(1)]
        m = re.search(r"<hdrgm:%s>(.*?)</hdrgm:%s>" % (name, name), xmp, re.S)
        if m:
            items = re.findall(r"<rdf:li>([^<]+)</rdf:li>", m.group(1))
            return items or [m.group(1).strip()]
        return [default]

    f = lambda n, d: np.array([float(x) for x in get(n, d)], dtype=np.float32)
    return {
        "min": f("GainMapMin", "0"),
        "max": f("GainMapMax", "1"),
        "gamma": f("Gamma", "1"),
        "off_sdr": f("OffsetSDR", "0.015625"),
        "off_hdr": f("OffsetHDR", "0.015625"),
        "cap_min": float(get("HDRCapacityMin", "0")[0]),
        "cap_max": float(get("HDRCapacityMax", "1")[0]),
        "base_is_hdr": get("BaseRenditionIsHDR", "False")[0].lower() == "true",
    }


class UltraHdr:
    def __init__(self, path):
        data = open(path, "rb").read()
        parts = split_mpf(data)
        self.primary_bytes = parts[0]
        self.base = Image.open(io.BytesIO(parts[0]))
        self.icc = self.base.info.get("icc_profile")
        self.meta = None
        self.gm = None
        # Per bestand detecteren, nooit aannemen dat er een gain map is
        for extra in parts[1:]:
            meta = parse_gainmap_xmp(extra)
            if meta:
                self.meta = meta
                self.gm = Image.open(io.BytesIO(extra))
                break
        # Grootte van het gain-map-beeld en van wat erna komt (bij Oppo de OnePlus-trailer met src.image)
        self.gm_len = len(parts[1]) if len(parts) > 1 else 0
        self.trailer_len = len(data) - sum(len(p) for p in parts)
        self.width, self.height = self.base.size
        self._base_arr = None
        self._gm_arr = None

    @property
    def has_gainmap(self):
        return self.meta is not None

    def base_array(self):
        if self._base_arr is None:
            self._base_arr = np.asarray(self.base.convert("RGB"))
        return self._base_arr

    def _gm_array(self):
        if self._gm_arr is None:
            g = self.gm.convert("RGB") if self.gm.mode != "L" else self.gm
            self._gm_arr = np.asarray(g)
            if self._gm_arr.ndim == 2:
                self._gm_arr = self._gm_arr[:, :, None]
        return self._gm_arr

    def _upsample_rows(self, y0, y1):
        """Bilineaire opschaling van de gain map, alleen voor rijen y0..y1 (tegels)."""
        gm = self._gm_array()
        gh, gw = gm.shape[:2]
        ys = np.clip((np.arange(y0, y1) + 0.5) * gh / self.height - 0.5, 0, gh - 1)
        yl = np.floor(ys).astype(int)
        yh = np.minimum(yl + 1, gh - 1)
        wy = (ys - yl).astype(np.float32)[:, None, None]
        xs = np.clip((np.arange(self.width) + 0.5) * gw / self.width - 0.5, 0, gw - 1)
        xl = np.floor(xs).astype(int)
        xh = np.minimum(xl + 1, gw - 1)
        wx = (xs - xl).astype(np.float32)[None, :, None]
        rows = gm[yl].astype(np.float32) * (1 - wy) + gm[yh].astype(np.float32) * wy
        return (rows[:, xl] * (1 - wx) + rows[:, xh] * wx) / 255.0

    def strip(self, y0, y1, weight=1.0):
        """Geeft (sdr_lineair, hdr_lineair) voor rijen y0..y1, float32, gamut = die van het basisbeeld."""
        sdr = SRGB_LUT[self.base_array()[y0:y1]]
        if not self.has_gainmap:
            return sdr, sdr
        m = self.meta
        g = self._upsample_rows(y0, y1) ** (1.0 / m["gamma"])
        log_boost = (m["min"] + (m["max"] - m["min"]) * g) * weight
        hdr = (sdr + m["off_sdr"]) * np.exp2(log_boost) - m["off_hdr"]
        return sdr, np.maximum(hdr, 0.0)

    def strips(self, rows=256, weight=1.0):
        for y0 in range(0, self.height, rows):
            y1 = min(y0 + rows, self.height)
            yield y0, y1, *self.strip(y0, y1, weight)
