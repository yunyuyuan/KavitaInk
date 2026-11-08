#!/usr/bin/env python3
"""KavitaInk 1.2.0 新功能的模拟器测试 + 10 种语言截图巡检。

前提：
  - 先跑过 e2e.py all（已登录，本地有 Test Manga / Alice / 测试小说 / PDF Book）；
  - Kavita 测试服的「漫画」库里有 Spread Test、Crop Test、Many Chapters、Big Manga（见 README 的测试章节）；
  - 装的是可调试构建（gradle assembleRelease -PrelDebug=true），这样才能采集裁边耗时日志。
界面文字按键名从 values-zh 读取（脚本把应用语言固定为 zh-CN），文案改了不用改脚本。

用法：python3 e2e_v120.py [段名 ...]
  段名：fixtures statusbar orientation spread crop fonts batch idle langs（缺省 = 全部，idle 要 10 分钟）
结果写到 tools/shots/v120_results.json，截图在 tools/shots/v_*.png。
"""
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from adbui import *  # noqa: E402,F401
import translate as T  # noqa: E402

LIB = "/sdcard/Android/data/net.yunyuyuan.kavitaink/files/index"
FONTS_DIR = f"/data/data/{PKG}/files/fonts"
RESULTS = os.path.join(SHOTS, "v120_results.json")
R = json.load(open(RESULTS)) if os.path.exists(RESULTS) else {}

ZH = {}
for _items in T.load_dir("values-zh").values():
    for _kind, _name, _v in _items:
        ZH[_name] = _v


def Z(key, *args):
    """中文文案；带占位符时按参数替换"""
    v = ZH[key]
    if isinstance(v, dict):
        v = v.get("other")

    def rep(m):
        i = int(m.group(1) or 1) - 1
        return str(args[i]) if i < len(args) else ""
    return re.sub(r"%(?:(\d+)\$)?[sd]", rep, v).replace("%%", "%")


def pre(key):
    """文案在第一个占位符之前的部分（前缀匹配用）"""
    v = ZH[key]
    v = v if isinstance(v, str) else v["other"]
    return v.split("%")[0]


def record(k, v):
    R[k] = v
    print(f"  [{k}] {v}")
    os.makedirs(SHOTS, exist_ok=True)
    json.dump(R, open(RESULTS, "w"), ensure_ascii=False, indent=1)


def am(cls, extras="", wait=4):
    out = shell(f"am start -W -n {PKG}/{cls} {extras}")
    if "Error" in out:
        raise RuntimeError(out)
    time.sleep(wait)


def stop():
    stop_app()


def back():
    key(4)
    time.sleep(1)


def menu():
    key(82)
    time.sleep(3)


def page_down(n=1, gap=2.5):
    for _ in range(n):
        key(93)
        time.sleep(gap)


def png_size(name):
    from PIL import Image
    return Image.open(f"{SHOTS}/{name}.png").size


def shot_orient(name):
    shot(name)
    w, h = png_size(name)
    return "landscape" if w > h else "portrait"


def texts_now():
    return texts()


def wait_any(cands, wait=60):
    end = time.time() + wait
    while time.time() < end:
        t = texts()
        for c in cands:
            if any(c in x for x in t):
                return c
        time.sleep(2)
    return None


def tap_prefix(prefix, wait=30):
    """点击以 prefix 开头的控件（菜单按钮「双页：关闭」之类）"""
    end = time.time() + wait
    while time.time() < end:
        for t, x, y, _ in nodes():
            if t.startswith(prefix):
                tap(x, y)
                return t
        time.sleep(2)
    raise RuntimeError(f"找不到以「{prefix}」开头的控件：{texts()[:30]}")


def label_of(prefix):
    for t in texts():
        if t.startswith(prefix):
            return t
    return None


# ---------- 本地内容 ----------

def local_chapters(series=None):
    out = shell(f"find {LIB} -name chapter.json 2>/dev/null")
    res = []
    for p in out.split():
        try:
            j = json.loads(shell(f"cat {p}"))
        except Exception:
            continue
        j["_dir"] = os.path.dirname(p).replace("/sdcard/", "/storage/emulated/0/")
        if series is None or series in j.get("seriesName", ""):
            res.append(j)
    res.sort(key=lambda j: (j.get("volNum", 0), j.get("sortOrder", 0), j["_dir"]))
    return res


