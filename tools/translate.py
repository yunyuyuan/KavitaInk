#!/usr/bin/env python3
"""
把 res/values/strings*.xml（英文原文）翻译成其它语言，写到 res/values-<q>/ 下同名文件。

- 增量：tools/i18n_cache.json 记录每条英文原文的哈希和译文，只翻译新增或改动的条目
- 校验：占位符（%1$s 等）必须一致；专有名词（KavitaInk、Kavita、EPUB…）必须保留；不合格的重翻一次
- 复数：按各语言 CLDR 规则生成需要的数量类别
- values-zh/（简体中文）是人工维护的，脚本只把它当作参考上下文，不会改写

用法（需要 OpenAI 兼容接口：OPENAI_API_KEY / OPENAI_API_BASE）：
    python3 tools/translate.py              # 翻译全部语言
    python3 tools/translate.py ja fr        # 只翻译指定语言
    python3 tools/translate.py --check      # 只检查是否有待翻译条目（CI 用），有则退出码 1
"""
import hashlib
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
CACHE = os.path.join(ROOT, "tools/i18n_cache.json")
MODEL = os.environ.get("TRANSLATE_MODEL", "gpt-6.1-sol")

# 语言限定符 → (提示词里的语言名, 需要的复数类别)
LANGS = {
    "zh-rTW": ("Traditional Chinese as used in Taiwan (繁體中文，台灣用語)", ["other"]),
    "ja": ("Japanese", ["other"]),
    "ko": ("Korean", ["other"]),
    "es": ("Spanish (neutral, Latin American friendly)", ["one", "many", "other"]),
    "fr": ("French", ["one", "many", "other"]),
    "de": ("German", ["one", "other"]),
    "ru": ("Russian", ["one", "few", "many", "other"]),
    "pt-rBR": ("Brazilian Portuguese", ["one", "many", "other"]),
}
# 生成后原样复制的语言。香港 / 澳门繁体不单独复制：Android 7+ 按文字脚本（Hant）自动匹配 values-zh-rTW，
# Android 5 / 6 由 Lang.wrap 改用 zh-TW（复制一份会让 resources.arsc 多出约 9 KB，而它必须不压缩存储）
COPIES = {}

KEEP_TERMS = ["KavitaInk", "Kavita", "Auth Key", "OPDS", "EPUB", "PDF", "CBZ", "CBR", "GitHub", "WebView",
              "TTF", "OTF", "WOFF2", "WOFF", "API"]

PH = re.compile(r"%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc]")


def placeholders(s):
    return sorted(m.group(0) for m in PH.finditer(s))


# ---------------- Android 字符串编解码 ----------------

def decode(raw):
    out, quoted, last_space, i = [], False, False, 0
    s = raw.strip()
    while i < len(s):
        ch = s[i]
        if ch == "\\" and i + 1 < len(s):
            n = s[i + 1]
            out.append({"n": "\n", "t": "\t"}.get(n, n))
            i += 2
            last_space = False
            continue
        if ch == '"':
            quoted = not quoted
        elif ch.isspace() and not quoted:
            if not last_space:
                out.append(" ")
            last_space = True
            i += 1
            continue
        else:
            out.append(ch)
        last_space = False
        i += 1
    return "".join(out)


def encode(text):
    s = text.replace("\\", "\\\\").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t")
    if s.startswith("@") or s.startswith("?"):
        s = "\\" + s
    # 首尾空格会被 Android 去掉，加引号保留
    if s != s.strip():
        s = '"' + s + '"'
    # 连续空格在 Android 里本来就会被合并
    s = re.sub(r"  +", " ", s)
    return s


def inner_text(el):
    return (el.text or "") + "".join(ET.tostring(c, encoding="unicode") for c in el)


