"""
模拟器端到端测试：下载进度、暂停 / 继续、重启后保持暂停、网络卡死时暂停、断网自动重试、通知栏按钮、取消。
需要：Kavita(5000) + tools/throttle_proxy.py(5001)，服务器上有 Big Manga（80 页）和 Big Novel（15 MB EPUB）。
用法：python3 e2e_pause.py <apk>
"""
import os
KAVITA_USER = os.environ.get("KAVITA_USER", "admin")
KAVITA_PASS = os.environ.get("KAVITA_PASS", "")  # 测试服务器的密码，通过环境变量传入
import hashlib
import json
import re
import subprocess
import sys
import time

from adbui import *

APK = sys.argv[1] if len(sys.argv) > 1 else "/home/ubuntu/KavitaInk/app/build/outputs/apk/release/app-release.apk"
PKG = "com.manus.kavitaink"
BASE = "http://10.0.2.2:5001"
SK = hashlib.sha1(BASE.lower().encode()).hexdigest()[:10]
LIB = f"/sdcard/Android/data/{PKG}/files/library/{SK}"
MANGA = f"{LIB}/s7/c8"
NOVEL = f"{LIB}/s6/c7"
ORIG_EPUB = "/home/ubuntu/library/books/Big Novel/Big Novel.epub"
R = {}  # 结果


def proxy(mode, rate=400_000):
    with open("/tmp/proxy.json", "w") as f:
        f.write(json.dumps({"mode": mode, "rate": rate}))
    time.sleep(0.8)


def log_lines():
    with open("/tmp/proxy.log") as f:
        return f.read().splitlines()


def service_running():
    return "DownloadService" in shell(f"dumpsys activity services {PKG}")


def wakelock_held():
    return "KavitaInk:download" in shell("dumpsys power")


def pages_on_disk():
    out = shell(f"ls {MANGA}/pages 2>/dev/null")
    return len([x for x in out.split() if x and not x.endswith(".part")])


def size_of(path):
    out = shell(f"stat -c %s '{path}' 2>/dev/null").strip()
    return int(out) if out.isdigit() else -1


def exists(path):
    return shell(f"[ -e '{path}' ] && echo yes || echo no").strip() == "yes"


def wait_until(fn, wait, interval=0.3):
    t0 = time.time()
    while time.time() - t0 < wait:
        if fn():
            return time.time() - t0
        time.sleep(interval)
    return None


def row(pred, root=None):
    for t, x, y, n in nodes(root):
        if t and not t.startswith("共 ") and pred(t):
            return t, x, y
    return None


def wait_row(pred, wait=300, every=2):
    end = time.time() + wait
    while time.time() < end:
        r = row(pred)
        if r:
            return r
        time.sleep(every)
    raise RuntimeError(f"等待行超时；当前界面：{texts()[:30]}")


def back(n=1):
    for _ in range(n):
        key(4)
        time.sleep(2)


def edit_texts():
    return [(x, y) for t, x, y, n in nodes() if n.get("class") == "android.widget.EditText"]


def open_series(lib, series):
    tap_text(lib, exact=True)
    wait_text(series)
    tap_text(series, exact=True)
    wait_text("全部下载")


def pct_pages(t):
    m = re.search(r"(\d+) / 80 页", t)
    return int(m.group(1)) if m else -1


def mb_done(t):
    m = re.search(r"([\d.]+) (KB|MB) / ", t)
    if not m:
        return -1
    v = float(m.group(1))
    return v / 1024 if m.group(2) == "KB" else v


def step(name):
    print(f"\n=== {name} ===", flush=True)

import os
FROM = int(os.environ.get("FROM", "0"))  # 从第几步开始（0 = 安装登录）


# ---------------------------------------------------------------- 准备
def step0():
    global attempt, btns, crash, done, f, first, kept, l, m, mark, n, n1, n2, p, page_reqs, part_at_drop, r, reqs_after, retry_reqs, s1, s2, sha, t, t0, t_np, t_pause, t_stall, x, y
    step("安装并登录（经限速代理）")
    proxy("ok", 300_000)
    print(sh("install", "-r", APK, timeout=600).decode().strip())
    shell("pm clear " + PKG)
    shell("logcat -c")
    shell(f"am start -W -n {PKG}/.ui.MainActivity", timeout=300)
    wait_text("书架", 300)
    tap_text("书库", exact=True)
    wait_text("连接 Kavita")
    f = edit_texts()
    assert fill(f[0], BASE, lambda v: v in texts())
    assert fill(f[1], KAVITA_USER, lambda v: v in texts())
    fill(f[2], KAVITA_PASS)
    tap_text("连接", exact=True)
    wait_text("个书库", 300)


