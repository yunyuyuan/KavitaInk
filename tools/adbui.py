"""adb + uiautomator 小工具：按文字查找控件并点击、截图、输入文字。"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = "/home/ubuntu/android-sdk/platform-tools/adb"
SHOTS = "/home/ubuntu/KavitaInk/tools/shots"


def sh(*args, timeout=120):
    return subprocess.run([ADB, *args], capture_output=True, timeout=timeout).stdout


def shell(cmd, timeout=120):
    return sh("shell", cmd, timeout=timeout).decode("utf-8", "replace")


def dump():
    for _ in range(5):
        out = shell("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml", timeout=180)
        if "<hierarchy" in out:
            return ET.fromstring(out[out.index("<?xml"):] if "<?xml" in out else out[out.index("<hierarchy"):])
        time.sleep(3)
    raise RuntimeError("uiautomator dump failed")


def nodes(root=None):
    root = root if root is not None else dump()
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) == 4:
            x1, y1, x2, y2 = map(int, b)
            yield n.get("text", ""), (x1 + x2) // 2, (y1 + y2) // 2, n


def find(text, exact=False, root=None):
    for t, x, y, n in nodes(root):
        if (t == text) if exact else (text in t):
            return x, y
    return None


def texts(root=None):
    return [t for t, *_ in nodes(root) if t]


def tap(x, y):
    shell(f"input tap {x} {y}")


def tap_text(text, exact=False, wait=60):
    end = time.time() + wait
    while time.time() < end:
        p = find(text, exact)
        if p:
            tap(*p)
            return True
        time.sleep(2)
    raise RuntimeError(f"找不到控件：{text}；当前界面：{texts()[:30]}")


def wait_text(text, wait=120):
    end = time.time() + wait
    while time.time() < end:
        r = dump()
        if any(text in t for t in texts(r)):
            return True
        time.sleep(3)
    raise RuntimeError(f"等待超时：{text}；当前界面：{texts()[:30]}")


def type_text(s):
    # 整体放在单引号里交给设备端 shell；空格用 %s，单引号用 '\'' 拼接
    esc = s.replace(" ", "%s").replace("'", "'\\''")
    shell(f"input text '{esc}'")
    time.sleep(2)


def key(code):
    shell(f"input keyevent {code}")


def fill(xy, value, check=None, tries=3):
    """点击输入框、清空、输入并校验（慢速模拟器上偶尔会丢字符）"""
    for _ in range(tries):
        tap(*xy)
        time.sleep(1)
        shell("input keyevent KEYCODE_MOVE_END")
        shell("input keyevent " + " ".join(["KEYCODE_DEL"] * 40))
        type_text(value)
        if check is None or check(value):
            return True
    return False


def shot(name):
    import os
    os.makedirs(SHOTS, exist_ok=True)
    data = sh("exec-out", "screencap -p", timeout=180)
    with open(f"{SHOTS}/{name}.png", "wb") as f:
        f.write(data)
    print("shot", name, len(data))


if __name__ == "__main__":
    print(texts())