def open_reader(series, idx=0, wait=8):
    ch = local_chapters(series)
    if not ch:
        raise RuntimeError(f"本地没有 {series}")
    c = ch[idx]
    cls = ".reader.EpubReaderActivity" if c.get("format") == 3 else ".reader.ImageReaderActivity"
    stop()
    am(cls, f"--es dir '{c['_dir']}'", wait=wait)
    return c


def open_series_local(series):
    c = local_chapters(series)[0]
    stop()
    am(".ui.SeriesActivity", f"--es sk {c['serverKey']} --ei sid {c['seriesId']} --es name '{series}' "
                            f"--ei format {c.get('seriesFormat', c.get('format', 0))} --ez online false")


def series_counts():
    """系列页顶部「共 N 项 · 已下载 M」"""
    for t in texts():
        m = re.match(r"共 (\d+) 项 · 已下载 (\d+)", t)
        if m:
            return int(m.group(1)), int(m.group(2))
    return None


def open_series_online(lib, series):
    stop()
    am(".ui.LibrariesActivity")
    wait_text(lib)
    tap_text(lib, exact=True)
    wait_text(series)
    tap_text(series, exact=True)
    wait_text("共 ", 120)
    time.sleep(3)


def download_all(lib, series, timeout=1200):
    """在线打开系列页，点「全部下载」，等全部下载完"""
    have = local_chapters(series)
    open_series_online(lib, series)
    n = series_counts()
    if n and n[0] == n[1] and n[0] > 0:
        print(f"  {series}: 已全部下载（{n[1]}）")
        return n
    tap_text(Z("series_download_all"), exact=True)
    wait_text(pre("series_download_all_msg")[:3], 30)
    tap_text(Z("kfonts_download"), exact=True)  # 确认按钮「下载」
    end = time.time() + timeout
    last = None
    while time.time() < end:
        n = series_counts()
        if n != last:
            print(f"  {series}: {n}")
            last = n
        if n and n[0] == n[1] and n[0] > 0:
            return n
        time.sleep(10)
    raise RuntimeError(f"下载超时：{series} {last}（本地原有 {len(have)} 项）")


# ---------- 各段测试 ----------

def sec_login():
    """（重新）登录测试服：设置被清空时用"""
    stop()
    grant_storage()
    am(".ui.LoginActivity", wait=5)
    wait_text(pre("login_title") if "login_title" in ZH else "Kavita", 60)
    fields = [(x, y) for t, x, y, n in nodes() if n.get("class") == "android.widget.EditText"]
    assert len(fields) >= 3, texts()
    user = os.environ.get("KAVITA_USER", "admin")
    pw = os.environ.get("KAVITA_PASS", "")
    assert fill(fields[0], "http://10.0.2.2:5000", lambda v: v in texts())
    assert fill(fields[1], user, lambda v: v in texts())
    fill(fields[2], pw)
    tap_text(Z("login_connect"), exact=True)
    wait_text(pre("libs_count")[:2] if "libs_count" in ZH else "书库", 300)
    record("login", texts()[:6])


def sec_fixtures():
    print("== 准备：下载测试漫画")
    for s in ["Spread Test", "Crop Test", "Big Manga"]:
        record(f"fixture_{s}", download_all("漫画", s))


def sec_statusbar():
    print("== F8 状态栏")
    # 新装默认：小说开（页码 + 时间 + 电量），漫画关
    open_reader("Alice", 0, wait=15)
    shot("v_sb_novel_default")
    menu()
    shot("v_sb_novel_menu")
    tap_text(Z("reader_status_bar"), exact=True)
    time.sleep(3)
    shot("v_sb_dialog")
    tap_prefix(Z("sb_item_percent_book"))
    time.sleep(1)
    tap_prefix(Z("sb_item_chapter"))
    time.sleep(1)
    tap_prefix(Z("sb_at_top"))
    time.sleep(2)
    shot("v_sb_dialog_changed")
    back()  # 关对话框
    time.sleep(2)
    back()  # 关菜单
    time.sleep(6)
    shot("v_sb_novel_top")
    page_down(2, 6)
    shot("v_sb_novel_top_p3")
    # 漫画：默认关闭 → 打开
    open_reader("Test Manga", 0)
    shot("v_sb_manga_off")
    menu()
    tap_text(Z("reader_status_bar"), exact=True)
    time.sleep(3)
    tap_prefix(Z("sb_show"))
    time.sleep(2)
    back()
    time.sleep(2)
    back()
    time.sleep(4)
    shot("v_sb_manga_on")
    record("statusbar", "截图：v_sb_*")