# ---------------------------------------------------------------- 1. 漫画：进度 + 暂停
def step1():
    global attempt, btns, crash, done, f, first, kept, l, m, mark, n, n1, n2, p, page_reqs, part_at_drop, r, reqs_after, retry_reqs, s1, s2, sha, t, t0, t_np, t_pause, t_stall, x, y
    step("1. 漫画下载进度与暂停")
    open_series("漫画", "Big Manga")
    tap(*wait_row(lambda t: t.endswith("未下载"))[1:])
    r = wait_row(lambda t: "下载中" in t and pct_pages(t) >= 6, 400)
    print("  进度显示：", r[0])
    R["manga_progress_text"] = r[0]
    shot("p01_series_downloading")
    tap(*r[1:])
    wait_text("暂停")
    shot("p02_item_actions")
    p = find("暂停", exact=True)
    mark = len(log_lines())
    tap(*p)
    t_pause = wait_until(lambda: not service_running(), 30)
    print(f"  暂停后下载服务停止用时：{t_pause:.1f}s")
    R["manga_pause_service_stop_s"] = round(t_pause, 1)
    R["manga_wakelock_after_pause"] = wakelock_held()
    n1 = pages_on_disk()
    time.sleep(10)
    n2 = pages_on_disk()
    reqs_after = [l for l in log_lines()[mark:] if "chapterId=8" in l and "REQ" in l]
    print(f"  暂停时磁盘页数 {n1}，10 秒后 {n2}；暂停后仍发出的页面请求 {len(reqs_after)} 个")
    R["manga_pages_at_pause"] = n1
    R["manga_pages_stable_after_pause"] = n1 == n2
    r = wait_row(lambda t: "已暂停" in t, 60)
    print("  暂停后显示：", r[0])
    R["manga_paused_text"] = r[0]
    shot("p03_series_paused")


# ---------------------------------------------------------------- 2. 重启应用后保持暂停
def step2():
    global attempt, btns, crash, done, f, first, kept, l, m, mark, n, n1, n2, p, page_reqs, part_at_drop, r, reqs_after, retry_reqs, s1, s2, sha, t, t0, t_np, t_pause, t_stall, x, y
    step("2. 杀掉应用重新打开，暂停项不应自动继续")
    shell("am force-stop " + PKG)
    time.sleep(2)
    shell(f"am start -W -n {PKG}/.ui.MainActivity", timeout=300)
    wait_text("书架")
    time.sleep(8)
    R["restart_service_running"] = service_running()
    R["restart_pages_unchanged"] = pages_on_disk() == n1
    tap_text("下载(", wait=30)
    wait_text("下载队列")
    r = wait_row(lambda t: "已暂停" in t, 60)
    print("  重启后队列显示：", r[0])
    R["restart_queue_text"] = r[0]
    R["restart_toggle_label"] = "全部继续" if find("全部继续", exact=True) else "?"
    shot("p04_queue_paused_after_restart")


# ---------------------------------------------------------------- 3. 继续：从已下载的页之后接着下
def step3():
    global attempt, btns, crash, done, f, first, kept, l, m, mark, n, n1, n2, p, page_reqs, part_at_drop, r, reqs_after, retry_reqs, s1, s2, sha, t, t0, t_np, t_pause, t_stall, x, y
    step("3. 全部继续：从断点接着下载")
    proxy("ok", 30_000_000)
    mark = len(log_lines())
    tap_text("全部继续", exact=True)
    assert wait_until(service_running, 30) is not None, "继续后服务未启动"
    done = wait_until(lambda: exists(f"{MANGA}/chapter.json"), 900, 3)
    assert done is not None, "漫画续传未完成"
    page_reqs = [int(m.group(1)) for l in log_lines()[mark:] for m in [re.search(r"chapterId=8&page=(\d+)", l)] if m]
    print(f"  续传请求了 {len(page_reqs)} 页，第一页序号 {min(page_reqs) if page_reqs else None}（暂停时已有 {n1} 页）")
    R["manga_resume_first_page"] = min(page_reqs) if page_reqs else None
    R["manga_resume_requests"] = len(page_reqs)
    R["manga_total_pages"] = pages_on_disk()
    wait_text("没有进行中的下载", 60)
    shot("p05_queue_empty")
    back()
    wait_text("书架")


