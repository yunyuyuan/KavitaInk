"""
在无头 Chromium 中模拟 App 的 EPUB 阅读流程：
  - 拦截 https://epub.local/* 请求，直接从 zip 里取资源（同 App 的 shouldInterceptRequest）
  - 对书脊文档在 </head> 前插入分页样式（同 Paginator.injectStyle）
  - 页面加载后执行 Paginator.script，并用桩对象接收 EinkBridge.onLayout 回调
校验：页数计算、逐页跳转位置、最后一页不为空、锚点定位、视口尺寸不一致时的自动校正。
"""
import re, sys, zipfile, json, os, urllib.parse
from playwright.sync_api import sync_playwright

DUMP = sys.argv[1]
OUT = sys.argv[2]
os.makedirs(OUT, exist_ok=True)
HEAD_TPL = open(f"{DUMP}/head_template.txt").read()
ST = json.load(open(f"{DUMP}/style.json"))
SCRIPT_TPL = open(f"{DUMP}/script.js").read()

def fill(t, w, h):
    # 与 Kotlin Paginator.fill / 页面内 css() 完全相同的占位符替换
    mx, mt, mb = ST["mx"], ST["mt"], ST["mb"]
    return (t.replace("__W__", str(w)).replace("__H__", str(h))
             .replace("__CW__", str(max(50, w - 2 * mx))).replace("__GAP__", str(2 * mx))
             .replace("__IH__", str(max(50, h - mt - mb))).replace("__MT__", str(mt))
             .replace("__MB__", str(mb)).replace("__MX__", str(mx)))

def style_tag(w, h):
    return fill(HEAD_TPL, w, h)

def script_for(w, h):
    return SCRIPT_TPL.replace("HW=11111", f"HW={w}").replace("HH=22222", f"HH={h}")

def inject(data: bytes, tag: str) -> bytes:
    s = data.decode("latin-1")
    m = re.search(r"</head\s*>", s, re.I)
    if not m:
        return data
    return (s[: m.start()] + tag + s[m.start():]).encode("latin-1")

def opf_spine(z):
    c = z.read("META-INF/container.xml").decode()
    opf = re.search(r'full-path="([^"]+)"', c).group(1)
    d = opf.rsplit("/", 1)[0] if "/" in opf else ""
    x = z.read(opf).decode("utf-8")
    items = {}
    for m in re.finditer(r"<(?:\w+:)?item\b([^>]*)/?>", x):
        a = m.group(1)
        id_ = re.search(r'\bid="([^"]+)"', a).group(1)
        href = re.search(r'\bhref="([^"]+)"', a).group(1)
        mt = re.search(r'media-type="([^"]+)"', a).group(1)
        items[id_] = ((d + "/" if d else "") + urllib.parse.unquote(href), mt)
    spine = [items[m.group(1)] for m in re.finditer(r'<(?:\w+:)?itemref\b[^>]*idref="([^"]+)"', x)]
    return spine, {p: mt for p, mt in items.values()}

