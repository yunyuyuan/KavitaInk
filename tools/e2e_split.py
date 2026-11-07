"""1.4.0「拆分跨页」模拟器测试（素材见 make_fixtures_v140.py；界面语言需为 zh-CN）。

按截图亮度判断显示的是哪一页、哪一半：
  第 3 页：左半内容 170（外侧左边白），右半内容 90（外侧右边白）
  第 5 页：左半 220，右半 60（3200x2300 大图）
用法：ANDROID_SERIAL=emulator-5556 KAVITA_PASS=... python3 tools/e2e_split.py [login] [cbz|pdf|all]
"""
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from adbui import *  # noqa: E402,F401
import e2e_v120 as E  # noqa: E402
from PIL import Image  # noqa: E402

OUT = os.path.join(SHOTS, "split_results.json")
RES = json.load(open(OUT)) if os.path.exists(OUT) else {}
FAILS = []


def rec(k, ok, detail=""):
    RES[k] = {"ok": bool(ok), "detail": detail}
    json.dump(RES, open(OUT, "w"), ensure_ascii=False, indent=1)
    print(f"  [{'OK' if ok else 'FAIL'}] {k} {detail}")
    if not ok:
        FAILS.append(k)


def set_prefs(**kw):
    """改 settings.xml：布尔 / 整数"""
    def fn(x):
        for k, v in kw.items():
            x = re.sub(rf'\s*<(boolean|int|string) name="{k}"[^>]*/>', "", x)
            x = re.sub(rf'\s*<string name="{k}">[^<]*</string>', "", x)
            if isinstance(v, bool):
                line = f'    <boolean name="{k}" value="{"true" if v else "false"}" />'
            else:
                line = f'    <int name="{k}" value="{v}" />'
            x = x.replace("</map>", line + "\n</map>")
        return x
    edit_prefs("settings", fn)


