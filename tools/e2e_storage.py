"""存储权限引导 + 下载目录结构的模拟器测试（Android 10 用正式包；Android 9 用 -PtestMinSdk=28 的测试包）

用法：ANDROID_SERIAL=emulator-5556 KAVITA_PASS=xxx python3 tools/e2e_storage.py app-release.apk

覆盖：
  1. 未授权时点章节下载 → 弹出说明对话框（Android 10 提示「在系统弹窗里选择允许」）；点取消不会加入队列
  2. 去授权 → 系统弹窗点「拒绝」→ 不下载，章节仍是未下载
  3. 再次去授权 → 「拒绝且不再询问」→ 自动打开应用详情页；在那里允许后返回，刚才的下载自动开始
  4. 下载漫画（cbz）、EPUB、PDF，检查 /sdcard/Books/KavitaInk 下的路径、文件名与 md5 和服务器上的原文件一致
  5. 断网后打开 cbz 章节阅读（直接读 zip）
  6. 应用里删除章节 → 原文件和变空的文件夹被删掉
"""
import hashlib
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from adbui import *  # noqa: E402,F401,F403

APK = sys.argv[1]
USER = os.environ.get("KAVITA_USER", "admin")
PW = os.environ.get("KAVITA_PASS", "")
# 本机测试服的书库：书库名 → 服务器上的根目录
LIBS = {"漫画": "/home/ubuntu/library/manga", "小说": "/home/ubuntu/library/books"}
WRITE = "android.permission.WRITE_EXTERNAL_STORAGE"
READ = "android.permission.READ_EXTERNAL_STORAGE"
RESULTS = {}
SDK = 0  # main() 里等设备在线后再读
W, H = 1072, 1448


def back():
    key(4)
    time.sleep(2)


def edit_texts():
    return [(x, y) for t, x, y, n in nodes() if n.get("class") == "android.widget.EditText"]


def long_tap(x, y):
    shell(f"input swipe {x} {y} {x} {y} 1500")
    time.sleep(3)


def record(k, v):
    RESULTS[k] = v
    print(f"  [{k}] {v}")
    with open(os.path.join(SHOTS, "storage_results.json"), "w") as f:
        json.dump(RESULTS, f, ensure_ascii=False, indent=1)


def res_node(*suffixes):
    for t, x, y, n in nodes():
        rid = n.get("resource-id", "")
        if any(rid.endswith(s) for s in suffixes):
            return x, y, rid
    return None


def wait_res(*suffixes, timeout=120):
    end = time.time() + timeout
    while time.time() < end:
        r = res_node(*suffixes)
        if r:
            return r
        time.sleep(3)
    raise RuntimeError(f"没有出现 {suffixes}：{texts()}")


def top_activity():
    out = shell("dumpsys activity activities | grep -m1 mResumedActivity")
    return out.strip()


def tap_undownloaded():
    for t, x, y, n in nodes():
        if t.endswith("未下载") and not t.startswith("共 "):
            tap(x, y)
            return t
    raise RuntimeError(f"找不到未下载的章节：{texts()}")


def open_series(lib, series):
    shell("am force-stop " + PKG)
    shell(f"am start -W -n {PKG}/.ui.LibrariesActivity", timeout=300)
    wait_text(lib, 120)
    tap_text(lib, exact=True)
    wait_text(series, 120)
    tap_text(series, exact=True)
    wait_text("共 ", 120)
    time.sleep(3)


def wait_downloaded(n, timeout=600):
    end = time.time() + timeout
    while time.time() < end:
        t = texts()
        done = sum(1 for s in t if "已下载" in s and not s.startswith("共 "))
        if done >= n:
            return done
        bad = [s for s in t if "失败" in s]
        if bad:
            raise RuntimeError(f"下载失败：{bad}")
        time.sleep(5)
    raise RuntimeError(f"下载超时：{texts()}")


def server_files(lib, series_dir):
    """服务器上该系列文件夹里的原文件：{相对书库根的路径: md5}"""
    root = LIBS[lib]
    out = {}
    for dp, _, fs in os.walk(os.path.join(root, series_dir)):
        for f in fs:
            p = os.path.join(dp, f)
            out[os.path.relpath(p, root)] = hashlib.md5(open(p, "rb").read()).hexdigest()
    return out