def sec_orientation():
    print("== F4 屏幕方向")
    shell("settings put system accelerometer_rotation 0; settings put system user_rotation 0")
    stop()
    am(".ui.SettingsActivity")
    tap_text(Z("set_orientation"), exact=True)
    time.sleep(3)
    tap_text(Z("orient_landscape"), exact=True)
    time.sleep(5)
    res = {"settings": shot_orient("v_or_settings_land")}
    stop()
    am(".ui.MainActivity")
    res["shelf"] = shot_orient("v_or_shelf_land")
    open_series_local("Test Manga")
    res["series"] = shot_orient("v_or_series_land")
    open_reader("Test Manga", 0)
    res["image_reader"] = shot_orient("v_or_manga_land")
    open_reader("Alice", 0, wait=15)
    res["epub_reader"] = shot_orient("v_or_epub_land")
    # 锁定后转动设备：系统转到竖屏 / 反向，应用仍保持横屏
    shell("settings put system user_rotation 2")
    time.sleep(5)
    res["locked_after_rotate"] = shot_orient("v_or_locked_rotate")
    shell("settings put system user_rotation 0")
    time.sleep(3)
    # 阅读菜单快捷项：横屏 → 跟随系统（系统是竖屏）；漫画页码不变
    open_reader("Spread Test", 0)
    page_down(3)
    menu()
    before = texts()
    shot("v_or_menu_before")
    tap_prefix(pre("reader_orientation"))  # 横屏 → 跟随系统
    time.sleep(6)
    after_orient = shot_orient("v_or_after_cycle")
    after = texts()
    info = lambda t: [x for x in t if "/" in x and re.search(r"\d+\s*/\s*\d+", x)]
    res["cycle_to_system"] = after_orient
    res["page_before"] = info(before)[:2]
    res["page_after"] = info(after)[:2]
    res["page_kept"] = bool(info(before)) and info(before)[:1] == info(after)[:1]
    back()
    # 竖屏锁定：系统转到横屏，应用仍竖屏
    stop()
    am(".ui.SettingsActivity")
    tap_text(Z("set_orientation"), exact=True)
    time.sleep(3)
    tap_text(Z("orient_portrait"), exact=True)
    time.sleep(3)
    shell("settings put system user_rotation 1")
    time.sleep(5)
    res["portrait_locked_when_system_land"] = shot_orient("v_or_portrait_locked")
    # 跟随系统：先选「跟随系统」，再把系统转到横屏 → 应用横屏（书架、设置、两种阅读器）
    # 注意：在竖屏锁定期间改 user_rotation 再切跟随系统，Android 9 会把 user_rotation 自己改回 0，
    # 这是系统行为，所以这里按真实使用顺序：先选跟随系统，再转动设备。
    shell("settings put system user_rotation 0")
    time.sleep(3)
    tap_text(Z("set_orientation"), exact=True)
    time.sleep(3)
    tap_text(Z("orient_system"), exact=True)
    time.sleep(4)
    shell("settings put system user_rotation 1")
    time.sleep(6)
    res["system_follows_land"] = shot_orient("v_or_follow_system")
    stop()
    am(".ui.MainActivity", wait=6)
    res["system_follows_shelf"] = shot_orient("v_or_follow_shelf")
    open_reader("Spread Test", 0)
    res["system_follows_manga"] = shot_orient("v_or_follow_manga")
    open_reader("Alice", 0, wait=15)
    res["system_follows_epub"] = shot_orient("v_or_follow_epub")
    shell("settings put system user_rotation 0")
    time.sleep(6)
    res["system_follows_back_portrait"] = shot_orient("v_or_follow_back")
    record("orientation", res)


def spread_dialog_set(mode_label, **kw):
    """在漫画菜单打开「双页显示」对话框，把双页模式切到 mode_label"""
    menu()
    tap_prefix(pre("img_spread"))
    time.sleep(3)
    for _ in range(3):
        cur = label_of(Z("spread_mode"))
        if cur and cur.endswith(mode_label):
            break
        tap_prefix(Z("spread_mode"))
        time.sleep(2)
    for k, target in kw.items():
        for _ in range(20):
            cur = label_of(Z(k))
            if cur is None or cur.endswith(target):
                break
            tap_prefix(Z(k))
            time.sleep(1.5)
    shot("v_sp_dialog")
    back()
    time.sleep(2)
    back()
    time.sleep(5)