def load_dir(d):
    """返回 {文件名: [(kind, name, value)]}，kind = string / plurals；plurals 的 value 是 {quantity: text}"""
    out = {}
    path = os.path.join(RES, d)
    if not os.path.isdir(path):
        return out
    for fn in sorted(os.listdir(path)):
        if not (fn.startswith("strings") and fn.endswith(".xml")):
            continue
        root = ET.parse(os.path.join(path, fn)).getroot()
        items = []
        for el in root:
            if el.tag == "string":
                if el.get("translatable") == "false":
                    continue
                items.append(("string", el.get("name"), decode(inner_text(el))))
            elif el.tag == "plurals":
                items.append(("plurals", el.get("name"), {i.get("quantity"): decode(inner_text(i)) for i in el.findall("item")}))
        out[fn] = items
    return out


def write_dir(q, files, translations):
    d = os.path.join(RES, "values-" + q)
    os.makedirs(d, exist_ok=True)
    for fn, items in files.items():
        lines = ['<?xml version="1.0" encoding="utf-8"?>',
                 "<!-- 由 tools/translate.py 生成；修改英文原文后重新运行脚本即可更新 -->",
                 "<resources>"]
        for kind, name, _ in items:
            t = translations.get(name)
            if t is None:
                continue
            if kind == "string":
                lines.append(f'    <string name="{name}">{encode(t)}</string>')
            else:
                lines.append(f'    <plurals name="{name}">')
                for qty in ["zero", "one", "two", "few", "many", "other"]:
                    if qty in t:
                        lines.append(f'        <item quantity="{qty}">{encode(t[qty])}</item>')
                lines.append("    </plurals>")
        lines.append("</resources>")
        with open(os.path.join(d, fn), "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")


# ---------------- 翻译 ----------------

def src_hash(value):
    return hashlib.sha1(json.dumps(value, ensure_ascii=False, sort_keys=True).encode()).hexdigest()[:12]


def validate(kind, en, zh, t, need):
    if kind == "string":
        if not isinstance(t, str) or not t.strip():
            return "empty"
        if placeholders(t) != placeholders(en):
            return f"placeholders {placeholders(en)} vs {placeholders(t)}"
        for term in KEEP_TERMS:
            if term in en and term not in t:
                return f"missing term {term}"
        return None
    if not isinstance(t, dict):
        return "plural not dict"
    want = placeholders(en["other"])
    for qty in need:
        if not t.get(qty, "").strip():
            return f"missing quantity {qty}"
    for qty, s in t.items():
        if placeholders(s) != want:
            return f"plural {qty} placeholders {want} vs {placeholders(s)}"
    return None


SYSTEM = """You translate Android UI strings for KavitaInk, a minimal offline reader app for E Ink devices that downloads comics and novels from a self-hosted Kavita server.
Rules:
- Translate from English. A Simplified Chinese version is given as a second reference for meaning; follow the English when they differ.
- Keep every printf placeholder exactly as written (%1$s, %2$d, %1$s%% etc.), including ordinal numbers; you may reorder them.
- Never translate these names: KavitaInk, Kavita, Auth Key, OPDS, EPUB, PDF, CBZ, CBR, GitHub, WebView, TTF, OTF, WOFF, WOFF2, API.
- Keep symbols such as ▶ ◀ → · … • × and the line breaks (\\n) of the source.
- Be concise: these are button labels, menu items and short messages on small screens. Use the standard terminology of Android apps and e-reader apps in the target language (e.g. "chapter", "volume", "download", "offline").
- Use a consistent, polite but neutral tone; no exclamation marks unless the source has them.
- For plurals, return every requested quantity category, each containing all placeholders of the source.
Return JSON only, matching the schema."""