def local_files():
    """设备下载目录里的文件：{相对路径: md5}（含隐藏文件，方便发现残留的 .part）"""
    out = {}
    lst = shell(f"cd {CONTENT_DIR} 2>/dev/null && find . -type f -exec md5sum {{}} +")
    for line in lst.splitlines():
        parts = line.strip().split(None, 1)
        if len(parts) == 2:
            out[parts[1][2:] if parts[1].startswith("./") else parts[1]] = parts[0]
    return out


def main():
    global SDK
    sh("wait-for-device", timeout=600)
    SDK = int(shell("getprop ro.build.version.sdk").strip() or 0)
    print(f"== 设备 SDK {SDK}")
    assert SDK >= 29, SDK
    print(sh("install", "-r", APK, timeout=600).decode().strip())
    shell("pm clear " + PKG)
    shell(f"rm -rf {CONTENT_DIR}")
    for p in (WRITE, READ):
        shell(f"pm revoke {PKG} {p}")
        shell(f"pm clear-permission-flags {PKG} {p} user-set user-fixed")
    # 恢复成刚安装时的 app op（之前的测试在弹窗里点过「拒绝」会留下 ignore）
    for op in ("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE"):
        shell(f"appops set --uid {PKG} {op} allow")
    if SDK >= 30:
        shell(f"appops set {PKG} MANAGE_EXTERNAL_STORAGE default")
    set_app_lang("zh-CN")

    print("== 登录（不预先授权）")
    shell(f"am start -W -n {PKG}/.ui.MainActivity", timeout=300)
    wait_text("书架", 300)
    tap_text("书库", exact=True)
    wait_text("连接 Kavita", 120)
    fields = edit_texts()
    assert fill(fields[0], "http://10.0.2.2:5000", lambda v: v in texts())
    assert fill(fields[1], USER, lambda v: v in texts())
    fill(fields[2], PW)
    tap_text("连接", exact=True)
    wait_text("个书库", 300)

    print("== 1. 未授权时下载 → 说明对话框 → 取消")
    open_series("漫画", "Test Manga")
    row = tap_undownloaded()
    wait_text("需要存储权限", 60)
    shot("st01_perm_dialog")
    t = texts()
    msg = next((s for s in t if "保存到" in s), "")
    record("dialog_msg", msg)
    want = "系统弹窗" if SDK < 30 else "允许管理所有文件"
    assert want in msg, msg
    assert "/storage/emulated/0/Books/KavitaInk" in msg, msg
    tap_text("取消", exact=True)
    time.sleep(4)
    t = texts()
    record("after_cancel", [s for s in t if "下载" in s][:4])
    assert not any(("排队" in s or "下载中" in s or "等待" in s) for s in t), t
    assert not shell(f"ls {CONTENT_DIR} 2>/dev/null").strip(), "取消后不应创建下载目录"

    if SDK < 30:
        print("== 2. 系统弹窗点「拒绝」")
        tap_undownloaded()
        wait_text("需要存储权限", 60)
        tap_text("去授权", exact=True)
        x, y, rid = wait_res("permission_deny_button", timeout=120)
        shot("st02_system_dialog")
        tap(x, y)
        time.sleep(5)
        t = texts()
        record("after_deny", {"top": top_activity()[-60:], "queued": any("排队" in s or "下载中" in s for s in t)})
        assert "kavitaink" in top_activity()
        assert not any("排队" in s or "下载中" in s for s in t), t
        assert shell(f"dumpsys package {PKG} | grep '{WRITE}: granted=true'").strip() == ""

        print("== 3. 「拒绝且不再询问」→ 应用详情页 → 允许后返回自动下载")
        tap_undownloaded()
        wait_text("需要存储权限", 60)
        tap_text("去授权", exact=True)
        x, y, rid = wait_res("permission_deny_and_dont_ask_again_button", "permission_deny_dont_ask_again_button",
                             timeout=120)
        record("dont_ask_button", rid)
        tap(x, y)
        end = time.time() + 60
        while time.time() < end and "settings" not in top_activity().lower():
            time.sleep(3)
        record("opened_settings", top_activity()[-70:])
        assert "settings" in top_activity().lower()
        shot("st03_app_details")
        # 像用户一样在应用详情页里点「Permissions → Storage → Allow」。
        # 不能用 pm grant：前面在弹窗里点「拒绝」时，权限控制器把 READ/WRITE_EXTERNAL_STORAGE 的 app op 设成了 ignore，
        # pm grant 只改权限不改 app op，系统仍按只读视图挂载存储；设置页的开关会把两者一起恢复。
        tap_text("Permissions", exact=True, wait=60)
        tap_text("Storage", exact=True, wait=60)
        tap_text("Allow", exact=True, wait=60)
        time.sleep(3)
        shot("st03b_storage_allowed")
        record("ops_after_settings_toggle", [l for l in shell(f"appops get {PKG}").splitlines() if "EXTERNAL_STORAGE" in l])
        for _ in range(4):
            if PKG in top_activity():
                break
            back()
            time.sleep(3)
        assert PKG in top_activity(), top_activity()
        wait_downloaded(1, 600)
        shot("st04_downloaded_after_grant")
        record("auto_resume_after_grant", True)
    else:
        print("== 2/3. Android 11+：去授权 → 所有文件访问页 → 允许后返回自动下载")
        tap_undownloaded()
        wait_text("需要存储权限", 60)
        tap_text("去授权", exact=True)
        end = time.time() + 60
        while time.time() < end and "settings" not in top_activity().lower():
            time.sleep(3)
        record("opened_settings", top_activity()[-70:])
        shot("st03_all_files_page")
        shell(f"appops set {PKG} MANAGE_EXTERNAL_STORAGE allow")
        back()
        wait_downloaded(1, 600)
        record("auto_resume_after_grant", True)

    print("== 4. 下载其余内容并核对目录")
    tap_text("全部下载", exact=True)
    wait_text("将下载", 60)
    tap_text("下载", exact=True)
    wait_downloaded(2, 900)
    for series in ["Alice's Adventures in Wonderland", "PDF Book"]:
        open_series("小说", series)
        tap_undownloaded()
        wait_downloaded(1, 900)
    time.sleep(3)
    want = {}
    want.update({"漫画/" + k: v for k, v in server_files("漫画", "Test Manga").items()})
    want.update({"小说/" + k: v for k, v in server_files("小说", "Alice").items()})
    want.update({"小说/" + k: v for k, v in server_files("小说", "PDF Book").items()})
    got = local_files()
    record("server_files", sorted(want))
    record("local_files", sorted(got))
    missing = sorted(set(want) - set(got))
    extra = sorted(set(got) - set(want))
    diff = sorted(k for k in set(want) & set(got) if want[k] != got[k])
    record("dir_check", {"missing": missing, "extra": extra, "md5_mismatch": diff})
    assert not missing and not extra and not diff
    print(shell(f"ls -laR {CONTENT_DIR}"))

    print("== 5. 断网后读 cbz")
    shell("settings put secure immersive_mode_confirmations confirmed")
    shell("am force-stop " + PKG)
    shell("iptables -D OUTPUT -d 10.0.2.2 -j REJECT 2>/dev/null")
    shell("iptables -A OUTPUT -d 10.0.2.2 -j REJECT")
    try:
        shell(f"am start -W -n {PKG}/.ui.MainActivity", timeout=300)
        wait_text("书架", 120)
        tap_text("Test Manga", exact=True)
        wait_text("第1卷", 120)
        tap_text("第1卷")
        time.sleep(15)
        shot("st05_cbz_p1")
        tap(W - 100, H // 2)
        time.sleep(6)
        shot("st06_cbz_p2")
        record("cbz_reader", top_activity()[-60:])
        assert "ImageReaderActivity" in top_activity()
        back()
        time.sleep(3)
    finally:
        shell("iptables -D OUTPUT -d 10.0.2.2 -j REJECT 2>/dev/null")

    print("== 6. 删除章节 → 原文件与空文件夹被删")
    shell("am force-stop " + PKG)
    shell(f"am start -W -n {PKG}/.ui.MainActivity", timeout=300)
    wait_text("书架", 120)
    # 书架上长按 PDF Book → 删除
    for t, x, y, n in nodes():
        if t == "PDF Book":
            long_tap(x, y)
            break
    wait_text("已选", 60)
    tap_text("删除所选")              # 顶栏「删除所选（1）」
    wait_text("将释放", 60)
    tap_text("删除", exact=True)      # 确认对话框
    time.sleep(8)
    left = local_files()
    record("after_delete", sorted(left))
    assert not any(k.startswith("小说/PDF Book/") for k in left)
    assert "PDF Book" not in shell(f"ls {CONTENT_DIR}/小说/"), "空文件夹应被删除"
    print("DONE")


if __name__ == "__main__":
    main()
