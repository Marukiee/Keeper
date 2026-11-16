"""Adobe Camera Raw / Lightroom 'Look' (profiel) met een 3D-LUT schrijven en lezen.

Het formaat is afgeleid van echte profielen uit Lightroom mobile (zie de tests onderaan, ze draaien tegen die bestanden):
  tabel  = [u32 type=1][u32 versie=1][u32 dimensies=3][u32 punten per as]
           [punten^3 * (R,G,B als u16 LE)]  (B loopt het snelst; opgeslagen als verschil met een lineaire ramp)
           [u32 primaries][u32 gamma][u32 gamut][f64 min][f64 max]
  tekst  = adobe-base85( [u32 LE lengte van de tabel] + zlib(tabel) )
  id     = MD5 van de ongecomprimeerde tabel, hoofdletters
"""
import hashlib
import re
import struct
import uuid
import zlib

import numpy as np

ALPHA = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-:+=^!/*?`'|()[]{}@%$#"
_IDX = {c: i for i, c in enumerate(ALPHA)}


def b85_decode(text):
    s = [c for c in text if c in _IDX]
    out = bytearray()
    for i in range(0, len(s), 5):
        g = s[i:i + 5]
        k = len(g)
        v = sum(_IDX[c] * 85 ** j for j, c in enumerate(g))
        out += struct.pack("<I", v & 0xFFFFFFFF)[: 4 if k == 5 else k - 1]
    return bytes(out)


def b85_encode(data):
    out = []
    for i in range(0, len(data), 4):
        g = data[i:i + 4]
        k = len(g)
        v = struct.unpack("<I", g + b"\x00" * (4 - k))[0]
        digits = []
        for _ in range(5 if k == 4 else k + 1):
            digits.append(ALPHA[v % 85])
            v //= 85
        out.append("".join(digits))
    return "".join(out)


def ramp(n):
    return np.array([(i * 0xFFFF + (n >> 1)) // (n - 1) for i in range(n)], dtype=np.int64)


def encode_table(nodes, primaries=1, gamma=3, gamut=0, min_amount=0.0, max_amount=2.0):
    """nodes: (n,n,n,3) floats 0..1 in volgorde [r][g][b]. Geeft (id, tekst)."""
    n = nodes.shape[0]
    q = np.clip(np.rint(nodes * 65535.0), 0, 65535).astype(np.int64)
    nop = ramp(n)
    d = q - nop[:, None, None, None] * np.array([1, 0, 0]) - nop[None, :, None, None] * np.array([0, 1, 0]) - nop[None, None, :, None] * np.array([0, 0, 1])
    body = (d & 0xFFFF).astype("<u2").tobytes()
    raw = struct.pack("<4I", 1, 1, 3, n) + body + struct.pack("<3I2d", primaries, gamma, gamut, min_amount, max_amount)
    comp = struct.pack("<I", len(raw)) + zlib.compress(raw)
    return hashlib.md5(raw).hexdigest().upper(), b85_encode(comp)


def decode_table(text):
    raw = b85_decode(text)
    dec = zlib.decompress(raw[4:])
    assert struct.unpack("<I", raw[:4])[0] == len(dec)
    t, ver, dim, n = struct.unpack("<4I", dec[:16])
    assert (t, ver, dim) == (1, 1, 3), (t, ver, dim)
    a = np.frombuffer(dec[16:16 + 6 * n ** 3], dtype="<u2").astype(np.int64).reshape(n, n, n, 3)
    nop = ramp(n)
    a = (a + nop[:, None, None, None] * np.array([1, 0, 0]) + nop[None, :, None, None] * np.array([0, 1, 0])
         + nop[None, None, :, None] * np.array([0, 0, 1])) & 0xFFFF
    tail = struct.unpack("<3I2d", dec[16 + 6 * n ** 3:16 + 6 * n ** 3 + 28])
    return a / 65535.0, tail, hashlib.md5(dec).hexdigest().upper()


def look_xmp(name, nodes, group="Keeper", sort="1", cluster="Keeper", profile_uuid=None):
    tid, text = encode_table(nodes)
    u = profile_uuid or uuid.uuid4().hex.upper()
    return f'''<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.6-c140 79.160451, 2017/05/06-01:08:21        ">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
   crs:PresetType="Look"
   crs:Cluster="{cluster}"
   crs:UUID="{u}"
   crs:SupportsAmount="True"
   crs:SupportsColor="True"
   crs:SupportsMonochrome="True"
   crs:SupportsHighDynamicRange="True"
   crs:SupportsNormalDynamicRange="True"
   crs:SupportsSceneReferred="True"
   crs:SupportsOutputReferred="True"
   crs:CameraModelRestriction=""
   crs:Copyright=""
   crs:ContactInfo=""
   crs:Version="12.2"
   crs:ProcessVersion="11.0"
   crs:ConvertToGrayscale="False"
   crs:RGBTable="{tid}"
   crs:Table_{tid}="{text}"
   crs:HasSettings="True">
   <crs:Name>
    <rdf:Alt>
     <rdf:li xml:lang="x-default">{name}</rdf:li>
    </rdf:Alt>
   </crs:Name>
   <crs:ShortName>
    <rdf:Alt>
     <rdf:li xml:lang="x-default"/>
    </rdf:Alt>
   </crs:ShortName>
   <crs:SortName>
    <rdf:Alt>
     <rdf:li xml:lang="x-default">{sort}</rdf:li>
    </rdf:Alt>
   </crs:SortName>
   <crs:Group>
    <rdf:Alt>
     <rdf:li xml:lang="x-default">{group}</rdf:li>
    </rdf:Alt>
   </crs:Group>
   <crs:Description>
    <rdf:Alt>
     <rdf:li xml:lang="x-default"/>
    </rdf:Alt>
   </crs:Description>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
'''


def self_test(folder):
    """Echte tabellen uit Lightroom lezen, opnieuw schrijven, en kijken of de inhoud en het id gelijk blijven."""
    import glob
    n_ok = 0
    for f in sorted(glob.glob(folder + "/**/*.xmp", recursive=True)):
        s = open(f, encoding="utf-8", errors="replace").read()
        for tid in re.findall(r'crs:RGBTable="([0-9A-F]+)"', s):
            m = re.search(r'crs:Table_%s="([^"]*)"' % tid, s)
            nodes, tail, md5 = decode_table(m.group(1))
            assert md5 == tid and tail == (1, 3, 0, 0.0, 2.0), (f, md5, tid, tail)
            tid2, text2 = encode_table(nodes)
            nodes2, _t, md52 = decode_table(text2)
            assert tid2 == tid and np.array_equal(np.rint(nodes * 65535), np.rint(nodes2 * 65535)), f
            n_ok += 1
    return n_ok


