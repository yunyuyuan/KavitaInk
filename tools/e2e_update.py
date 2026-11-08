"""应用内检查更新端到端测试：装一个版本号为 1.1.9 的正式签名包，对着真实的 GitHub Release 检查、下载并安装 1.2.0。"""
import re
import sys
import time
sys.path.insert(0, '/home/ubuntu/KavitaInk/tools')
from adbui import shell, shot, texts, tap_text, set_app_lang, sh

PKG = "net.yunyuyuan.kavitaink"
APK = sys.argv[1]


def ver():
    out = shell(f"dumpsys package {PKG}")
    m = re.search(r"versionName=(\S+)", out)
    return m.group(1) if m else None


def wait_any(pred, timeout):
    t0 = time.time()
    while time.time() - t0 < timeout:
        ts = texts()
        hit = [t for t in ts if pred(t)]
        if hit:
            return hit[0], ts
        time.sleep(2)
    return None, texts()


print(sh("uninstall", PKG, timeout=120).decode().strip())
print(sh("install", APK, timeout=600).decode().strip())
print("installed", ver())
set_app_lang("zh-CN")
shell(f"appops set {PKG} REQUEST_INSTALL_PACKAGES allow")
shell(f"am start -W -n {PKG}/.ui.UpdateActivity")
t0 = time.time()
hit, ts = wait_any(lambda t: t.startswith("发现新版本") or t.startswith("检查失败") or t.startswith("已是最新"), 180)
print("check:", hit, f"{time.time()-t0:.0f}s")
shot("u1_found")
if not hit or not hit.startswith("发现新版本 1.2.0"):
    print("texts", ts)
    sys.exit(1)
tap_text("下载并安装", exact=True)
t1 = time.time()
hit, ts = wait_any(lambda t: t.upper() in ("INSTALL", "安装") and t != "安装" or "install an update" in t or t.startswith("下载失败"), 300)
print("installer:", hit, f"{time.time()-t1:.0f}s")
shot("u2_installer")
if not hit or hit.startswith("下载失败"):
    print("texts", ts)
    sys.exit(1)
tap_text("INSTALL", exact=True)
for _ in range(60):
    time.sleep(3)
    if ver() == "1.2.0":
        break
print("after install", ver())
time.sleep(3)
shot("u3_done")
print("RESULT", "PASS" if ver() == "1.2.0" else "FAIL")