SCHEMA = {
    "type": "object",
    "properties": {
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "quantity": {"type": "string", "enum": ["", "zero", "one", "two", "few", "many", "other"]},
                    "text": {"type": "string"},
                },
                "required": ["name", "quantity", "text"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["items"],
    "additionalProperties": False,
}


def ask(client, lang_name, need, batch, retry_notes=None):
    entries = []
    for kind, name, en, zh in batch:
        e = {"name": name, "type": kind, "en": en}
        if zh is not None:
            e["zh_hans"] = zh
        if kind == "plurals":
            e["quantities_needed"] = need
        if retry_notes and name in retry_notes:
            e["previous_problem"] = retry_notes[name]
        entries.append(e)
    user = (f"Target language: {lang_name}.\n"
            f"For type=string return one item with quantity \"\". For type=plurals return one item per needed quantity.\n"
            f"Strings:\n{json.dumps(entries, ensure_ascii=False, indent=1)}")
    resp = client.chat.completions.create(
        model=MODEL,
        messages=[{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}],
        response_format={"type": "json_schema", "json_schema": {"name": "translations", "strict": True, "schema": SCHEMA}},
        extra_body={"reasoning": {"effort": "low"}},
    )
    data = json.loads(resp.choices[0].message.content)
    out = {}
    for it in data["items"]:
        if it["quantity"]:
            out.setdefault(it["name"], {})
            if isinstance(out[it["name"]], dict):
                out[it["name"]][it["quantity"]] = it["text"]
        else:
            out[it["name"]] = it["text"]
    return out, resp.usage


def translate_lang(client, q, files, zh_map, cache):
    lang_name, need = LANGS[q]
    c = cache.setdefault(q, {})
    result = {}
    todo_by_file = {}
    for fn, items in files.items():
        for kind, name, en in items:
            h = src_hash(en)
            hit = c.get(name)
            if hit and hit.get("src") == h and validate(kind, en, None, hit.get("t"), need) is None:
                result[name] = hit["t"]
            else:
                todo_by_file.setdefault(fn, []).append((kind, name, en, zh_map.get(name)))
    usage = [0, 0]
    for fn, batch in todo_by_file.items():
        notes = None
        pending = batch
        for attempt in range(3):
            got, u = ask(client, lang_name, need, pending, notes)
            usage[0] += u.prompt_tokens
            usage[1] += u.completion_tokens
            notes = {}
            nxt = []
            for kind, name, en, zh in pending:
                t = got.get(name)
                if kind == "plurals" and isinstance(t, dict):
                    # 额外返回的类别保留；缺 other 时不合格
                    pass
                err = validate(kind, en, zh, t, need)
                if err is None:
                    result[name] = t
                    c[name] = {"src": src_hash(en), "t": t}
                else:
                    notes[name] = err
                    nxt.append((kind, name, en, zh))
            pending = nxt
            if not pending:
                break
        if pending:
            raise RuntimeError(f"{q}/{fn}: 仍有不合格条目：" + ", ".join(f"{n}: {notes.get(n)}" for _, n, _, _ in pending))
        print(f"  {q} {fn}: 翻译 {len(batch)} 条", flush=True)
    # 清理已删除的键
    alive = {name for items in files.values() for _, name, _ in items}
    for k in list(c.keys()):
        if k not in alive:
            del c[k]
    return result, usage


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    check_only = "--check" in sys.argv
    files = load_dir("values")
    zh = {name: v for items in load_dir("values-zh").values() for _, name, v in items}
    cache = json.load(open(CACHE, encoding="utf-8")) if os.path.exists(CACHE) else {}
    langs = args or list(LANGS)

    if check_only:
        missing = 0
        for q in LANGS:
            c = cache.get(q, {})
            for items in files.values():
                for kind, name, en in items:
                    if c.get(name, {}).get("src") != src_hash(en):
                        missing += 1
                        print(f"待翻译：{q}/{name}")
        sys.exit(1 if missing else 0)

    from openai import OpenAI
    client = OpenAI()
    total = [0, 0]

    def job(q):
        res, u = translate_lang(client, q, files, zh, cache)
        write_dir(q, files, res)
        return q, u

    with ThreadPoolExecutor(max_workers=4) as ex:
        for q, u in ex.map(job, langs):
            total[0] += u[0]
            total[1] += u[1]
            print(f"{q} 完成（输入 {u[0]} / 输出 {u[1]} tokens）", flush=True)

    for dst, srcq in COPIES.items():
        if srcq in langs:
            write_dir(dst, files, {name: cache[srcq][name]["t"] for items in files.values() for _, name, _ in items if name in cache[srcq]})
            print(f"{dst} 复制自 {srcq}")
    with open(CACHE, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False, indent=1, sort_keys=True)
        f.write("\n")
    print(f"合计 tokens：输入 {total[0]}，输出 {total[1]}")


if __name__ == "__main__":
    main()
