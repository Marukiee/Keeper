"""Zet een DNG met ingebakken Lightroom-instellingen op de telefoon, opent hem in Lightroom mobile en maakt een schermafdruk.

Gebruik als module: probe(naam, xml_beschrijving, bron_dng) -> pad naar de schermafdruk. Voor tests van het formaat van RGB-tabellen.
De bestanden gaan naar /sdcard/Download/keeper-<naam>.dng en worden bij `opruimen()` weer weggehaald.
"""
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import dng_xmp

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PHONE = "3B164V0127K00000"
CRS = 'xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"'


def adb(*args, out=False):
    cmd = [ADB, "-s", PHONE, *args]
    return subprocess.run(cmd, check=True, capture_output=True).stdout if out else subprocess.run(cmd, check=True, capture_output=True)


def shell(cmd):
    return subprocess.run([ADB, "-s", PHONE, "shell", cmd], capture_output=True, text=True).stdout


def check_awake():
    """Stopt met een duidelijke fout als de telefoon slaapt of vergrendeld is (de PIN is aan de gebruiker, niet aan ons)."""
    shell("input keyevent KEYCODE_WAKEUP")  # alleen het scherm aan, verandert geen instelling
    time.sleep(0.7)
    if "mWakefulness=Awake" not in shell("dumpsys power | grep mWakefulness="):
        raise RuntimeError("telefoon slaapt")
    if "isKeyguardShowing=true" in shell("dumpsys window | grep isKeyguardShowing"):
        raise RuntimeError("telefoon is vergrendeld: ontgrendel hem en laat hem aan")


def wait_awake(seconds):
    """Wacht en prikt het scherm om de paar seconden wakker, zodat de schermtime-out niet toeslaat."""
    end = time.time() + seconds
    while time.time() < end:
        shell("input keyevent KEYCODE_WAKEUP")
        time.sleep(min(3, max(0.1, end - time.time())))


def probe(name, description_xml, src, workdir, wait=7):
    check_awake()
    os.makedirs(workdir, exist_ok=True)
    dng = os.path.join(workdir, f"keeper-{name}.dng")
    xmp = dng_xmp.merge(dng_xmp.read_xmp(src), description_xml)
    dng_xmp.embed(src, dng, xmp)
    remote = f"/sdcard/Download/keeper-{name}.dng"
    adb("push", dng, remote)
    adb("shell", "am", "force-stop", "com.adobe.lrmobile")
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", f"file://{remote}", "-t", "image/x-adobe-dng", "-p", "com.adobe.lrmobile")
    wait_awake(wait)
    png = os.path.join(workdir, f"scherm-{name}.png")
    with open(png, "wb") as f:
        f.write(adb("exec-out", "screencap", "-p", out=True))
    import numpy as np
    from PIL import Image
    a = np.asarray(Image.open(png).convert("L"))
    if a.std() < 3:
        raise RuntimeError(f"schermafdruk {png} is effen (zwart of leeg): de foto is niet getoond")
    return png


def opruimen(names):
    for n in names:
        subprocess.run([ADB, "-s", PHONE, "shell", "rm", "-f", f"/sdcard/Download/keeper-{n}.dng"], capture_output=True)
