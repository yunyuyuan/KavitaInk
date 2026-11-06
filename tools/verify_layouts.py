#!/usr/bin/env python3
"""F5 排版 × F8 状态栏的各种组合，在模拟器的真实 WebView（Android 9 / WebView 66）中逐页校验三本测试书。

前提：
  - 装的是可调试构建（gradle assembleRelease -PrelDebug=true，WebView 才能远程调试）；
  - 本地已下载过任意一本 EPUB（借用它的 chapter.json，把测试书放进临时章节目录打开）。
每个组合：改 App 设置（root 下改 shared_prefs）→ 打开测试书 → verify_webview.py 逐章逐页检查
（没有文字被截断、没有空白页、标题 / 居中 / 图片不缩进）。

用法：python3 verify_layouts.py [组合名 ...]      结果：tools/shots/layouts_results.json
"""
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from adbui import ADB, PKG, SHOTS, edit_prefs, shell, sh, shot  # noqa: E402

ROOT = os.path.dirname(HERE)
GRADLE = os.environ.get("GRADLE", os.path.expanduser("~/gradle-dist/gradle-8.11.1/bin/gradle"))
DUMP = "/tmp/layout_dump"
PREFS = f"/data/data/{PKG}/shared_prefs/settings.xml"
VT = f"/storage/emulated/0/Android/data/{PKG}/files/vtest"
BOOKS = {
    "alice": f"{ROOT}/app/src/test/resources/alice.epub",
    "novel_zh": f"{ROOT}/app/src/test/resources/novel_zh.epub",
    "edge_cases": f"{ROOT}/app/src/test/resources/edge_cases.epub",
}
SB_ALL = 1 | 2 | 4 | 8 | 16
COMBOS = [
    # 使用书籍原排版（与 1.1.0 一致），状态栏默认（底部：页码 + 时间 + 电量）
    {"name": "book_default", "bookStyle": True},
    # 自定义排版的默认值
    {"name": "custom_default", "bookStyle": False},
    # 各项取最大值 + 状态栏在顶部、全部项、大字号
    {"name": "custom_max", "bookStyle": False, "textIndent": 4, "paraSpacing": 15, "justify": True,
     "letterSpacing": 15, "marginX": 48, "marginY": 48, "lineHeight": 200, "fontZoom": 200,
     "sb": {"on": True, "top": True, "size": 1, "items": SB_ALL}},
    # 各项取最小值 + 关闭状态栏（行距 0 = 书籍自带行距）
    {"name": "custom_min", "bookStyle": False, "textIndent": 0, "paraSpacing": 0, "justify": False,
     "letterSpacing": 0, "marginX": 8, "marginY": 8, "lineHeight": 0, "fontZoom": 80,
     "sb": {"on": False}},
    # 书籍原排版 + 状态栏底部全部项、大字号
    {"name": "sb_bottom_all", "bookStyle": True, "sb": {"on": True, "top": False, "size": 1, "items": SB_ALL}},
]
PREF_KEYS = {"marginX": "margin", "marginY": "marginY", "lineHeight": "lineHeight", "fontZoom": "fontZoom",
             "bookStyle": "bookStyle", "textIndent": "textIndent", "paraSpacing": "paraSpacing",
             "justify": "justify", "letterSpacing": "letterSpacing"}
DEFAULTS = {"marginX": 16, "marginY": 18, "lineHeight": 160, "fontZoom": 100, "bookStyle": True,
            "textIndent": 2, "paraSpacing": 5, "justify": True, "letterSpacing": 0}


def dump_scripts(combos):
    path = "/tmp/layout_combos.json"
    json.dump(combos, open(path, "w"))
    env = dict(os.environ, PAGINATOR_DUMP=DUMP, PAGINATOR_COMBOS=path)
    env.pop("KAVITA_URL", None)
    r = subprocess.run([GRADLE, "testDebugUnitTest", "--tests", "*PaginatorDumpTest*", "--rerun-tasks", "-q",
                        "--console=plain"], cwd=ROOT, env=env, capture_output=True, text=True)
    for c in combos:
        if not os.path.exists(f"{DUMP}/{c['name']}/script.js"):
            raise RuntimeError("导出脚本失败：" + r.stdout[-2000:] + r.stderr[-2000:])