def jump(n):
    """漫画菜单 → 跳页到第 n 页"""
    menu()
    tap_text(Z("img_jump"), exact=True)
    time.sleep(3)
    shell(f"input text {n}")
    time.sleep(1)
    tap_text(Z("img_jump_go"), exact=True)
    time.sleep(5)


def set_direction(value_key):
    """把漫画阅读方向设成 value_key（dir_ltr / dir_rtl）"""
    menu()
    for _ in range(3):
        cur = label_of(pre("img_direction"))
        if cur and cur.endswith(Z(value_key)):
            break
        tap_prefix(pre("img_direction"))
        time.sleep(3)
    back()
    time.sleep(3)


def sec_spread():
    print("== F3 双页")
    res = {}
    open_reader("Spread Test", 0)
    set_direction("dir_ltr")
    spread_dialog_set(Z("spread_always"))
    jump(1)
    shot("v_sp_1_cover")             # 首页单独
    for i, name in enumerate(["v_sp_2_3", "v_sp_4_5", "v_sp_6_wide", "v_sp_7_8", "v_sp_9_10"]):
        page_down(1, 4)
        shot(name)
    menu()
    res["menu_at_end"] = [t for t in texts() if re.search(r"\d+\s*[–-]\s*\d+", t)][:3]
    shot("v_sp_menu_end")
    # 双页时「缩放」显示为灰色
    zoom = [n for t, x, y, n in nodes() if t.startswith(pre("img_zoom"))]
    res["zoom_disabled"] = bool(zoom) and zoom[0].get("enabled") == "false"
    # 错开一页
    tap_prefix(Z("spread_shift"))
    time.sleep(5)
    back()
    time.sleep(3)
    shot("v_sp_shift_on")
    # 跳页到 8：显示包含第 8 页的那一屏（错开后横图后面那段是 7 | 8-9 | 10）
    jump(8)
    shot("v_sp_jump8")
    menu()
    res["jump8_menu"] = [t for t in texts() if re.search(r"\d+\s*[–-]\s*\d+|/ ?10", t)][:3]
    tap_prefix(Z("spread_shift"))  # 关掉错开
    time.sleep(4)
    # 阅读方向改为从右到左：页序跟随，左右交换
    tap_prefix(pre("img_direction"))
    time.sleep(4)
    back()
    time.sleep(3)
    shot("v_sp_rtl")
    menu()
    tap_prefix(pre("img_direction"))  # 改回
    time.sleep(3)
    back()
    time.sleep(2)
    # 中缝：间距加大、分隔线开、粗细 6px、深灰
    spread_dialog_set(Z("spread_always"), spread_gap=Z("px_n", 32), spread_divider=Z("on"),
                      divider_width=Z("px_n", 6), divider_color=Z("color_dark_gray"))
    shot("v_sp_divider")
    spread_dialog_set(Z("spread_always"), spread_gap=Z("px_n", 8), spread_divider=Z("off"))
    # PDF 双页
    open_reader("PDF Book", 0, wait=10)
    page_down(1, 6)
    shot("v_sp_pdf")
    # 横屏时自动：竖屏单页，转横屏后双页且位置不变
    open_reader("Spread Test", 0)
    spread_dialog_set(Z("spread_auto"))
    page_down(3, 4)
    shot("v_sp_auto_portrait")
    shell("settings put system user_rotation 1")
    time.sleep(8)
    shot("v_sp_auto_landscape")
    shell("settings put system user_rotation 0")
    time.sleep(6)
    shot("v_sp_auto_back_portrait")
    record("spread", res)


def meminfo():
    out = shell(f"dumpsys meminfo {PKG}")
    m = re.search(r"TOTAL\s+(\d+)", out)
    return int(m.group(1)) if m else None


def sec_spread_memory():
    print("== F3 双页：80 页大漫画连续翻页内存")
    open_reader("Big Manga", 0, wait=10)
    spread_dialog_set(Z("spread_always"))
    mem = [meminfo()]
    for i in range(41):
        page_down(1, 3)
        if i % 10 == 9:
            mem.append(meminfo())
            print("  meminfo KB", mem[-1])
    shot("v_sp_big_end")
    crashed = "FATAL" in subprocess.run([ADB, "logcat", "-d", "-s", "AndroidRuntime:E"], capture_output=True, text=True).stdout
    record("spread_memory_kb", {"samples": mem, "crash": crashed})
    open_reader("Big Manga", 0, wait=6)
    spread_dialog_set(Z("spread_off"))


