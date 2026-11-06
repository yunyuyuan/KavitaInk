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


def _dismiss_anr(root):
    """模拟器很慢时系统会弹「xxx isn't responding」（多为 System UI），挡住界面：点「Wait」继续等"""
    if not any("isn't responding" in (n.get("text") or "") for n in root.iter("node")):
        return False
    for n in root.iter("node"):
        if (n.get("text") or "").strip().lower() == "wait":
            b = list(map(int, re.findall(r"\d+", n.get("bounds", ""))))
            if len(b) == 4:
                print("  （系统无响应对话框 → Wait）")
                shell(f"input tap {(b[0] + b[2]) // 2} {(b[1] + b[3]) // 2}")
                time.sleep(3)
                return True
    return False


def dump():
    for _ in range(10):  # 进度刷新时界面可能一时达不到空闲，多试几次
        # 先删掉旧文件：dump 失败时不能读到上一次留下的界面
        out = shell("rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml 2>/dev/null", timeout=180)
        if "<hierarchy" in out:
            root = ET.fromstring(out[out.index("<?xml"):] if "<?xml" in out else out[out.index("<hierarchy"):])
            if _dismiss_anr(root):
                continue
            return root
        time.sleep(5)
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



PKG = "com.manus.kavitaink"


def stop_app():
    """确保应用进程已退出。返回栈较深时 force-stop 后系统会在 1~2 秒内为栈里的下一个 Activity
    重新拉起进程，所以先回到桌面，再停止并确认 pidof 为空。"""
    shell("input keyevent 3")
    time.sleep(1)
    for _ in range(10):
        shell(f"am force-stop {PKG}")
        time.sleep(3)
        if not shell(f"pidof {PKG}").strip():
            return
    raise RuntimeError("应用进程停不下来")


def edit_prefs(name, fn):
    """改应用的 SharedPreferences 文件（需要 adb root）。fn(旧 XML) -> 新 XML。
    先写临时文件再 mv 原子替换：应用即使在这时被拉起，也只会读到完整的旧文件或新文件。"""
    sh("root", timeout=60)
    time.sleep(2)
    sh("wait-for-device", timeout=120)
    d = f"/data/data/{PKG}/shared_prefs"
    f = f"{d}/{name}.xml"
    for _ in range(3):
        stop_app()
        cur = shell(f"cat {f} 2>/dev/null")
        if "<map" not in cur:
            cur = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
        new = fn(cur.replace("<map />", "<map>\n</map>"))
        uid = shell(f"stat -c %u /data/data/{PKG}").strip()
        shell(f"mkdir -p {d}; rm -f {f}.bak")
        subprocess.run([ADB, "shell", f"cat > {f}.e2e"], input=new.encode(), timeout=60)
        shell(f"chown {uid}:{uid} {d} {f}.e2e; chmod 771 {d}; chmod 660 {f}.e2e; mv {f}.e2e {f}; restorecon -R {d}")
        time.sleep(2)
        if shell(f"cat {f}").strip() == new.strip() and not shell(f"pidof {PKG}").strip():
            return
    raise RuntimeError(f"写入 {name}.xml 失败")


def set_app_lang(tag):
    """把应用内语言写进 SharedPreferences（Android 12 及以下有效；需要 adb root）。tag 为空 = 跟随系统。
    模拟器系统语言是英文，而 e2e 脚本按中文界面文字查找控件，所以清数据后要先固定为 zh-CN。"""
    def fn(cur):
        cur = re.sub(r'\s*<string name="language">[^<]*</string>', "", cur)
        return cur.replace("<map>", f'<map>\n    <string name="language">{tag}</string>', 1)
    edit_prefs("settings", fn)


if __name__ == "__main__":
    print(texts())


INDEX_DIR = f"/sdcard/Android/data/{PKG}/files/index"   # 私有索引（chapter.json 等）
CONTENT_DIR = "/sdcard/Books/KavitaInk"                  # 公共下载目录（原文件）


def grant_storage():
    """授予下载目录所需的存储权限（Android 11+ 所有文件访问；Android 10 存储写权限）"""
    shell(f"appops set {PKG} MANAGE_EXTERNAL_STORAGE allow")
    shell(f"pm grant {PKG} android.permission.WRITE_EXTERNAL_STORAGE")
    shell(f"pm grant {PKG} android.permission.READ_EXTERNAL_STORAGE")
    # Android 10：pm grant 不会恢复被「拒绝」设成 ignore 的 app op，系统会按只读视图挂载存储
    for op in ("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE"):
        shell(f"appops set --uid {PKG} {op} allow")
