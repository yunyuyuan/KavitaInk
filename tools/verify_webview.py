"""在模拟器的真实 WebView 中逐章逐页校验分页（通过 DevTools 协议）。
前提：EPUB 阅读器已打开（请求仍由 App 的 shouldInterceptRequest 处理，样式注入与真实使用一致）。
用法：python3 verify_webview.py <epub 本地副本> <脚本导出目录> <cssW|auto> <cssH|auto>
  auto = 从阅读器当前页读取 App 实际使用的分页尺寸（开了状态栏时高度会变小）。
  环境变量 MINLEFT：可见文字最左位置的下限（默认 15，左右边距调到 8 时用 7）。
另外检查缩进：标题、居中 / 右对齐的段落、只含图片的段落，计算后的 text-indent 必须为 0。"""
import json
import os
import re
import sys
import time
import urllib.parse
import zipfile

from cdp_probe import connect, evaluate

epub, dump, W, H = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
MINLEFT = int(os.environ.get("MINLEFT", "15"))
ws, url = connect()
if W == "auto" or H == "auto":
    dims = evaluate(ws, "[window.__einkW||0, +((document.getElementById('__einkcss')||{getAttribute:function(){return 0}}).getAttribute('data-h')||0)]")
    W, H = (dims[0] if W == "auto" else int(W)), (dims[1] if H == "auto" else int(H))
    print(f"自动尺寸：{W}x{H}")
W, H = int(W), int(H)
SCRIPT = open(f"{dump}/script.js").read().replace("HW=11111", f"HW={W}").replace("HH=22222", f"HH={H}")

z = zipfile.ZipFile(epub)
c = z.read("META-INF/container.xml").decode()
opf = re.search(r'full-path="([^"]+)"', c).group(1)
base = opf.rsplit("/", 1)[0] + "/" if "/" in opf else ""
x = z.read(opf).decode("utf-8")
items = {}
for m in re.finditer(r"<(?:\w+:)?item\b([^>]*)/?>", x):
    a = m.group(1)
    items[re.search(r'\bid="([^"]+)"', a).group(1)] = base + urllib.parse.unquote(re.search(r'\bhref="([^"]+)"', a).group(1))
spine = [items[m.group(1)] for m in re.finditer(r'<(?:\w+:)?itemref\b[^>]*idref="([^"]+)"', x)]

CHECK = """(function(n){
  var w=window.__einkW, out=[];
  for(var p=0;p<n;p++){
    __einkGo(p);
    var r=document.createRange(), vis=0, cut=0, minLeft=1e9;
    var walker=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false), node;
    while((node=walker.nextNode())){
      if(!node.textContent.trim()) continue;
      r.selectNodeContents(node);
      var rs=r.getClientRects();
      for(var i=0;i<rs.length;i++){ var rc=rs[i]; if(rc.width<=0) continue;
        if(rc.left>=-1 && rc.right<=w+1){vis++; if(rc.left<minLeft) minLeft=rc.left;}
        else if((rc.left<0 && rc.right>1)||(rc.left<w-1 && rc.right>w+1)){cut++;}
      }
    }
    var media=0, im=document.querySelectorAll('img,svg');
    for(var j=0;j<im.length;j++){var b=im[j].getBoundingClientRect(); if(b.width>0&&b.left>=-1&&b.right<=w+1) media++;}
    out.push([p,vis,cut,media,vis?Math.round(minLeft):null]);
  }
  __einkGo(0);
  return {w:w, iw:innerWidth, out:out};
})"""

INDENT = """(function(){
  var bad=[], body=0, els=document.querySelectorAll('p,div,h1,h2,h3,h4,h5,h6,li,blockquote');
  for(var i=0;i<els.length;i++){
    var e=els[i], cs=getComputedStyle(e), ti=parseFloat(cs.textIndent)||0;
    var tag=e.tagName.toLowerCase(), txt=(e.textContent||'').trim();
    var onlyImg=!txt && e.querySelector('img,svg,image');
    var heading=/^h[1-6]$/.test(tag), centered=(cs.textAlign=='center'||cs.textAlign=='right'||cs.textAlign=='-webkit-center'||cs.textAlign=='end');
    if((heading||centered||onlyImg) && Math.abs(ti)>0.5) bad.push(tag+':'+cs.textAlign+':'+ti+':'+txt.slice(0,20));
    if(tag=='p' && txt && !centered && ti>0.5) body++;
  }
  return {bad:bad.slice(0,5), nbad:bad.length, indented:body};
})()"""
host = "https://epub.local/"
total, bad_total, indented_total = 0, 0, 0
for path in spine:
    u = host + "/".join(urllib.parse.quote(s) for s in path.split("/"))
    ws.send(json.dumps({"id": 900000, "method": "Page.navigate", "params": {"url": u}}))
    time.sleep(4)
    for _ in range(30):
        if evaluate(ws, "document.readyState") == "complete":
            break
        time.sleep(1)
    evaluate(ws, "window.EinkBridge=window.EinkBridge||{onLayout:function(){}};" + SCRIPT)
    time.sleep(1.5)
    n = evaluate(ws, "__einkCount()")
    res = evaluate(ws, CHECK + f"({n})")
    bad = [o for o in res["out"] if o[2] > 0 or (o[1] == 0 and o[3] == 0) or (o[4] is not None and o[4] < MINLEFT)]
    ind = evaluate(ws, INDENT)
    total += n
    # 「标题 / 居中 / 纯图片段落不缩进」是自定义排版的规则；书籍原排版下缩进由书自己的 CSS 决定，不计入异常
    bad_total += len(bad) + (ind["nbad"] if os.environ.get("INDENT_CHECK", "1") == "1" else 0)
    indented_total += ind["indented"]
    print(f"{path.split('/')[-1][:40]:40s} 页数={n:3d} 视口={res['w']}/{res['iw']} 异常页={bad[:3]} 缩进段={ind['indented']} 不应缩进却缩进={ind['bad']}")
print(f"合计 {len(spine)} 章 {total} 页，异常 {bad_total}，有首行缩进的正文段落 {indented_total}")
print("RESULT", "PASS" if bad_total == 0 else "FAIL")