def sec_crop():
    print("== F7 裁白边")
    sh("logcat", "-c")
    open_reader("Crop Test", 0)
    jump(1)                          # 阅读器会回到上次的位置，先回到第 1 页
    time.sleep(8)                    # 冷启动时第一次解码较慢，等画完再截图
    shot("v_cr_off_p1")
    menu()
    tap_prefix(pre("img_crop"))  # 关 → 裁白边
    time.sleep(5)
    back()
    time.sleep(4)
    for i in range(4):
        shot(f"v_cr_white_p{i + 1}")
        page_down(1, 4)
    open_reader("Crop Test", 0)
    jump(4)
    menu()
    tap_prefix(pre("img_crop"))  # 裁白边 → 裁白边和黑边
    time.sleep(5)
    back()
    time.sleep(4)
    shot("v_cr_black_p4")
    # 与双页同时开启
    open_reader("Spread Test", 0)
    spread_dialog_set(Z("spread_always"))
    page_down(1, 4)
    shot("v_cr_spread")
    spread_dialog_set(Z("spread_off"))
    # PDF：渲染出来的页面同样裁边
    open_reader("PDF Book", 0, wait=10)
    for i in range(3):
        page_down(1, 6)
    shot("v_cr_pdf")
    menu()
    tap_prefix(pre("img_crop"))  # → 关
    time.sleep(3)
    back()
    log = subprocess.run([ADB, "logcat", "-d", "-s", "KavitaInk:D"], capture_output=True, text=True).stdout
    def stats(v):
        return {"n": len(v), "max": round(max(v), 1) if v else None,
                "avg": round(sum(v) / len(v), 1) if v else None, "all": [round(x, 1) for x in v[:30]]}
    img = [l for l in log.splitlines() if "crop page=" in l and "read=" in l]
    pdf = [l for l in log.splitlines() if "crop page=" in l and "read=" not in l]
    num = lambda l, k: float(re.search(k + r"=([\d.]+)", l).group(1))
    tag = os.environ.get("CROP_TAG", "")
    record("crop_ms" + tag, {
        "image_extra": stats([num(l, "ms") for l in img]),
        "image_read": stats([num(l, "read") for l in img]),
        "image_detect": stats([num(l, "detect") for l in img]),
        "image_copy": stats([num(l, "copy") for l in img]),
        "redecode": sum("redecode=true" in l for l in img),
        "pdf_extra": stats([num(l, "ms") for l in pdf]),
        "decode_no_crop": stats([num(l, "ms") for l in log.splitlines() if "decode page=" in l and "crop=false" in l]),
        "decode_with_crop": stats([num(l, "ms") for l in log.splitlines() if "decode page=" in l and "crop=true" in l]),
        "lines": [l.split("KavitaInk: ")[-1] for l in img + pdf][:20],
    })


def fonts_du():
    out = shell(f"du -sk {FONTS_DIR} 2>/dev/null")
    return int(out.split()[0]) if out.strip() else 0


def tap_font(name, wait=60):
    """字体行的名字：选中时前面带「✓ 」"""
    end = time.time() + wait
    while time.time() < end:
        for t in (name, "✓ " + name):
            if find(t, exact=True):
                tap_text(t, exact=True)
                return
        time.sleep(2)
    raise RuntimeError(f"找不到字体：{name}；当前界面：{texts()[:30]}")


def kavita_font(name, timeout=900):
    stop()
    am(".ui.KavitaFontsActivity")
    tap_font(name, 120)
    wait_text(Z("kfonts_download"), 120)
    shot("v_ft_confirm_" + re.sub(r"\W", "", name))
    tap_text(Z("kfonts_download"), exact=True)
    done = wait_any([pre("kfonts_done"), pre("kfonts_failed")], timeout)
    return done == pre("kfonts_done")


def use_font(name):
    stop()
    am(".ui.FontsActivity")
    tap_font(name, 60)
    time.sleep(3)
    tap_text(Z("fonts_use"), exact=True)
    time.sleep(2)


