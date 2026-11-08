#!/usr/bin/env python3
"""Build the README screenshot sheets (English and Chinese captions) from raw emulator shots.

Raw shots are produced by the e2e scripts into tools/shots/ (not committed). Usage:
    python3 tools/make_doc_images.py            # writes docs/*.png
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
SHOTS = os.path.join(HERE, "shots")
DOCS = os.path.join(HERE, "..", "docs")
FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"

# (raw shot, English caption, Chinese caption)
SHEETS = {
    # English UI overview; the reading pages themselves are language-neutral
    "screenshots_en.png": (4, 380, [
        ("v_lang_en_1shelf", "Bookshelf (offline)", None),
        ("v_lang_en_2series", "Series & downloads", None),
        ("v_lang_en_4settings", "Settings", None),
        ("v_lang_en_7fonts", "Fonts", None),
        ("v_lang_en_5reader_menu", "Comic reader menu", None),
        ("v_lang_en_6novel_menu", "Novel + status bar", None),
        ("v_sp_divider", "Two-page spread", None),
        ("v_cr_white_p1", "Auto-trim margins", None),
    ]),
    "pause_resume_screenshots_en.png": (3, 500, [
        ("p01_series_downloading", "Downloading: percent & size", None),
        ("p02_item_actions", "Tap an item: pause / cancel", None),
        ("p03_series_paused", "Paused: progress is kept", None),
        ("p04_queue_paused_after_restart", "Still paused after restart", None),
        ("p08_auto_retry", "Network lost: auto-retry", None),
        ("p11_notification_paused", "Notification: Resume all", None),
    ]),
    "v140_split_en.png|v140_split.png": (4, 300, [
        ("sp_cbz_01_cover", "Wide cover: not split", "封面宽页：不拆"),
        ("sp_cbz_03_p3a", "Page 3, 1st half (RTL)", "第 3 页前半（右→左）"),
        ("sp_cbz_04_p3b", "Page 3, 2nd half", "第 3 页后半"),
        ("sp_cbz_14_crop_a", "Trim: per half", "裁白边：半页单独裁"),
        ("sp_cbz_12_ltr_a", "LTR: left half first", "左→右：先看左半"),
        ("sp_cbz_17_off", "Off: whole page", "关闭：整张显示"),
        ("sp_cbz_18_land", "Landscape: not split", "横屏：不拆"),
        ("sp_cbz_19_menu_on", "Toggled in reader menu", "阅读菜单里打开"),
    ]),
}


def sheet(cols, width, items, lang):
    size = min(30, max(22, width // 13))
    cap_h = size + 22
    cells = []
    for name, en, zh in items:
        im = Image.open(os.path.join(SHOTS, name + ".png")).convert("L")
        scale = width / im.size[0]
        im = im.resize((width, int(im.size[1] * scale)), Image.LANCZOS)
        cells.append((im, en if lang == "en" else (zh or en)))
    body_h = max(im.size[1] for im, _ in cells)
    rows = (len(cells) + cols - 1) // cols
    gap = 14
    out = Image.new("L", (cols * width + (cols + 1) * gap, rows * (cap_h + body_h) + (rows + 1) * gap), 255)
    d = ImageDraw.Draw(out)
    for i, (im, label) in enumerate(cells):
        x = gap + (i % cols) * (width + gap)
        y = gap + (i // cols) * (cap_h + body_h + gap)
        s = size
        font = ImageFont.truetype(FONT, s)
        while d.textlength(label, font=font) > width - 8 and s > 14:
            s -= 1
            font = ImageFont.truetype(FONT, s)
        d.text((x + 4, y + 2 + (size - s)), label, fill=0, font=font)
        top = y + cap_h + (body_h - im.size[1]) // 2
        out.paste(im, (x, top))
        d.rectangle([x - 1, top - 1, x + width, top + im.size[1]], outline=150)
    return out


def main():
    os.makedirs(DOCS, exist_ok=True)
    for names, (cols, width, items) in SHEETS.items():
        for lang, name in zip(("en", "zh"), names.split("|")):
            path = os.path.normpath(os.path.join(DOCS, name))
            sheet(cols, width, items, lang).save(path, optimize=True)
            print("wrote", path)


if __name__ == "__main__":
    sys.exit(main())
