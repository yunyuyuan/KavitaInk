"""生成 1.4.0「拆分跨页」测试素材，放进测试服的漫画书库：

  Split Test/Split Test Ch.001.cbz   6 页图片
  Split PDF/Split PDF Ch.001.pdf     同样 6 页的 PDF

每页用不同的灰度填充，测试时按截图亮度判断显示的是哪一页、哪一半：
  第 1 页  宽封面 1600x1100：左半 200、右半 120（封面不拆，应整张显示）
  第 2 页  普通竖页 800x1150：白底黑字 "2"
  第 3 页  宽页 1600x1150：白底；左半内容块 170（外侧左边留 200 px 白边），右半内容块 90（外侧右边留 200 px 白边），
           上下各留 100 px 白边，中缝两侧不留白
  第 4 页  普通竖页 "4"
  第 5 页  大尺寸满版宽页 3200x2300：左半 220、右半 60（检验半页按半页的尺寸解码，清晰度不打折）
  第 6 页  普通竖页 "6"

用法：python3 tools/make_fixtures_v140.py [书库目录，默认 ~/library/manga]
"""
import io
import os
import sys
import zipfile

from PIL import Image, ImageDraw, ImageFont

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/library/manga")


def font(size):
    for p in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
              "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"):
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def label(d, xy, text, size, fill):
    f = font(size)
    l, t, r, b = d.textbbox((0, 0), text, font=f)
    d.text((xy[0] - (r - l) / 2, xy[1] - (b - t) / 2), text, font=f, fill=fill)


def normal(n):
    im = Image.new("L", (800, 1150), 255)
    d = ImageDraw.Draw(im)
    label(d, (400, 575), str(n), 300, 0)
    d.rectangle((40, 40, 760, 1110), outline=0, width=6)
    return im


def pages():
    out = []
    # 1 宽封面
    im = Image.new("L", (1600, 1100), 255)
    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, 799, 1099), fill=200)
    d.rectangle((800, 0, 1599, 1099), fill=120)
    label(d, (400, 550), "BACK", 160, 0)
    label(d, (1200, 550), "COVER", 160, 255)
    out.append(im)
    out.append(normal(2))
    # 3 宽页：两半各有外侧白边
    im = Image.new("L", (1600, 1150), 255)
    d = ImageDraw.Draw(im)
    d.rectangle((200, 100, 799, 1049), fill=170)
    d.rectangle((800, 100, 1399, 1049), fill=90)
    label(d, (500, 575), "3L", 220, 0)
    label(d, (1100, 575), "3R", 220, 255)
    out.append(im)
    out.append(normal(4))
    # 5 大尺寸满版宽页
    im = Image.new("L", (3200, 2300), 255)
    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, 1599, 2299), fill=220)
    d.rectangle((1600, 0, 3199, 2299), fill=60)
    label(d, (800, 1150), "5L", 440, 0)
    label(d, (2400, 1150), "5R", 440, 255)
    # 细线：清晰度检查用（半页按原分辨率解码时线条清楚）
    for x in range(100, 1500, 40):
        d.line((x, 1700, x, 2200), fill=0, width=3)
    out.append(im)
    out.append(normal(6))
    return out


def main():
    ims = pages()
    d1 = os.path.join(ROOT, "Split Test")
    os.makedirs(d1, exist_ok=True)
    cbz = os.path.join(d1, "Split Test Ch.001.cbz")
    with zipfile.ZipFile(cbz, "w", zipfile.ZIP_STORED) as z:
        for i, im in enumerate(ims):
            b = io.BytesIO()
            im.convert("RGB").save(b, "JPEG", quality=92)
            z.writestr(f"{i + 1:03d}.jpg", b.getvalue())
    d2 = os.path.join(ROOT, "Split PDF")
    os.makedirs(d2, exist_ok=True)
    pdf = os.path.join(d2, "Split PDF Ch.001.pdf")
    rgb = [im.convert("RGB") for im in ims]
    rgb[0].save(pdf, "PDF", save_all=True, append_images=rgb[1:], resolution=100)
    for p in (cbz, pdf):
        print(p, os.path.getsize(p))


if __name__ == "__main__":
    main()