def set_prefs(c):
    """改 shared_prefs（先停掉应用，原子替换文件，下次启动时读取）"""
    vals = dict(DEFAULTS)
    vals.update({k: v for k, v in c.items() if k in DEFAULTS})
    sb = c.get("sb", {"on": True, "top": False, "size": 0, "items": 1 | 8 | 16})
    items = {PREF_KEYS[k]: v for k, v in vals.items()}
    items.update({"sbNovelOn": sb["on"], "sbNovelTop": sb.get("top", False),
                  "sbNovelSize": sb.get("size", 0), "sbNovelItems": sb.get("items", 1 | 8 | 16),
                  "fontId": "builtin:default"})

    def fn(xml):
        for k, v in items.items():
            if isinstance(v, bool):
                tag = f'<boolean name="{k}" value="{str(v).lower()}" />'
                pat = rf'<boolean name="{k}" value="[^"]*" />'
            elif isinstance(v, str):
                tag = f'<string name="{k}">{v}</string>'
                pat = rf'<string name="{k}">[^<]*</string>'
            else:
                tag = f'<int name="{k}" value="{v}" />'
                pat = rf'<int name="{k}" value="[^"]*" />'
            if re.search(pat, xml):
                xml = re.sub(pat, tag, xml)
            else:
                xml = xml.replace("</map>", f"    {tag}\n</map>")
        return xml
    edit_prefs("settings", fn)


def prepare_books():
    """把三本测试书放进临时章节目录（借用一本已下载 EPUB 的 chapter.json）"""
    out = shell(f"find /sdcard/Android/data/{PKG}/files/library -name book.epub")
    src = [p for p in out.split() if p.endswith("book.epub")]
    if not src:
        raise RuntimeError("本地没有已下载的 EPUB")
    meta = os.path.dirname(src[0]) + "/chapter.json"
    for name, path in BOOKS.items():
        d = f"{VT}/{name}"
        shell(f"mkdir -p {d}; cp {meta} {d}/chapter.json; rm -f {d}/progress.json")
        sh("push", path, f"{d}/book.epub")


def run(combo, book):
    set_prefs(combo)
    shell(f"am start -W -n {PKG}/.reader.EpubReaderActivity --es dir {VT}/{book}")
    time.sleep(15)
    minleft = min(15, combo.get("marginX", 16) - 1)
    env = dict(os.environ, MINLEFT=str(minleft), INDENT_CHECK="0" if combo.get("bookStyle") else "1")
    r = subprocess.run([sys.executable, f"{HERE}/verify_webview.py", BOOKS[book], f"{DUMP}/{combo['name']}", "auto", "auto"],
                       cwd=HERE, env=env, capture_output=True, text=True, timeout=1800)
    out = r.stdout + r.stderr
    m = re.search(r"合计 (\d+) 章 (\d+) 页，异常 (\d+)，有首行缩进的正文段落 (\d+)", out)
    dims = re.search(r"自动尺寸：(\d+)x(\d+)", out)
    res = {"pass": "RESULT PASS" in out, "chapters": int(m.group(1)) if m else None, "pages": int(m.group(2)) if m else None,
           "bad": int(m.group(3)) if m else None, "indented_paras": int(m.group(4)) if m else None,
           "viewport": f"{dims.group(1)}x{dims.group(2)}" if dims else None}
    if not res["pass"]:
        res["log"] = out[-1500:]
    open(f"{SHOTS}/layout_{combo['name']}_{book}.log", "w").write(out)
    # 重新打开到第 2 章第 1 页截图，目视检查
    shell(f"am force-stop {PKG}")
    shell(f"am start -W -n {PKG}/.reader.EpubReaderActivity --es dir {VT}/{book}")
    time.sleep(12)
    shot(f"layout_{combo['name']}_{book}")
    return res


if __name__ == "__main__":
    names = sys.argv[1:]
    combos = [c for c in COMBOS if not names or c["name"] in names]
    sh("root", timeout=60)
    time.sleep(2)
    dump_scripts(combos)
    prepare_books()
    path = f"{SHOTS}/layouts_results.json"
    results = json.load(open(path)) if os.path.exists(path) else {}
    for c in combos:
        for b in BOOKS:
            t0 = time.time()
            try:
                r = run(c, b)
            except Exception as e:
                r = {"pass": False, "error": repr(e)[:500]}
            r["seconds"] = round(time.time() - t0)
            results[f"{c['name']}/{b}"] = r
            print(c["name"], b, {k: v for k, v in r.items() if k != "log"}, flush=True)
            json.dump(results, open(path, "w"), ensure_ascii=False, indent=1)
    set_prefs({"name": "restore", "bookStyle": True})
    ok = all(v.get("pass") for v in results.values())
    print("RESULT", "PASS" if ok else "FAIL")