def sec_fonts():
    print("== F2 字体")
    # 从干净状态开始：删掉之前下载 / 导入的字体（设置里仍指向已删除的字体 → 阅读器应回退到书籍默认字体）
    stop()
    shell(f"rm -rf {FONTS_DIR}")
    open_reader("Alice", 0, wait=20)
    shot("v_ft_font_missing_fallback")
    res = {"du_before": fonts_du()}
    res["merriweather"] = kavita_font("Merriweather")
    shot("v_ft_merri_done")
    stop()
    am(".ui.FontsActivity")
    shot("v_ft_list1")
    use_font("Merriweather")
    open_reader("Alice", 0, wait=20)
    shot("v_ft_alice_merri")
    # 中文字体（用户上传，25 MB）
    t0 = time.time()
    res["wenkai"] = kavita_font("LXGWWen Kai", 1800)
    res["wenkai_download_s"] = round(time.time() - t0, 1)
    res["du_with_wenkai"] = fonts_du()
    use_font("LXGWWen Kai")
    open_reader("测试小说", 0, wait=30)
    shot("v_ft_novel_wenkai")
    # 断网后照常可用
    shell("iptables -I OUTPUT -d 10.0.2.2 -j REJECT")
    open_reader("测试小说", 0, wait=30)
    shot("v_ft_novel_wenkai_offline")
    shell("iptables -D OUTPUT -d 10.0.2.2 -j REJECT")
    # 本机导入 ttf（系统文件选择器）
    sh("push", "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf", "/sdcard/Download/DejaVuSansMono.ttf")
    shell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/DejaVuSansMono.ttf")
    stop()
    am(".ui.FontsActivity")
    tap_text(Z("fonts_import"), exact=True)
    time.sleep(8)
    shot("v_ft_picker")
    if not find("DejaVuSansMono.ttf"):
        # 打开侧栏选「下载」
        for t in ["Show roots", "显示根目录"]:
            n = [(x, y) for tt, x, y, nd in nodes() if nd.get("content-desc") == t]
            if n:
                tap(*n[0])
                time.sleep(3)
                break
        for t in ["Downloads", "下载"]:
            if find(t, exact=True):
                tap_text(t, exact=True, wait=5)
                break
        time.sleep(5)
    tap_text("DejaVuSansMono.ttf", wait=30)
    res["import"] = wait_any([pre("fonts_imported"), pre("fonts_import_failed")], 60)
    shot("v_ft_imported")
    open_reader("Alice", 0, wait=20)
    shot("v_ft_alice_mono")
    # 删除中文字体，释放空间
    stop()
    am(".ui.FontsActivity")
    tap_font("LXGWWen Kai", 30)
    time.sleep(3)
    tap_text(Z("delete"), exact=True)
    time.sleep(3)
    tap_text(Z("delete"), exact=True)  # 确认框
    time.sleep(4)
    res["du_after_delete"] = fonts_du()
    shot("v_ft_after_delete")
    use_font("Merriweather")
    record("fonts", res)


def chapter_rows():
    """系列页列表里的章节行（状态栏之下、翻页按钮之上，带数字的标题）"""
    out = []
    for t, x, y, n in nodes():
        if not t or t.startswith("共 ") or n.get("clickable") == "true" and n.get("class", "").endswith("Button"):
            continue
        if 250 < y < 1250 and re.search(r"\d", t) and not re.search(r"页 ·|KB|MB", t):
            out.append((x, y))
    return out


def sel_title():
    for t in texts():
        if t.startswith(pre("sel_count")):
            return t
    return None


def choose(op):
    tap_text(Z("sel_choose"), exact=True)
    time.sleep(3)
    t0 = time.time()
    tap_text(Z(op), exact=True)
    time.sleep(1)
    return t0


