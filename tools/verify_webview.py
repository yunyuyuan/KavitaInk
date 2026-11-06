"""在模拟器的真实 WebView 中逐章逐页校验分页（通过 DevTools 协议）。
前提：EPUB 阅读器已打开（请求仍由 App 的 shouldInterceptRequest 处理，样式注入与真实使用一致）。
用法：python3 verify_webview.py <epub 本地副本> <脚本导出目录> <cssW> <cssH>"""
import json
import re
import sys
import time
import urllib.parse
import zipfile

from cdp_probe import connect, evaluate

epub, dump, W, H = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
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

ws, url = connect()
host = "https://epub.local/"
total, bad_total = 0, 0
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
    bad = [o for o in res["out"] if o[2] > 0 or (o[1] == 0 and o[3] == 0) or (o[4] is not None and o[4] < 15)]
    total += n
    bad_total += len(bad)
    print(f"{path.split('/')[-1][:40]:40s} 页数={n:3d} 视口={res['w']}/{res['iw']} 异常页={bad[:3]}")
print(f"合计 {len(spine)} 章 {total} 页，异常页 {bad_total}")
print("RESULT", "PASS" if bad_total == 0 else "FAIL")
