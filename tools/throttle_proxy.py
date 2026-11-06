"""测试用 TCP 代理：0.0.0.0:5001 -> 127.0.0.1:5000
控制文件 /tmp/proxy.json（每 0.3 秒重新读取）：
  {"rate": 400000, "mode": "ok"}    限速（字节/秒，按连接计）
  {"mode": "drop"}                  断网：立即断开所有连接，并拒绝新连接
  {"mode": "stall"}                 卡死：连接保持，但不转发任何数据（模拟网络假死）
请求日志写入 /tmp/proxy.log：时间、方法、路径、Range 头、本次连接转发的字节数。
"""
import asyncio
import json
import re
import sys
import time

LISTEN = ("0.0.0.0", int(sys.argv[1]) if len(sys.argv) > 1 else 5001)
TARGET = ("127.0.0.1", 5000)
CTRL = "/tmp/proxy.json"
LOG = "/tmp/proxy.log"

state = {"rate": 400_000, "mode": "ok"}
conns = set()
REQ = re.compile(rb"(GET|POST|HEAD|PUT|DELETE) (\S+) HTTP/1\.[01]")
RANGE = re.compile(rb"\r\n[Rr]ange: *bytes=(\S+)")


def log(line):
    with open(LOG, "a") as f:
        f.write(f"{time.strftime('%H:%M:%S')} {line}\n")


async def control():
    last = None
    while True:
        try:
            with open(CTRL) as f:
                txt = f.read()
            if txt != last:
                last = txt
                d = json.loads(txt or "{}")
                state["rate"] = int(d.get("rate", state["rate"]))
                state["mode"] = d.get("mode", "ok")
                log(f"CTRL {state}")
                if state["mode"] == "drop":
                    for w in list(conns):
                        try:
                            w.transport.abort()
                        except Exception:
                            pass
        except FileNotFoundError:
            pass
        except Exception as e:
            log(f"CTRL error {e}")
        await asyncio.sleep(0.3)


async def pipe(reader, writer, upstream, stats):
    try:
        while True:
            data = await reader.read(16 * 1024)
            if not data:
                break
            while state["mode"] == "stall":
                await asyncio.sleep(0.2)
            if state["mode"] == "drop":
                break
            if upstream:
                for m in REQ.finditer(data):
                    rng = RANGE.search(data)
                    log(f"REQ {m.group(1).decode()} {m.group(2).decode()[:110]} range={rng.group(1).decode() if rng else '-'}")
            else:
                stats["down"] += len(data)
                rate = state["rate"]
                if rate > 0:
                    await asyncio.sleep(len(data) / rate)
            writer.write(data)
            await writer.drain()
    except Exception:
        pass
    finally:
        try:
            writer.close()
        except Exception:
            pass


async def handle(cr, cw):
    if state["mode"] == "drop":
        cw.transport.abort()
        return
    try:
        sr, sw = await asyncio.open_connection(*TARGET)
    except Exception:
        cw.close()
        return
    conns.add(cw)
    conns.add(sw)
    stats = {"down": 0}
    await asyncio.gather(pipe(cr, sw, True, stats), pipe(sr, cw, False, stats))
    conns.discard(cw)
    conns.discard(sw)
    log(f"CLOSE down={stats['down']}")


async def main():
    srv = await asyncio.start_server(handle, *LISTEN)
    log(f"LISTEN {LISTEN}")
    await asyncio.gather(srv.serve_forever(), control())


asyncio.run(main())
