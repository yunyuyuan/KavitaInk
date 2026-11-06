"""
模拟器端到端测试：真实 APK + 真实 Kavita 服务器（模拟器内通过 10.0.2.2 访问宿主机）。
流程：登录 → 浏览书库 → 下载漫画 / EPUB / PDF → 断网 → 离线阅读三种格式 → 检查崩溃日志。
"""
import os
KAVITA_USER = os.environ.get("KAVITA_USER", "admin")
KAVITA_PASS = os.environ.get("KAVITA_PASS", "")  # 测试服务器的密码，通过环境变量传入
import sys
import time
from adbui import *

APK = sys.argv[1] if len(sys.argv) > 1 else "/home/ubuntu/KavitaInk/app/build/outputs/apk/release/app-release.apk"
PKG = "com.manus.kavitaink"
W, H = 1072, 1448
step = sys.argv[2] if len(sys.argv) > 2 else "all"


def back():
    key(4)
    time.sleep(2)


def edit_texts():
    return [(x, y) for t, x, y, n in nodes() if n.get("class") == "android.widget.EditText"]


def tap_downloaded():
    """点击第一个已下载的章节行（跳过顶部「共 N 项 · 已下载 N」状态栏）"""
    for t, x, y, n in nodes():
        if "已下载" in t and not t.startswith("共 "):
            tap(x, y)
            return
    raise RuntimeError(f"没有已下载的章节：{texts()}")


def download_series(lib, series, expect_items):
    tap_text(lib, exact=True)
    wait_text(series)
    tap_text(series, exact=True)
    wait_text("共 ")
    if expect_items > 1:
        tap_text("全部下载", exact=True)
        wait_text("将下载")
        tap_text("下载", exact=True)
    else:
        wait_text("未下载")
        # 点章节行（副标题以「未下载」结尾），而不是顶部的提示文字
        for t, x, y, n in nodes():
            if t.endswith("未下载") and not t.startswith("共 "):
                tap(x, y)
                break
        else:
            raise RuntimeError(f"找不到未下载的章节：{texts()}")
    end = time.time() + 600
    while time.time() < end:
        t = texts()
        # 章节副标题形如「5 页 · 145 KB · 已下载」
        done = sum(1 for s in t if "已下载" in s and not s.startswith("共 "))
        if done >= expect_items:
            print(f"  {series}: 已下载 {done} 项")
            return
        bad = [s for s in t if "失败" in s]
        if bad:
            raise RuntimeError(f"下载失败：{bad}")
        time.sleep(5)
    raise RuntimeError(f"下载超时：{series}")


def phase_online():
    print(sh("install", "-r", APK, timeout=600).decode())
    shell("pm clear " + PKG)
    shell(f"rm -rf {CONTENT_DIR}")
    grant_storage()        # 下载目录在公共存储（/sdcard/Books/KavitaInk）
    set_app_lang("zh-CN")  # 模拟器系统语言是英文，脚本按中文文字查找控件
    shell("logcat -c")
    shell(f"am start -W -n {PKG}/.ui.MainActivity", timeout=300)
    wait_text("书架", 300)
    shot("01_shelf_empty")

    tap_text("书库", exact=True)
    wait_text("连接 Kavita")
    fields = edit_texts()
    assert len(fields) >= 4, fields
    assert fill(fields[0], "http://10.0.2.2:5000", lambda v: v in texts())
    assert fill(fields[1], KAVITA_USER, lambda v: v in texts())
    fill(fields[2], KAVITA_PASS)  # 密码框文字不可见，无法校验
    shot("02_login_filled")
    tap_text("连接", exact=True)  # 顶栏按钮，键盘弹出时也可见
    wait_text("个书库", 300)
    shot("03_libraries")

    download_series("漫画", "Test Manga", 2)
    shot("04_series_manga_downloaded")
    back(); back()

    tap_text("小说", exact=True)
    wait_text("Alice")
    shot("05_series_list_books")
    back()
    download_series("小说", "Alice's Adventures in Wonderland", 1)
    back(); back()
    download_series("小说", "测试小说", 1)
    back(); back()
    download_series("小说", "PDF Book", 1)
    shot("06_series_pdf_downloaded")
    back(); back(); back()
    wait_text("书架")
    wait_text("共 4 部")
    shot("07_shelf_full")


def phase_offline():
    # 切断模拟器到宿主机（Kavita）的网络，证明阅读完全离线
    sh("root"); time.sleep(5)
    # 系统首次进入沉浸式全屏会弹「Viewing full screen」提示（真机点一次 GOT IT 即可），测试中直接标记为已确认
    shell("settings put secure immersive_mode_confirmations confirmed")
    shell("am force-stop " + PKG)
    shell("iptables -D OUTPUT -d 10.0.2.2 -j REJECT 2>/dev/null; iptables -D OUTPUT -d 10.0.2.2 -j REJECT 2>/dev/null")
    shell("iptables -A OUTPUT -d 10.0.2.2 -j REJECT")
    print("  断网验证:", shell("toybox nc -w 3 10.0.2.2 5000 </dev/null >/dev/null 2>&1 && echo 仍可连接 || echo 已无法连接服务器").strip())
    shell(f"am start -W -n {PKG}/.ui.MainActivity", timeout=300)
    wait_text("书架")

    # 漫画
    tap_text("Test Manga", exact=True)
    wait_text("第1卷")
    tap_text("第1卷")
    time.sleep(15)
    shot("08_manga_p1")
    tap(W - 100, H // 2); time.sleep(6)
    shot("09_manga_p2")
    tap(W // 2, H // 2); time.sleep(5)
    shot("10_manga_menu")
    back(); time.sleep(2)
    back(); back()

    # EPUB 英文
    tap_text("Alice", exact=False)
    wait_text("已下载")
    tap_downloaded()
    time.sleep(15)
    shot("11_epub_alice_start")
    for _ in range(3):
        tap(W - 100, H // 2); time.sleep(5)
    shot("12_epub_alice_p4")
    tap(W // 2, H // 2); time.sleep(4)
    shot("13_epub_menu")
    tap_text("目录", exact=True); time.sleep(5)
    shot("14_epub_toc")
    tap_text("CHAPTER III"); time.sleep(12)
    shot("15_epub_chapter3")
    back(); back()  # 阅读器 → 系列页 → 书架

    # EPUB 中文
    tap_text("测试小说", exact=True)
    wait_text("已下载")
    tap_downloaded()
    time.sleep(15)
    shot("16_epub_zh")
    key(25); time.sleep(5)  # 音量减 = 下一页
    shot("17_epub_zh_volume_next")
    back(); back()

    # PDF
    tap_text("PDF Book", exact=True)
    wait_text("已下载")
    tap_downloaded()
    time.sleep(10)
    shot("18_pdf")
    back(); back()
    wait_text("书架")
    shot("19_shelf_progress")

    shell("iptables -D OUTPUT -d 10.0.2.2 -j REJECT")
    log = shell("logcat -d -b crash")
    crashes = [l for l in log.splitlines() if PKG in l or "FATAL" in l]
    print("CRASH LOG:", "\n".join(crashes[:40]) if crashes else "无崩溃")


if step in ("all", "online"):
    phase_online()
if step in ("all", "offline"):
    phase_offline()
print("E2E DONE")