# ---------------------------------------------------------------- 4. 小说：网络卡死时暂停
def step4():
    global attempt, btns, crash, done, f, first, kept, l, m, mark, n, n1, n2, p, page_reqs, part_at_drop, r, reqs_after, retry_reqs, s1, s2, sha, t, t0, t_np, t_pause, t_stall, x, y
    step("4. 小说：网络卡死时暂停是否立即生效")
    proxy("ok", 400_000)
    tap_text("书库", exact=True)
    wait_text("个书库")
    open_series("小说", "Big Novel")
    tap(*wait_row(lambda t: t.endswith("未下载"))[1:])
    r = wait_row(lambda t: "下载中" in t and mb_done(t) >= 1.0, 300)
    print("  进度显示：", r[0])
    R["novel_progress_text"] = r[0]
    shot("p06_novel_downloading")
    proxy("stall")
    time.sleep(4)
    s1 = size_of(f"{NOVEL}/book.epub.part")
    time.sleep(3)
    s2 = size_of(f"{NOVEL}/book.epub.part")
    R["stall_no_progress"] = s1 == s2
    r = row(lambda t: "下载中" in t)
    tap(*r[1:])
    wait_text("暂停")
    tap_text("暂停", exact=True)
    t0 = time.time()
    t_stall = wait_until(lambda: not service_running(), 90)
    print(f"  网络卡死时暂停，下载服务停止用时：{t_stall:.1f}s（读取超时 60s）")
    R["stall_pause_service_stop_s"] = round(t_stall, 1)
    R["stall_wakelock_after_pause"] = wakelock_held()
    kept = size_of(f"{NOVEL}/book.epub.part")
    R["novel_part_kept_bytes"] = kept
    proxy("ok", 400_000)
    r = wait_row(lambda t: "已暂停" in t, 60)
    print("  暂停后显示：", r[0])
    R["novel_paused_text"] = r[0]
    shot("p07_novel_paused")


# ---------------------------------------------------------------- 5. 继续 + 断网自动重试
def step5():
    global attempt, btns, crash, done, f, first, kept, l, m, mark, n, n1, n2, p, page_reqs, part_at_drop, r, reqs_after, retry_reqs, s1, s2, sha, t, t0, t_np, t_pause, t_stall, x, y
    step("5. 继续下载，中途断网，自动重试并续传")
    mark = len(log_lines())
    tap(*row(lambda t: "已暂停" in t)[1:])
    wait_text("继续")
    tap_text("继续", exact=True)
    assert wait_until(service_running, 30) is not None
    time.sleep(4)
    first = [l for l in log_lines()[mark:] if "chapterId=7" in l and "Download" in l]
    print("  继续后的下载请求：", first[:2])
    R["novel_resume_range_ok"] = any(f"range={kept}-" in l for l in first)
    wait_until(lambda: size_of(f"{NOVEL}/book.epub.part") > kept + 800_000, 120, 1)
    proxy("drop")
    r = wait_row(lambda t: "自动重试" in t, 120, 1)
    print("  断网后显示：", r[0])
    R["retry_text"] = r[0]
    shot("p08_auto_retry")
    part_at_drop = size_of(f"{NOVEL}/book.epub.part")
    mark = len(log_lines())
    proxy("ok", 30_000_000)
    done = wait_until(lambda: exists(f"{NOVEL}/chapter.json"), 300, 2)
    assert done is not None, "断网恢复后未能完成"
    retry_reqs = [l for l in log_lines()[mark:] if "chapterId=7" in l and "Download" in l]
    print("  恢复后的请求：", retry_reqs[:2], " 断网时已有", part_at_drop)
    R["retry_range_ok"] = any(re.search(r"range=(\d+)-", l) and int(re.search(r"range=(\d+)-", l).group(1)) >= part_at_drop - 600_000 for l in retry_reqs)
    subprocess.run([ADB, "pull", f"{NOVEL}/book.epub", "/tmp/pulled.epub"], capture_output=True)
    sha = lambda p: hashlib.sha256(open(p, "rb").read()).hexdigest()
    R["novel_sha_match"] = sha("/tmp/pulled.epub") == sha(ORIG_EPUB)
    r = wait_row(lambda t: "已下载" in t, 60)
    shot("p09_novel_done")