def med(im, x0, y0, x1, y1):
    """截图中按比例给出的区域的亮度中位数"""
    w, h = im.size
    box = im.crop((int(x0 * w), int(y0 * h), int(x1 * w), int(y1 * h)))
    px = sorted(box.tobytes())
    return px[len(px) // 2]


def look(name):
    shot(name)
    return Image.open(os.path.join(SHOTS, name + ".png")).convert("L")


def near(v, want, tol=18):
    return abs(v - want) <= tol


def page_up(n=1, gap=1.0):
    for _ in range(n):
        key(92)
        time.sleep(gap)


def half(im, fill, outer, crop=False):
    """第 3 页的一半：fill = 内容灰度；outer = 外侧白边在哪边（'L' / 'R'）。
    不裁时外侧有白边、上方有白边；裁白边后内容铺满高度，上方也是内容色。"""
    mid = med(im, 0.35, 0.40, 0.65, 0.60)
    side = med(im, 0.03, 0.40, 0.12, 0.60) if outer == "L" else med(im, 0.88, 0.40, 0.97, 0.60)
    top = med(im, 0.35, 0.025, 0.65, 0.045)
    if crop:
        ok = near(mid, fill) and near(top, fill)
    else:
        ok = near(mid, fill) and side > 240 and top > 240
    return ok, f"mid={mid} side={side} top={top}"


def whole_wide(im, left, right):
    a = med(im, 0.10, 0.45, 0.40, 0.55)
    b = med(im, 0.60, 0.45, 0.90, 0.55)
    return near(a, left) and near(b, right), f"left={a} right={b}"


def solid(im, want):
    v = med(im, 0.20, 0.30, 0.80, 0.45)
    return near(v, want), f"mid={v}"


def normal(im):
    """普通竖页：白底大黑字，中间区域以白为主"""
    v = med(im, 0.15, 0.15, 0.85, 0.30)
    return v > 240, f"mid={v}"


def rotate(r):
    """用系统设置转屏：r=1 横屏，r=0 竖屏并恢复自动旋转"""
    if r:
        shell("settings put system accelerometer_rotation 0; settings put system user_rotation 1")
    else:
        shell("settings put system user_rotation 0; settings put system accelerometer_rotation 1")
    for _ in range(30):
        if f"ROTATION_{90 if r else 0}" in shell("dumpsys window displays | grep -m1 mCurrentRotation"):
            return
        time.sleep(1)


def expect(k, name, fn, *args, timeout=75, **kw):
    """反复截图直到画面符合预期（模拟器上一次截图就要 5–15 秒，固定等待会截到按键前的画面）。
    超时仍不符合即失败：按键丢失或翻到错误的一半时，后续步骤都会一直等不到预期画面。"""
    t0 = time.time()
    tries = 0
    while True:
        tries += 1
        ok, d = fn(look(name), *args, **kw)
        if ok or time.time() - t0 > timeout:
            rec(k, ok, f"{d} ({tries} 次截图, {time.time() - t0:.0f}s)")
            return ok
        time.sleep(1)


def position(series):
    c = E.local_chapters(series)[0]
    x = shell(f"cat /data/data/{PKG}/shared_prefs/progress.xml")
    m = re.search(rf'name="pos:{c["serverKey"]}:{c["chapterId"]}">([^<]*)<', x)
    return m.group(1) if m else None


def run(series, tag):
    print(f"== {tag}: {series}")
    base = dict(splitWide=True, mangaRtl=True, cropMargins=False, cropBlack=False, spreadMode=0,
                spreadOrder=0, fitMode=0, orientation=1)
    set_prefs(**base)
    shell(f"touch /sdcard/Android/data/{PKG}/files/timing.flag")
    sh("logcat", "-c")
    E.open_reader(series, 0)
    E.jump(1)
    time.sleep(6)
    expect(f"{tag}_cover_whole", f"sp_{tag}_01_cover", whole_wide, 200, 120)
    E.page_down(1, 1)
    expect(f"{tag}_p2_normal", f"sp_{tag}_02_p2", normal)
    # 右→左：先右半（90，外侧白边在右）再左半（170，外侧白边在左）
    E.page_down(1, 1)
    expect(f"{tag}_p3_first_right", f"sp_{tag}_03_p3a", half, 90, "R")
    E.page_down(1, 1)
    expect(f"{tag}_p3_second_left", f"sp_{tag}_04_p3b", half, 170, "L")
    E.page_down(1, 1)
    expect(f"{tag}_p4_normal", f"sp_{tag}_05_p4", normal)
    # 往回翻：先落在第 3 页的后半，再到前半
    page_up(1, 1)
    expect(f"{tag}_back_to_second", f"sp_{tag}_06_back_b", half, 170, "L")
    page_up(1, 1)
    expect(f"{tag}_back_to_first", f"sp_{tag}_07_back_a", half, 90, "R")
    page_up(1, 1)
    expect(f"{tag}_back_p2", f"sp_{tag}_08_back_p2", normal)
    # 大图第 5 页：右半 60，左半 220
    E.jump(5)
    time.sleep(4)
    expect(f"{tag}_p5_first_right", f"sp_{tag}_09_p5a", solid, 60)
    E.page_down(1, 1)
    expect(f"{tag}_p5_second_left", f"sp_{tag}_10_p5b", solid, 220)
    # 停在第 5 页后半退出，再打开应回到这里
    E.back()          # 正常退出阅读器（onPause 时保存进度）
    time.sleep(4)
    E.stop()
    time.sleep(2)
    pos = position(series)
    rec(f"{tag}_position_saved", pos == "4:1", f"pos={pos}")
    E.open_reader(series, 0)
    time.sleep(6)
    expect(f"{tag}_resume_second_half", f"sp_{tag}_11_resume", solid, 220)

    # 左→右：先左半
    set_prefs(**dict(base, mangaRtl=False))
    E.open_reader(series, 0)
    E.jump(3)
    time.sleep(4)
    expect(f"{tag}_ltr_first_left", f"sp_{tag}_12_ltr_a", half, 170, "L")
    E.page_down(1, 1)
    expect(f"{tag}_ltr_second_right", f"sp_{tag}_13_ltr_b", half, 90, "R")

    # 裁白边：两半各自按外侧白边裁
    set_prefs(**dict(base, cropMargins=True))
    sh("logcat", "-c")
    E.open_reader(series, 0)
    E.jump(3)
    time.sleep(5)
    expect(f"{tag}_crop_first_right", f"sp_{tag}_14_crop_a", half, 90, "R", crop=True)
    E.page_down(1, 1)
    expect(f"{tag}_crop_second_left", f"sp_{tag}_15_crop_b", half, 170, "L", crop=True)
    E.jump(5)
    expect(f"{tag}_crop_p5_fullbleed", f"sp_{tag}_15b_crop_p5", solid, 60)   # 满版画面：预检后跳过裁边
    time.sleep(3)
    log = sh("logcat", "-d", "-s", "KavitaInk:D").decode(errors="replace")
    sizes = re.findall(r"crop page=4 .*size=(\d+)x(\d+) side=(\d)", log)
    detail = ";".join(f"{w}x{h}/side{s}" for w, h, s in sizes)
    if tag == "cbz":
        # 半页按半页的尺寸决定缩小倍数：1600x2300 原样解码（整页显示时只会解到 1600x1150）
        rec(f"{tag}_half_full_resolution", any(w == "1600" and h == "2300" for w, h, s in sizes), detail)
    else:
        # PDF 按目标尺寸直接渲染（矢量，没有「原分辨率」一说），日志里只有 decode 行：第 5 页两半各渲染一次
        n4 = len(re.findall(r"decode page=4 ", log))
        rec(f"{tag}_half_rendered", n4 >= 2, f"decode page=4 x{n4}")
    print("  timing:", " | ".join(l.split("KavitaInk:")[-1].strip() for l in log.splitlines()
                                   if "page=2" in l or "page=4" in l)[:900])

    # 强制双页（竖屏）：宽页本来就单独一屏，照样拆
    set_prefs(**dict(base, spreadMode=2))
    E.open_reader(series, 0)
    E.jump(3)
    time.sleep(6)
    expect(f"{tag}_forced_spread_split", f"sp_{tag}_16_spread", half, 90, "R")

    # 关掉开关：宽页整张显示
    set_prefs(**dict(base, splitWide=False))
    E.open_reader(series, 0)
    E.jump(3)
    time.sleep(5)
    expect(f"{tag}_off_whole", f"sp_{tag}_17_off", whole_wide, 170, 90)

    # 横屏：不拆，宽页整张显示。
    # 用系统旋转而不是应用的「屏幕方向」设置：Android 10/11 在去掉系统栏后长宽比 ≤ 4:3 的屏幕上
    # 会忽略应用指定的方向（AOSP DisplayContent.mIgnoreRotationForApps），这台模拟器正是这种屏幕。
    set_prefs(**dict(base, orientation=0))
    rotate(1)
    E.open_reader(series, 0)
    E.jump(3)
    time.sleep(3)
    expect(f"{tag}_landscape_whole", f"sp_{tag}_18_land", whole_wide, 170, 90)
    # 阅读中转回竖屏：同一页立刻拆开，显示前半
    rotate(0)
    expect(f"{tag}_rotate_back_split", f"sp_{tag}_18b_port", half, 90, "R")

    # 菜单开关：竖屏、默认关 → 菜单里打开 → 当前第 3 页立刻拆开
    set_prefs(**dict(base, splitWide=False))
    E.open_reader(series, 0)
    E.jump(3)
    time.sleep(4)
    E.menu()
    label = E.label_of(E.pre("split_wide"))
    E.tap_prefix(E.pre("split_wide"))
    time.sleep(4)
    label2 = E.label_of(E.pre("split_wide"))
    E.back()
    time.sleep(5)
    rec(f"{tag}_menu_label", label != label2, f"{label} -> {label2}")
    expect(f"{tag}_menu_toggle", f"sp_{tag}_19_menu_on", half, 90, "R")
    E.stop()


def main():
    args = sys.argv[1:] or ["all"]
    if "login" in args:
        # 设置被清空时：固定中文界面并重新登录测试服
        set_app_lang("zh-CN")
        E.sec_login()
        args = [a for a in args if a != "login"] or ["all"]
    which = args[0]
    if which in ("cbz", "all"):
        if not E.local_chapters("Split Test"):
            E.download_all("漫画", "Split Test")
        run("Split Test", "cbz")
    if which in ("pdf", "all"):
        if not E.local_chapters("Split PDF"):
            E.download_all("漫画", "Split PDF Ch.001")
        run("Split PDF", "pdf")
    # 恢复默认
    set_prefs(splitWide=False, cropMargins=False, spreadMode=0, orientation=0, mangaRtl=False)
    shell(f"rm -f /sdcard/Android/data/{PKG}/files/timing.flag")
    print("FAILS:", FAILS)
    print("DONE", "OK" if not FAILS else "FAIL")


if __name__ == "__main__":
    main()