if __name__ == "__main__":
    import sys
    print("echte tabellen heen en terug goed:", self_test(sys.argv[1]))


# ---- Look in de XMP van een DNG (zo leest Lightroom mobile hem; plat bovenaan werkt niet, zie CLAUDE.md) ----
# Gemeten met een testfoto in Lightroom mobile 11.5.31: kop-waarden van de tabel
#   primaries: 0 sRGB, 1 Adobe RGB, 2 ProPhoto, 3 Display P3, 4 Rec.2020
#   gamma:     0 lineair, 1 sRGB-curve, 2 gamma 1,8, 3 gamma 2,2
# De tabel werkt op het eindbeeld (na toon en kleur van Lightroom) in de gekozen ruimte.
PRIMARIES_SRGB, PRIMARIES_ADOBE, PRIMARIES_PROPHOTO, PRIMARIES_P3, PRIMARIES_REC2020 = 0, 1, 2, 3, 4
GAMMA_LINEAR, GAMMA_SRGB, GAMMA_18, GAMMA_22 = 0, 1, 2, 3

CRS_NS = 'xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"'


def dng_look_description(name, nodes, look_uuid, extra_settings="", group="Keeper", amount=1.0,
                         primaries=PRIMARIES_P3, gamma=GAMMA_SRGB, version="18.4", process="15.4"):
    """rdf:Description voor in de XMP van een DNG: Look met RGB-tabel, plus eventuele losse instellingen (bijv. vignet)."""
    tid, text = encode_table(nodes, primaries=primaries, gamma=gamma)
    return f'''<rdf:Description rdf:about="" {CRS_NS}
   crs:Version="{version}" crs:ProcessVersion="{process}" crs:HasSettings="True" {extra_settings}
   crs:Table_{tid}="{text}">
   <crs:Look>
    <rdf:Description crs:Name="{name}" crs:Amount="{amount:.6f}" crs:UUID="{look_uuid}" crs:SupportsAmount="True" crs:SupportsColor="True" crs:SupportsMonochrome="True" crs:SupportsHighDynamicRange="True" crs:SupportsNormalDynamicRange="True" crs:SupportsSceneReferred="True" crs:SupportsOutputReferred="True" crs:Group="{group}">
     <crs:Parameters>
      <rdf:Description crs:Version="{version}" crs:ProcessVersion="{process}" crs:ConvertToGrayscale="False" crs:RGBTable="{tid}" crs:RGBTableAmount="1.000000"/>
     </crs:Parameters>
    </rdf:Description>
   </crs:Look>
  </rdf:Description>'''


