"""通过 adb 转发 WebView DevTools，在模拟器的真实 WebView 中执行诊断 JS。
用法：python3 cdp_probe.py '<js 表达式>'"""
import json
import re
import subprocess
import sys
import time
import urllib.request

import websocket

ADB = "/home/ubuntu/android-sdk/platform-tools/adb"
PKG = "net.yunyuyuan.kavitaink"


def connect():
    pid = subprocess.run([ADB, "shell", "pidof", PKG], capture_output=True, text=True).stdout.strip()
    if not pid:
        raise SystemExit("app not running")
    socks = subprocess.run([ADB, "shell", "cat /proc/net/unix"], capture_output=True, text=True).stdout
    names = sorted(set(re.findall(r"@(webview_devtools_remote_\d+)", socks)))
    name = f"webview_devtools_remote_{pid}" if f"webview_devtools_remote_{pid}" in names else (names[-1] if names else None)
    if not name:
        raise SystemExit(f"no devtools socket; pid={pid}")
    subprocess.run([ADB, "forward", "tcp:9333", f"localabstract:{name}"], check=True)
    for _ in range(10):
        try:
            pages = json.load(urllib.request.urlopen("http://127.0.0.1:9333/json"))
            break
        except Exception:
            time.sleep(1)
    page = [p for p in pages if p.get("type") == "page"][0]
    ws = websocket.create_connection(page["webSocketDebuggerUrl"], timeout=60, suppress_origin=True)
    return ws, page["url"]


def evaluate(ws, expr, _id=[0]):
    _id[0] += 1
    ws.send(json.dumps({"id": _id[0], "method": "Runtime.evaluate",
                        "params": {"expression": expr, "returnByValue": True}}))
    while True:
        m = json.loads(ws.recv())
        if m.get("id") == _id[0]:
            r = m.get("result", {})
            if "exceptionDetails" in r:
                return {"exception": r["exceptionDetails"].get("text"), "detail": r.get("result", {}).get("description")}
            return r.get("result", {}).get("value")


if __name__ == "__main__":
    ws, url = connect()
    print("URL:", url)
    print(json.dumps(evaluate(ws, sys.argv[1]), ensure_ascii=False, indent=1))