def run(epub, vw, vh, wrong_size=False, label="", mobile=False):
    z = zipfile.ZipFile(epub)
    spine, mimes = opf_spine(z)
    names = {n.lower(): n for n in z.namelist()}
    spine_set = {p.lower() for p, _ in spine}
    results = []
    with sync_playwright() as p:
        b = p.chromium.launch(executable_path="/usr/bin/chromium", args=["--no-sandbox"])
        ctx = b.new_context(viewport={"width": vw, "height": vh}, device_scale_factor=1.875 if mobile else 1, is_mobile=mobile, has_touch=mobile)
        pg = ctx.new_page()
        layouts = []
        pg.expose_function("__einkReport", lambda g, c: layouts.append((g, c)))
        pg.add_init_script("window.EinkBridge={onLayout:function(g,c){window.__einkReport(g,c);}};")

        def handle(route):
            u = urllib.parse.urlparse(route.request.url)
            path = urllib.parse.unquote(u.path).lstrip("/")
            n = names.get(path.lower())
            if not n:
                return route.fulfill(status=404, body=b"")
            data = z.read(n)
            mt = mimes.get(n, "application/octet-stream")
            if n.lower() in spine_set:
                data = inject(data, style_tag(500, 700) if wrong_size else style_tag(vw, vh))
            route.fulfill(status=200, body=data, headers={"Content-Type": mt})
        pg.route("https://epub.local/**", handle)

        total_pages = 0
        for si, (path, mt) in enumerate(spine):
            layouts.clear()
            pg.goto("https://epub.local/" + urllib.parse.quote(path), wait_until="load")
            pg.evaluate(script_for(vw, vh))
            pg.wait_for_function("window.__einkCount !== undefined")
            pg.wait_for_timeout(150)
            assert layouts, f"未收到 onLayout 回调: {path}"
            g, count = layouts[-1]
            assert g == 7
            # 逐页跳转，验证落点与页内是否有内容
            info = pg.evaluate("""(n)=>{
              const w=window.innerWidth, out=[];
              for(let p=0;p<n;p++){
                const got=__einkGo(p);
                // 统计落在当前视口内的文本行数、被视口边缘截断的文本、最左文本位置
                const r=document.createRange(); let vis=0, cut=0, minLeft=1e9;
                const walker=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                let node; while((node=walker.nextNode())){ if(!node.textContent.trim()) continue;
                  r.selectNodeContents(node);
                  for(const rc of r.getClientRects()){
                    if(rc.width<=0) continue;
                    if(rc.left>=-1 && rc.right<=w+1){vis++; minLeft=Math.min(minLeft, rc.left);}
                    else if((rc.left<0 && rc.right>1) || (rc.left<w-1 && rc.right>w+1)){cut++;}
                  } }
                const imgs=[...document.images].filter(i=>{const rc=i.getBoundingClientRect(); return rc.width>0 && rc.left>=-1 && rc.right<=w+1;}).length;
                const svgs=[...document.querySelectorAll('svg')].filter(i=>{const rc=i.getBoundingClientRect(); return rc.width>0 && rc.left>=-1 && rc.right<=w+1;}).length;
                out.push({p, got, vis, cut, imgs:imgs+svgs, minLeft: vis? Math.round(minLeft): null});
              }
              return {w, sw:document.documentElement.scrollWidth, out, cssw: document.getElementById('__einkcss').getAttribute('data-w')};
            }""", count)
            bad = [o for o in info["out"] if o["cut"] > 0 or (o["vis"] == 0 and o["imgs"] == 0)
                   or (o["minLeft"] is not None and o["minLeft"] < 15)]
            results.append((path, count, info["sw"], bad, info["cssw"]))
            total_pages += count
            # 锚点定位：取本章最后一个带 id 的元素，检查 __einkFind 返回的页上确实能看到它
            anchor = pg.evaluate("""()=>{const els=[...document.body.querySelectorAll('[id]')].filter(e=>e.id!=='__einkcss'); if(!els.length) return null;
                const e=els[els.length-1]; __einkGo(0); const p=__einkFind(e.id); __einkGo(p); const rc=e.getBoundingClientRect();
                return {id:e.id, p, left:Math.round(rc.left), ok: rc.left>=-1 && rc.left<window.innerWidth};}""")
            if anchor and not anchor["ok"]:
                bad.append({"anchor": anchor})
            if si in (0, min(2, len(spine) - 1)) and not wrong_size:
                pg.evaluate("__einkGo(0)")
                pg.screenshot(path=f"{OUT}/{label}_s{si}_p0.png")
                if count > 1:
                    pg.evaluate(f"__einkGo({count-1})")
                    pg.screenshot(path=f"{OUT}/{label}_s{si}_plast.png")
        b.close()
    return results, total_pages

def report(name, res, total):
    bad_all = [(p, c, b) for p, c, sw, b, cw in res if b]
    print(f"== {name}: {len(res)} 个章节, 共 {total} 页, 异常页 {sum(len(b) for _,_,b in bad_all)}")
    for p, c, sw, b, cw in res[:6]:
        print(f"   {p}: pages={c} scrollWidth={sw} css-data-w={cw} bad={b[:2]}")
    return not bad_all

ok = True
ALICE = "/home/ubuntu/KavitaInk/app/src/test/resources/alice.epub"
ZH = "/home/ubuntu/KavitaInk/app/src/test/resources/novel_zh.epub"
r, t_alice = run(ALICE, 632, 840, label="alice")
ok &= report("Alice 桌面 632x840", r, t_alice)
r, t = run(ZH, 536, 714, label="zh")
ok &= report("中文小说 桌面 536x714", r, t)
r, t_m = run(ALICE, 571, 772, label="alice_mobile", mobile=True)
ok &= report("Alice 移动端模拟 571x772 @1.875x", r, t_m)
r, t_zm = run(ZH, 571, 772, label="zh_mobile", mobile=True)
ok &= report("中文小说 移动端模拟 571x772 @1.875x", r, t_zm)
EDGE = "/home/ubuntu/KavitaInk/app/src/test/resources/edge_cases.epub"
r, t_e = run(EDGE, 571, 772, label="edge_mobile", mobile=True)
ok &= report("边界测试 移动端模拟", r, t_e)
print("  边界测试各节页数:", [(p.split("/")[-1], c) for p, c, *_ in r])
ok &= r[0][1] == 1  # 封面末尾强制分页不应产生空白页
r2, t2 = run(ALICE, 632, 840, wrong_size=True, label="alice_fix")
ok &= report("Alice 注入尺寸错误→脚本按原生尺寸校正", r2, t2)
print("尺寸校正后总页数与正确尺寸一致:", t2 == t_alice, t2, t_alice)
ok &= t2 == t_alice
print("RESULT", "PASS" if ok else "FAIL")