def preset_xmp(name, nodes, look_uuid, preset_uuid, extra_settings="", group="Keeper", primaries=PRIMARIES_P3, gamma=GAMMA_SRGB,
               version="18.4", process="15.4"):
    """Een los Lightroom-voorinstelling (.xmp, PresetType Normal) met de Look (3D-tabel) en eventuele losse instellingen (bijv. vignet).
    Te importeren via Presets > Importeer; werkt dan op elke foto, met een Hoeveelheid-schuif, en alles blijft verder bij te stellen."""
    tid, text = encode_table(nodes, primaries=primaries, gamma=gamma)
    alt = lambda tag, value: f'<crs:{tag}>\n    <rdf:Alt>\n     <rdf:li xml:lang="x-default">{value}</rdf:li>\n    </rdf:Alt>\n   </crs:{tag}>'
    return f'''<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 7.0-c000 1.000000, 0000/00/00-00:00:00">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" {CRS_NS}
   crs:PresetType="Normal"
   crs:Cluster=""
   crs:UUID="{preset_uuid}"
   crs:SupportsAmount="True"
   crs:SupportsColor="True"
   crs:SupportsMonochrome="True"
   crs:SupportsHighDynamicRange="True"
   crs:SupportsNormalDynamicRange="True"
   crs:SupportsSceneReferred="True"
   crs:SupportsOutputReferred="True"
   crs:CameraModelRestriction=""
   crs:Copyright=""
   crs:ContactInfo=""
   crs:Version="{version}"
   crs:ProcessVersion="{process}"
   {extra_settings}
   crs:HasSettings="True"
   crs:Table_{tid}="{text}">
   {alt("Name", name)}
   {alt("ShortName", "")}
   {alt("SortName", name)}
   {alt("Group", group)}
   {alt("Description", "Oppo Find X9 Ultra camera filter, benaderd door Keeper")}
   <crs:Look>
    <rdf:Description crs:Name="{name}" crs:Amount="1.000000" crs:UUID="{look_uuid}" crs:SupportsAmount="True" crs:SupportsColor="True" crs:SupportsMonochrome="True" crs:SupportsHighDynamicRange="True" crs:SupportsNormalDynamicRange="True" crs:SupportsSceneReferred="True" crs:SupportsOutputReferred="True" crs:Group="{group}">
     <crs:Parameters>
      <rdf:Description crs:Version="{version}" crs:ProcessVersion="{process}" crs:ConvertToGrayscale="False" crs:RGBTable="{tid}" crs:RGBTableAmount="1.000000"/>
     </crs:Parameters>
    </rdf:Description>
   </crs:Look>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
'''