# ---------------------------------------------------------------- 6. 通知栏「全部暂停」/「全部继续」
def step6():
    global attempt, btns, crash, done, f, first, kept, l, m, mark, n, n1, n2, p, page_reqs, part_at_drop, r, reqs_after, retry_reqs, s1, s2, sha, t, t0, t_np, t_pause, t_stall, x, y
    step("6. 通知栏按钮")
    # 删除小说离线内容，重新下载
    shell(f"rm -rf {NOVEL}")
    back(); time.sleep(1)
    time.sleep(3)
    tap_text("Big Novel", exact=True)
    wait_text("全部下载")  # 系列页特有的按钮（列表页也有「共 N 个系列」，不能用来判断）
    proxy("ok", 100_000)  # 慢速模拟器上 UI 自动化很慢，下载需足够慢才能在完成前点到按钮
    tap(*wait_row(lambda t: t.endswith("未下载"))[1:])
    wait_row(lambda t: "下载中" in t and mb_done(t) >= 0.5, 300)
    shell("cmd statusbar expand-notifications")
    time.sleep(4)
    shot("p10_notification_downloading")
    t_np = None
    for attempt in range(3):
        # 通知每 2.5 秒刷新一次进度，慢速模拟器上偶尔会点空，最多重试 3 次
        p = find("全部暂停", exact=True)
        if p:
            tap(*p)
            t_np = wait_until(lambda: not service_running(), 15)
            if t_np is not None:
                break
        shell("cmd statusbar expand-notifications")
        time.sleep(3)
    R["notif_pause_attempts"] = attempt + 1
    R["notif_pause_service_stop_s"] = round(t_np, 1) if t_np is not None else None
    time.sleep(4)
    shell("cmd statusbar expand-notifications")
    time.sleep(4)
    R["notif_paused_shown"] = find("已暂停 1 项下载") is not None
    shot("p11_notification_paused")
    proxy("ok", 100_000)  # 放慢，留给第 7 步测试取消
    tap_text("全部继续", exact=True, wait=30)  # 精确匹配按钮，正文里也有这四个字
    R["notif_resume_service_started"] = wait_until(service_running, 30) is not None
    shell("cmd statusbar collapse")
    time.sleep(2)


# ---------------------------------------------------------------- 7. 取消会删除未完成部分
def step7():
    global attempt, btns, crash, done, f, first, kept, l, m, mark, n, n1, n2, p, page_reqs, part_at_drop, r, reqs_after, retry_reqs, s1, s2, sha, t, t0, t_np, t_pause, t_stall, x, y
    step("7. 取消下载")
    r = wait_row(lambda t: "下载中" in t, 120)
    tap(*r[1:])
    wait_text("取消下载")
    tap_text("取消下载", exact=True)
    wait_text("将删除已下载的未完成部分")
    # 确认对话框里的按钮也叫「取消下载」
    btns = [(x, y) for t, x, y, n in nodes() if t == "取消下载"]
    tap(*btns[-1])
    R["cancel_service_stopped"] = wait_until(lambda: not service_running(), 30) is not None
    time.sleep(3)
    R["cancel_partial_deleted"] = not exists(NOVEL)
    wait_row(lambda t: t.endswith("未下载"), 60)
    shot("p12_cancelled")


# ---------------------------------------------------------------- 收尾
def step8():
    global attempt, btns, crash, done, f, first, kept, l, m, mark, n, n1, n2, p, page_reqs, part_at_drop, r, reqs_after, retry_reqs, s1, s2, sha, t, t0, t_np, t_pause, t_stall, x, y
    crash = shell("logcat -d -b crash")
    R["crash_log"] = crash.strip()[:500]
    print("\n结果：")
    print(json.dumps(R, ensure_ascii=False, indent=1))
    with open("/tmp/e2e_pause_result.json", "w") as f:
        json.dump(R, f, ensure_ascii=False, indent=1)


for i, fn in enumerate([step0, step1, step2, step3, step4, step5, step6, step7, step8]):
    if i >= FROM or i == 8:
        fn()
        print("  当前结果：", json.dumps(R, ensure_ascii=False), flush=True)
