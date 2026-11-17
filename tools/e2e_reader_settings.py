"""阅读菜单（顶部按钮栏 + 分页）和从阅读器进入设置页（Android 10 模拟器，中文界面）
用法：ANDROID_SERIAL=emulator-5556 KAVITA_PASS=xxx python3 tools/e2e_reader_settings.py [apk] [manga] [novel] [menu]
需要已登录测试服（缺少「Split Test」时会自动下载）；「测试小说」需已下载。
覆盖：
  1. 菜单布局：顶部栏「‹ 返回 / 书名 / 设置」，底部三个分页标签，各分页的按钮正确，记住上次的分页，
     每个按钮的下边框都画出来了（按截图像素检查）
  2. 漫画：设置页打开「拆分跨页」→ 返回后当前宽页立即拆成半页（阅读区域尺寸不变，走重新排版）
  3. 漫画：设置页打开状态栏 → 返回后底部出现白底黑字的状态栏，画面按新尺寸重新排版
  4. 小说：设置页把字号调到 150% → 返回后立即重新分页（本节页数变多）、仍在原来的节，整页按新字号重绘
  5. 语言：阅读器 → 设置 → English → 返回后阅读器是英文，进度不变；顶部「‹ Back」退出阅读器；
     书架 → 设置 → 简体中文 → 返回后书架是中文
"""
import os
import re
import sys
import time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from adbui import *  # noqa: E402,F401
import e2e_v120 as E  # noqa: E402
import e2e_split as S  # noqa: E402
from e2e_split import set_prefs, look, half, whole_wide, expect, rec, FAILS  # noqa: E402

# 结果写到自己的文件里（rec / expect 用的是 e2e_split 模块里的全局变量）
S.OUT = os.path.join(SHOTS, "reader_settings_results.json")
S.RES = {}
APK = sys.argv[1] if len(sys.argv) > 1 else "/home/ubuntu/KavitaInk/app/build/outputs/apk/release/app-release.apk"
W, H = 1072, 1448
EN = {}
for _items in E.T.load_dir("values").values():
    for _kind, _name, _v in _items:
        EN[_name] = _v