def sec_batch():
    print("== F6 批量操作")
    res = {}
    open_series_online("漫画", "Many Chapters")
    res["total"] = series_counts()
    tap_text(Z("sel_select"), exact=True)
    time.sleep(3)
    res["enter"] = sel_title()
    t0 = choose("sel_all")
    wait_text(Z("sel_count", 120), 60)
    res["select_all_s"] = round(time.time() - t0, 2)
    res["all"] = sel_title()
    shot("v_bt_all")
    tap_text(Z("list_next_page"), exact=True, wait=5)  # 翻到下一页，未显示的项也应已选中
    time.sleep(3)
    shot("v_bt_all_page2")
    tap_text(Z("list_prev_page"), exact=True, wait=5)
    time.sleep(2)
    choose("sel_invert")
    time.sleep(2)
    res["invert"] = sel_title()
    tap_text(Z("cancel"), exact=True)
    time.sleep(3)
    # 长按一行进入多选（该行被选中）
    rows = chapter_rows()
    if rows:
        x, y = rows[0]
        shell(f"input swipe {x} {y} {x} {y} 1500")
        time.sleep(3)
        res["longpress"] = sel_title()
    choose("sel_all")
    time.sleep(2)
    tap_prefix(pre("sel_download_n"))
    time.sleep(5)
    res["enqueued_toast"] = [t for t in texts() if pre("series_enqueued")[:4] in t][:1]
    end = time.time() + 1500
    last = None
    while time.time() < end:
        n = series_counts()
        if n != last:
            print("  ", n)
            last = n
        if n and n[1] == n[0]:
            break
        if n is None and find(Z("series_already_queued")):  # 全都已下载：状态行显示提示而不是统计
            res["all_already_downloaded"] = True
            break
        time.sleep(15)
    res["after_download"] = series_counts()
    # 删除所选：手动勾 3 行
    tap_text(Z("sel_select"), exact=True)
    time.sleep(3)
    rows = [(x, y) for t, x, y, n in nodes() if t.startswith("☐")]
    for x, y in rows[:3]:
        tap(x, y)
        time.sleep(2)
    res["picked3"] = sel_title()
    tap_prefix(pre("sel_delete_n"))
    time.sleep(3)
    res["delete_confirm"] = [t for t in texts() if pre("series_delete_msg")[:4] in t][:1]
    shot("v_bt_delete_confirm")
    tap_text(Z("delete"), exact=True)
    time.sleep(6)
    res["after_delete"] = series_counts()
    # 只选未下载的 → 下载回来
    tap_text(Z("sel_select"), exact=True)
    time.sleep(3)
    choose("sel_not_downloaded")
    time.sleep(2)
    res["not_downloaded_sel"] = sel_title()
    choose("sel_downloaded")
    time.sleep(2)
    res["downloaded_sel"] = sel_title()
    choose("sel_not_downloaded")
    tap_prefix(pre("sel_download_n"))
    time.sleep(5)
    res["redownload_status"] = [t for t in texts() if pre("series_enqueued")[:4] in t][:1]
    # 3 话都只有十几 KB，几秒就下完；状态行此时显示的是提示语，重新打开系列页读统计
    time.sleep(45)
    open_series_online("漫画", "Many Chapters")
    res["after_redownload"] = series_counts()
    print("  series part:", json.dumps(res, ensure_ascii=False))
    # 书架：多选删除 Many Chapters + Crop Test
    stop()
    am(".ui.MainActivity")
    shelf_before = [t for t in texts() if t.startswith("共 ")]
    tap_text(Z("sel_select"), exact=True)
    time.sleep(3)
    for name in ["Many Chapters", "Crop Test"]:
        # 书架一屏放不下全部作品：当前屏没有就往后翻（多选状态跨页保留）
        for _ in range(4):
            if find(name):
                tap_text(name)
                break
            tap_text(Z("list_next_page"), exact=True, wait=5)
            time.sleep(3)
        time.sleep(2)
    res["shelf_picked"] = sel_title()
    tap_prefix(pre("sel_delete_n"))
    time.sleep(3)
    res["shelf_confirm"] = [t for t in texts() if pre("shelf_delete_msg")[:4] in t][:1]
    shot("v_bt_shelf_confirm")
    tap_text(Z("delete"), exact=True)
    time.sleep(6)
    res["shelf_before"] = shelf_before
    res["shelf_after"] = [t for t in texts() if t.startswith("共 ")]
    shot("v_bt_shelf_after")
    record("batch", res)


def sec_idle():
    print("== F8 停在同一页 10 分钟：无闹钟、无刷新")
    open_reader("Alice", 0, wait=20)
    def frames():
        out = shell(f"dumpsys gfxinfo {PKG}")
        m = re.search(r"Total frames rendered: (\d+)", out)
        return int(m.group(1)) if m else None
    f0 = frames()
    shot("v_idle_start")
    time.sleep(600)
    f1 = frames()
    shot("v_idle_end")
    alarms = [l.strip() for l in shell("dumpsys alarm").splitlines() if PKG in l]
    wakelocks = [l.strip() for l in shell("dumpsys power").splitlines() if PKG in l and "WAKE_LOCK" in l.upper()]
    record("idle", {"frames_start": f0, "frames_end": f1, "alarms": alarms[:5], "wakelocks": wakelocks[:5]})


# 默认只巡检中文和英文；要看全部 10 种语言时设环境变量 V120_ALL_LANGS=1
LANGS = ["en", "zh-CN", "zh-TW", "ja", "ko", "es", "fr", "de", "ru", "pt-BR"] if os.environ.get("V120_ALL_LANGS") else ["en", "zh-CN"]


