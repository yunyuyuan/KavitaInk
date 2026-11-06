#!/usr/bin/env python3
"""生成 e2e_v120.py 用的测试漫画，放进 Kavita「漫画」库所在目录后扫描入库。

  Spread Test   10 页，每页印大号页码；第 1 页是封面，第 6 页是横图（验证页序、首页单独、横图单独、错开一页）
  Crop Test     4 页：四边白边 / 只有左边白边 / 白底画面贴边（不应被裁）/ 黑边（只在「同时裁黑边」时裁）
  Many Chapters 120 章，每章 1 页（批量操作在 100+ 章节上的流畅度）

用法：python3 make_fixtures_v120.py <漫画库目录>       需要 Pillow 和 DejaVu 字体
"""
import io
import os
import sys
import zipfile

from PIL import Image, ImageDraw, ImageFont

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def F(n):
    return ImageFont.truetype(FONT, n)


def jpg(im):
    b = io.BytesIO()
    im.save(b, "JPEG", quality=80)
    return b.getvalue()


def cbz(path, pages):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        for i, im in enumerate(pages):
            z.writestr(f"{i + 1:03d}.jpg", jpg(im))
    print(path, os.path.getsize(path))


def spread_test(root):
    pages = []
    for n in range(1, 11):
        if n == 6:
            im = Image.new("L", (1600, 1100), 235)
            d = ImageDraw.Draw(im)
            d.rectangle([10, 10, 1589, 1089], outline=0, width=8)
            d.text((800, 550), "6 WIDE", font=F(260), fill=0, anchor="mm")
        else:
            im = Image.new("L", (800, 1150), 255 if n % 2 else 215)
            d = ImageDraw.Draw(im)
            d.rectangle([6, 6, 793, 1143], outline=0, width=6)
            d.text((400, 520), str(n), font=F(420), fill=0, anchor="mm")
            d.text((400, 950), "COVER" if n == 1 else ("ODD" if n % 2 else "EVEN"), font=F(90), fill=0, anchor="mm")
        pages.append(im)
    cbz(os.path.join(root, "Spread Test", "Spread Test Ch.001.cbz"), pages)


def crop_test(root):
    W, H = 800, 1150

    def art(im, box, label):
        d = ImageDraw.Draw(im)
        x0, y0, x1, y1 = box
        d.rectangle(box, fill=170, outline=0, width=6)
        for k in range(0, x1 - x0, 40):
            d.line([x0 + k, y0, x0 + k, y1], fill=60, width=3)
        d.text(((x0 + x1) // 2, (y0 + y1) // 2), label, font=F(70), fill=0, anchor="mm")

    p = []
    im = Image.new("L", (W, H), 255)
    art(im, (160, 200, 640, 950), "4 SIDES")          # 四边白边
    p.append(im)
    im = Image.new("L", (W, H), 255)
    art(im, (220, 0, W - 1, H - 1), "LEFT")           # 只有左边白边
    p.append(im)
    im = Image.new("L", (W, H), 255)                  # 白底画面贴边：线条碰到四边
    d = ImageDraw.Draw(im)
    d.line([0, 0, W - 1, H - 1], fill=0, width=5)
    d.line([W - 1, 0, 0, H - 1], fill=0, width=5)
    d.ellipse([200, 300, 600, 850], outline=0, width=6)
    d.text((W // 2, H // 2), "EDGE", font=F(80), fill=0, anchor="mm")
    p.append(im)
    im = Image.new("L", (W, H), 0)
    art(im, (120, 160, 680, 990), "BLACK")            # 黑边
    p.append(im)
    cbz(os.path.join(root, "Crop Test", "Crop Test Ch.001.cbz"), p)


def many_chapters(root, n=120):
    d0 = os.path.join(root, "Many Chapters")
    os.makedirs(d0, exist_ok=True)
    for c in range(1, n + 1):
        im = Image.new("L", (600, 860), 255)
        d = ImageDraw.Draw(im)
        d.rectangle([4, 4, 595, 855], outline=0, width=4)
        d.text((300, 430), f"{c}", font=F(240), fill=0, anchor="mm")
        with zipfile.ZipFile(os.path.join(d0, f"Many Chapters Ch.{c:03d}.cbz"), "w") as z:
            z.writestr("001.jpg", jpg(im))
    print(d0, n, "chapters")


if __name__ == "__main__":
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/library/manga")
    spread_test(root)
    crop_test(root)
    many_chapters(root)