def center():
    tap(W // 2, H // 2)


def has(s, exact=False):
    return any((t == s) if exact else (s in t) for t in texts())


def bounds(n):
    return tuple(map(int, re.findall(r"\d+", n.get("bounds", ""))))


def open_menu(settings_label=None, wait=90):
    """点屏幕中间呼出菜单，直到顶部栏的「设置」按钮出现"""
    lab = settings_label or E.Z("set_title")
    end = time.time() + wait
    while time.time() < end:
        center()
        t0 = time.time()
        while time.time() - t0 < 20:
            if has(lab, exact=True):
                return
            time.sleep(2)
    raise RuntimeError(f"菜单没有出现：{texts()[:30]}")


def settings_item(title, after=None, pages=5):
    """在设置页找到标题为 title 的一项并点击；after = 先经过的分组标题（同名项，例如小说 / 漫画都有「状态栏」）"""
    seen = after is None
    for _ in range(pages):
        for t, x, y, n in list(nodes()):
            if after is not None and t == after:
                seen = True
                continue
            if seen and t == title:
                tap(x, y)
                return
        # 设置项里也有「音量- 下一页」之类的文字，翻页按钮要精确匹配
        nxt = E.Z("list_next_page") if has(E.Z("list_next_page"), exact=True) else EN["list_next_page"]
        tap_text(nxt, exact=True)
        time.sleep(4)
    raise RuntimeError(f"设置页找不到「{title}」：{texts()[:30]}")


def go_settings(settings_label=None, header=None):
    tap_text(settings_label or E.Z("set_title"), exact=True)
    wait_text(header or E.Z("grp_general"), 120)
    time.sleep(2)


# ---------- 菜单布局 ----------

def tab_texts(i, want, absent=()):
    """切到第 i 个分页，检查按钮：want / absent 为前缀列表"""
    tabs = reader_tabs()
    tap(tabs[i][1], tabs[i][2])
    time.sleep(4)
    ns = list(nodes())
    tt = [t for t, *_ in ns if t]
    sel = [k for k, (_, _, _, s) in enumerate(reader_tabs(dump())) if s]
    miss = [w for w in want if not any(t.startswith(w) for t in tt)]
    extra = [a for a in absent if any(t.startswith(a) for t in tt)]
    return sel == [i] and not miss and not extra, f"选中={sel} 缺少={miss} 不该有={extra}", ns


def borders_ok(name, ns):
    """截图上每个菜单按钮（含分页标签）的底边都有一整条深色边框。
    沉浸模式下导航栏窗口虽然隐藏了，uiautomator 仍会把节点边界裁到它的上沿（最下面的分页标签被裁），
    这种节点按同屏未被裁的按钮高度推算真实底边。"""
    im = look(name)
    px = im.load()
    btns = [(t, bounds(node)) for t, x, y, node in ns if node.get("class") == "android.widget.Button"]
    btns = [(t, b) for t, b in btns if b[1] >= H * 0.5]  # 只看底部操作区
    full_h = max((b[3] - b[1] for _, b in btns), default=0)
    bad, n = [], 0
    for t, (x1, y1, x2, y2) in btns:
        if y2 - y1 < full_h - 2:
            y2 = min(H, y1 + full_h)
        n += 1
        xs = range(x1 + 14, x2 - 14, 2)
        best = max(sum(1 for xx in xs if px[xx, yy] < 170) / max(1, len(xs)) for yy in range(y2 - 6, min(H, y2 + 1)))
        if best < 0.9:
            bad.append(f"{t}:{best:.2f}")
    return n > 0 and not bad, f"{n} 个按钮，下边框缺失：{bad}"


def check_menu(tag, title_prefix, tabs_want):
    open_menu()
    ns = list(nodes())
    root_tabs = reader_tabs(dump())
    top = {t: y for t, x, y, _ in ns if y < H * 0.12 and t}
    back_xy = [(x, y) for t, x, y, _ in ns if t == E.Z("back")]
    rec(f"{tag}_top_bar", E.Z("set_title") in top and E.Z("back") in top and any(t.startswith(title_prefix) for t in top)
        and back_xy and back_xy[0][0] < W * 0.25, f"{list(top)}")
    rec(f"{tag}_tabs_at_bottom", len(root_tabs) == 3 and all(y > H * 0.88 for _, _, y, _ in root_tabs)
        and sum(s for *_, s in root_tabs) == 1, f"{root_tabs}")
    all_want = [w for ws in tabs_want for w in ws]
    for i, want in enumerate(tabs_want):
        absent = [w for w in all_want if w not in want]
        ok, d, ns_i = tab_texts(i, want, absent)
        shot(f"rs_{tag}_tab{i}")
        rec(f"{tag}_tab{i}_content", ok, d)
        okb, db = borders_ok(f"rs_{tag}_tab{i}", ns_i)
        rec(f"{tag}_tab{i}_borders", okb, db)
    # 记住分页：切到第 2 页，收起菜单再打开，仍是第 2 页
    tabs = reader_tabs()
    tap(tabs[1][1], tabs[1][2])
    time.sleep(3)
    key(4)
    time.sleep(3)
    open_menu()
    sel = [k for k, (*_, s) in enumerate(reader_tabs()) if s]
    rec(f"{tag}_tab_remembered", sel == [1], f"选中={sel}")
    key(4)
    time.sleep(3)


# 各分页应有的按钮（文字前缀）；其它分页的按钮不能出现
SCREEN_TAB = [E.pre("reader_orientation"), E.Z("reader_status_bar"), E.Z("reader_full_refresh")]
MANGA_TABS = [
    [E.Z("img_jump"), E.Z("reader_prev_chapter"), E.Z("reader_next_chapter")],
    [E.pre("img_direction"), E.pre("img_zoom"), E.pre("img_contrast"), E.pre("img_spread"),
     E.Z("spread_shift"), E.Z("split_wide"), E.pre("img_crop")],
    SCREEN_TAB,
]
NOVEL_TABS = [
    [E.Z("epub_toc"), E.Z("reader_prev_chapter"), E.Z("reader_next_chapter")],
    [E.Z("epub_font_smaller"), E.Z("epub_font_larger"), E.pre("epub_line_height"), E.pre("epub_font"),
     E.Z("epub_layout")],
    SCREEN_TAB,
]


# ---------- 画面检查 ----------

def statusbar_white(im):
    """底部状态栏：白底，上面有少量黑字（过渡中的黑色导航栏位置不算）"""
    w, h = im.size
    box = im.crop((int(0.02 * w), int(0.975 * h), int(0.98 * w), int(0.998 * h)))
    b = box.tobytes()
    white = sum(1 for p in b if p > 230) / len(b)
    dark = sum(1 for p in b if p < 80)
    return white > 0.6 and dark > 40, f"white={white:.2f} dark={dark}"


def ink(im):
    """阅读区域（不含底部状态栏）里的深色像素数"""
    return sum(1 for p in im.crop((0, 0, im.size[0], int(im.size[1] * 0.965))).tobytes() if p < 100)


def redrawn_like_turned(k, name):
    """当前页的画面应与「下一页 → 上一页」翻回来后一致。
    修复前：返回后只剩左上角一小块旧画面，墨迹不到翻回后的 1/5。
    模拟器（SwiftShader）上 Chromium 偶尔有单个图块栅格化失败、留着复用前的内容，翻页即恢复，
    只差一个图块（墨迹差十几个百分点），这里只记录差异区域，不算失败。"""
    from PIL import ImageChops
    a = look(name)
    key(93)
    time.sleep(12)
    key(92)
    time.sleep(12)
    b = look(name + "_turned")
    ia, ib = ink(a), ink(b)
    box = ImageChops.difference(a.crop((0, 0, W, int(H * 0.965))), b.crop((0, 0, W, int(H * 0.965)))).getbbox()
    rec(k, ib > 0 and ia >= 0.7 * ib, f"当前 ink={ia}，翻页再翻回 ink={ib}，差异区域={box}")


def progress(lab_re):
    """菜单里的小说进度：(本节第几页, 本节页数, 第几节)"""
    for t in texts():
        m = re.search(lab_re, t)
        if m:
            return int(m.group(1)), int(m.group(2)), int(m.group(3))
    return None


ZH_PROG = r"(\d+)/(\d+) · 第 (\d+)/\d+ 节"
EN_PROG = r"(\d+)/(\d+) · Section (\d+)/\d+"


def read_progress(lab_re, settings_label=None):
    open_menu(settings_label)
    p = progress(lab_re)
    key(4)  # 返回键收起菜单
    time.sleep(3)
    return p


def resumed():
    return shell("dumpsys activity activities | grep -m1 mResumedActivity")


# ---------- 各段 ----------

def manga():
    print("== 漫画：Split Test")
    if not E.local_chapters("Split Test"):
        E.download_all("漫画", "Split Test")
    set_prefs(splitWide=False, mangaRtl=True, cropMargins=False, cropBlack=False, spreadMode=0, spreadOrder=0,
              fitMode=0, orientation=1, sbMangaOn=False, menuTabManga=0)
    E.open_reader("Split Test", 0)
    E.jump(3)
    time.sleep(4)
    expect("m_whole_before", "rs_01_whole", whole_wide, 170, 90)

    # 1. 菜单布局
    check_menu("m", "Split Test · ", MANGA_TABS)

    # 2. 设置页打开拆分跨页 → 返回后立即拆开（右→左先看右半）
    open_menu()
    go_settings()
    settings_item(E.Z("split_wide"))
    time.sleep(3)
    key(4)
    expect("split_applied_on_return", "rs_03_split", half, 90, "R")

    # 3. 设置页打开漫画状态栏 → 返回后底部出现状态栏，内容按新尺寸重排
    open_menu()
    go_settings()
    settings_item(E.Z("set_status_bar"), after=E.Z("grp_manga"))
    wait_text(E.Z("sb_show"), 60)
    tap_text(E.Z("sb_show"), exact=True)
    time.sleep(3)
    tap_text(E.Z("done"), exact=True)
    time.sleep(3)
    key(4)

    def sb_on(im):
        ok1, d1 = statusbar_white(im)
        ok2, d2 = half(im, 90, "R")
        return ok1 and ok2, f"{d1} {d2}"
    expect("statusbar_applied_on_return", "rs_04_statusbar", sb_on, timeout=150)
    set_prefs(splitWide=False, sbMangaOn=False, mangaRtl=False, orientation=0)


def novel():
    print("== 小说：测试小说")
    set_prefs(fontZoom=100, orientation=1, menuTabNovel=0)
    E.open_reader("测试小说", 0, wait=20)
    check_menu("n", "测试小说 · ", NOVEL_TABS)
    p1 = read_progress(ZH_PROG)
    rec("novel_progress_before", p1 is not None, f"{p1}")
    shot("rs_05_novel_100")

    # 4. 字号 150% → 返回后立即重新分页，整页按新字号重绘
    open_menu()
    go_settings()
    settings_item(E.Z("set_font_size"))
    wait_text("150%", 60)
    tap_text("150%", exact=True)
    time.sleep(3)
    key(4)
    # 等重新分页完成（本节页数变多），再比较画面
    p2 = None
    end = time.time() + 150
    while time.time() < end:
        time.sleep(10)
        p2 = read_progress(ZH_PROG)
        if p2 and p1 and p2[1] > p1[1]:
            break
    rec("font_size_applied_on_return", bool(p1 and p2 and p2[1] > p1[1] and p2[2] == p1[2]), f"之前 {p1} → 之后 {p2}")
    time.sleep(5)
    redrawn_like_turned("font_size_redrawn_on_return", "rs_06_novel_150")

    # 5. 语言：阅读器 → 设置 → English → 返回后阅读器重建为英文，进度不变
    open_menu()
    go_settings()
    settings_item(E.Z("set_language"))
    wait_text("English", 60)
    tap_text("English", exact=True)
    wait_text(EN["grp_general"], 120)
    time.sleep(3)
    key(4)
    time.sleep(15)
    open_menu(EN["set_title"], wait=150)
    shot("rs_07_menu_en")
    p3 = progress(EN_PROG)
    rec("reader_english_after_return", has(EN["back"], exact=True) and has(EN["reader_tab_screen"], exact=True),
        f"{[t for t in texts() if len(t) < 30][:12]}")
    rec("reader_position_kept", bool(p2 and p3 and p3[1] == p2[1] and p3[2] == p2[2] and abs(p3[0] - p2[0]) <= 1),
        f"之前 {p2} → 之后 {p3}")
    # 顶部「‹ Back」退出阅读器
    tap_text(EN["back"], exact=True)
    time.sleep(8)
    r = resumed()
    rec("top_back_exits_reader", "EpubReaderActivity" not in r, r.strip()[-90:])

    # 书架 → 设置 → 简体中文 → 返回后书架是中文
    E.stop()
    E.am(".ui.MainActivity", wait=10)
    wait_text(EN["shelf_title"], 120)
    tap_text(EN["set_title"], exact=True)
    wait_text(EN["grp_general"], 120)
    settings_item(EN["set_language"])
    wait_text("简体中文", 60)
    tap_text("简体中文", exact=True)
    wait_text(E.Z("grp_general"), 120)
    time.sleep(3)
    key(4)
    ok = False
    end = time.time() + 90
    while time.time() < end and not ok:
        time.sleep(5)
        ok = has(E.Z("shelf_title"), exact=True) and not has(EN["shelf_title"], exact=True)
    shot("rs_08_shelf_zh")
    rec("shelf_chinese_after_return", ok, f"{[t for t in texts() if len(t) < 20][:10]}")
    set_prefs(fontZoom=100, orientation=0)


def main():
    """段名：manga novel（缺省两段都跑）；menu = 只检查两种阅读器的菜单布局"""
    which = [a for a in sys.argv[2:]] or ["manga", "novel"]
    print(sh("install", "-r", APK, timeout=600).decode().strip())
    grant_storage()
    set_app_lang("zh-CN")
    shell("settings put secure immersive_mode_confirmations confirmed")
    sh("logcat", "-b", "crash", "-c")
    if "menu" in which:
        set_prefs(splitWide=False, sbMangaOn=False, mangaRtl=True, spreadMode=0, orientation=1, menuTabManga=0)
        E.open_reader("Split Test", 0)
        E.jump(3)
        check_menu("m", "Split Test · ", MANGA_TABS)
        set_prefs(fontZoom=100, orientation=1, menuTabNovel=0)
        E.open_reader("测试小说", 0, wait=20)
        check_menu("n", "测试小说 · ", NOVEL_TABS)
        set_prefs(orientation=0, mangaRtl=False)
    if "manga" in which:
        manga()
    if "novel" in which:
        novel()
    crash = shell("logcat -d -b crash")
    rec("no_crash", PKG not in crash and "FATAL" not in crash, crash[-300:])
    print("FAILS:", FAILS)
    print("DONE", "OK" if not FAILS else "FAIL")


if __name__ == "__main__":
    main()