def sec_langs():
    print("== 10 种语言截图巡检")
    for tag in LANGS:
        set_app_lang(tag)
        t = tag.replace("-", "")
        am(".ui.MainActivity", wait=5)
        shot(f"v_lang_{t}_1shelf")
        open_series_local("Test Manga")
        shot(f"v_lang_{t}_2series")
        stop()
        am(".ui.DownloadsActivity", wait=4)
        shot(f"v_lang_{t}_3downloads")
        stop()
        am(".ui.SettingsActivity", wait=4)
        shot(f"v_lang_{t}_4settings")
        open_reader("Spread Test", 0)
        menu()
        shot(f"v_lang_{t}_5reader_menu")
        open_reader("Alice", 0, wait=15)
        menu()
        shot(f"v_lang_{t}_6novel_menu")
        stop()
        am(".ui.FontsActivity", wait=4)
        shot(f"v_lang_{t}_7fonts")
    set_app_lang("zh-CN")
    record("langs", LANGS)


def emu(*args):
    """模拟器控制台命令，例如 emu("rotate")：把虚拟传感器逆时针转 90°，相当于转动设备"""
    return subprocess.run([ADB, "emu", *args], capture_output=True, text=True, timeout=30).stdout.strip()


def choose_orientation(value_key):
    stop()
    am(".ui.SettingsActivity")
    tap_text(Z("set_orientation"), exact=True)
    time.sleep(3)
    tap_text(Z(value_key), exact=True)
    time.sleep(5)


def sec_orient_sensor():
    """「跟随系统」按真实使用方式测：打开系统自动旋转，转动设备（模拟器传感器）。
    用 user_rotation 模拟在方向锁定下测不准：Android 9 会在任何应用释放强制方向时把 user_rotation 改成当前方向。"""
    print("== F4 跟随系统（自动旋转 + 转动设备）")
    shell("settings put system user_rotation 0; settings put system accelerometer_rotation 1")
    res = {}
    choose_orientation("orient_system")
    res["start"] = shot_orient("v_os_start")
    res["emu_rotate"] = emu("rotate")              # 设备转 90°
    time.sleep(8)
    res["settings_rotated"] = shot_orient("v_os_settings")
    stop()
    am(".ui.MainActivity", wait=8)
    res["shelf_rotated"] = shot_orient("v_os_shelf")
    open_reader("Spread Test", 0)
    res["manga_rotated"] = shot_orient("v_os_manga")
    open_reader("Alice", 0, wait=15)
    res["epub_rotated"] = shot_orient("v_os_epub")
    # 设备仍横着，改为锁定竖屏：应用保持竖屏
    choose_orientation("orient_portrait")
    res["portrait_lock_device_rotated"] = shot_orient("v_os_portrait_lock")
    open_reader("Spread Test", 0)
    res["portrait_lock_manga"] = shot_orient("v_os_portrait_lock_manga")
    # 恢复：转回原位、改回跟随系统、关自动旋转
    for _ in range(3):
        emu("rotate")
        time.sleep(3)
    choose_orientation("orient_system")
    res["restored"] = shot_orient("v_os_restored")
    shell("settings put system accelerometer_rotation 0; settings put system user_rotation 0")
    record("orient_sensor", res)


SECTIONS = {
    "login": sec_login, "fixtures": sec_fixtures, "statusbar": sec_statusbar, "orientation": sec_orientation,
    "orient_sensor": sec_orient_sensor,
    "spread": sec_spread, "spreadmem": sec_spread_memory, "crop": sec_crop, "fonts": sec_fonts,
    "batch": sec_batch, "idle": sec_idle, "langs": sec_langs,
}

if __name__ == "__main__":
    names = sys.argv[1:] or ["fixtures", "statusbar", "orientation", "orient_sensor", "spread", "spreadmem", "crop", "fonts", "batch", "idle", "langs"]
    sh("root", timeout=60)
    time.sleep(2)
    shell("settings put global hide_error_dialogs 1")
    set_app_lang("zh-CN")
    failed = []
    for n in names:
        try:
            SECTIONS[n]()
        except Exception as e:  # 一段失败不影响后面的段
            import traceback
            traceback.print_exc()
            shot(f"v_fail_{n}")
            record(f"error_{n}", repr(e)[:500])
            failed.append(n)
    crash = subprocess.run([ADB, "logcat", "-d", "-b", "crash"], capture_output=True, text=True).stdout
    record("crash_log", [l for l in crash.splitlines() if PKG in l or "FATAL" in l][:20])
    print("失败的段：", failed)
    print("EXIT", 1 if failed else 0)
