#!/usr/bin/env python3
"""BiliPicks 数据更新脚本（只用 Python 标准库，在自己电脑上运行）。

数据都在 data.js 的 `const DATA = {...};` 这一行里，本脚本读它、改它、写回去。

用法（在仓库根目录运行）：
  python3 update.py                 刷新：检查失效视频，补封面和时长，更新频道榜的粉丝数
  python3 update.py --check         只检查、出报告，不改文件
  python3 update.py --deploy        刷新后 git 提交并推送（托管在 GitHub Pages / Cloudflare Pages 时会自动上线）
  python3 update.py --skip-tiers    刷新时跳过频道榜（少发几十个请求）

  python3 update.py add BV1xxxxxxxxx --cat 数学 --age 10 --reason "推荐理由" \
      [--caution "家长提示"] [--page 2] [--stage 3 --note "在成长路线里的说明"] \
      [--title "自己起的标题"] [--orig "原作者"]
      加一条视频：标题、UP 主、封面、时长自动从 B 站读取。
      --title  B 站标题太长或太标题党时，换一个自己写的标题
      --orig   搬运、译制的视频写上原作者（比如 "Andrej Karpathy"），页面会标出来
      --page   多 P 视频默认播第几 P（比如 Mark Rober 的中文配音在第 2P）
      --stage  同时加进「成长路线」第几阶段（从 1 开始数）

请求 B 站公开接口时会放慢节奏（每个请求间隔约半秒），只读公开信息，不登录。
"""
import argparse
import datetime
import html
import json
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
INDEX = HERE / "data.js"
UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
DATA_RE = re.compile(r"^const DATA = (\{.*\});$", re.M)
PAUSE = 0.5  # 每个请求之间停一下，别给 B 站添麻烦

# B 站接口常见错误码 → 人话
ERRORS = {
    -404: "视频不存在或已删除",
    62002: "稿件不可见",
    62004: "稿件审核中",
    62012: "仅 UP 主自己可见",
}


# ---------- 读写数据 ----------

def load():
    text = INDEX.read_text(encoding="utf-8")
    m = DATA_RE.search(text)
    if not m:
        sys.exit("在 data.js 里找不到 `const DATA = {...};` 这一行")
    return text, json.loads(m.group(1))


def save(text, data):
    line = "const DATA = " + json.dumps(data, ensure_ascii=False) + ";"
    new = DATA_RE.sub(lambda _: line, text, count=1)
    INDEX.write_text(new, encoding="utf-8")
    return new != text


# ---------- 请求 B 站 ----------

def fetch(url, referer="https://www.bilibili.com/"):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": referer})
    last = None
    for attempt in range(2):
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                return r.read().decode("utf-8", "replace")
        except Exception as e:  # 网络抖动就再试一次
            last = e
            time.sleep(2)
    raise RuntimeError(f"请求失败：{url}（{last}）")


def view(bvid):
    """返回 (code, data)。code == 0 表示视频正常。"""
    raw = fetch("https://api.bilibili.com/x/web-interface/view?bvid=" + urllib.parse.quote(bvid))
    time.sleep(PAUSE)
    j = json.loads(raw)
    return j.get("code", -1), j.get("data") or {}


def cover_url(pic):
    if not pic:
        return ""
    if pic.startswith("//"):
        pic = "https:" + pic
    if pic.startswith("http://"):
        pic = "https://" + pic[7:]
    u = urllib.parse.urlsplit(pic)
    if not re.search(r"(^|\.)(hdslb|biliimg)\.com$", u.hostname or ""):
        return ""
    # B 站 CDN 会按尺寸现场缩图：1080p 封面从约 120KB 降到约 20KB
    return f"{u.scheme}://{u.netloc}{u.path}@672w_378h_1c.webp"


def search_user(name):
    """在 B 站搜索页按名字找 UP 主，返回 [{mid, name, stats}]。"""
    raw = fetch("https://search.bilibili.com/upuser?keyword=" + urllib.parse.quote(name),
                "https://search.bilibili.com/")
    time.sleep(PAUSE)
    out = []
    for part in raw.split("b-user-info-card")[1:]:
        m = re.search(r"space\.bilibili\.com/(\d+)", part)
        if not m:
            continue
        body = part[part.find(">") + 1:][:4000]
        bits = [b.strip() for b in html.unescape(re.sub(r"<[^>]+>", "|", body)).split("|") if b.strip()]
        stats = next((b for b in bits if "粉丝" in b), "")
        out.append({"mid": m.group(1), "name": bits[0] if bits else "", "stats": stats})
    return out


def parse_stats(stats):
    """'278.5万粉丝 · 150个视频' → ('278.5 万', 150)"""
    fans = re.search(r"([\d.]+万?)粉丝", stats)
    vids = re.search(r"(\d+)个视频", stats)
    f = fans.group(1).replace("万", " 万") if fans else ""
    return f, int(vids.group(1)) if vids else None


# ---------- 刷新 ----------

def refresh_videos(data, report):
    vids = data["videos"]
    for i, v in enumerate(vids, 1):
        bv = v["id"]
        print(f"\r  视频 {i}/{len(vids)} {bv}", end="", flush=True)
        try:
            code, d = view(bv)
        except RuntimeError as e:
            report["warn"].append(f"{bv} {e}")
            continue
        if code != 0:
            if not v.get("dead"):
                report["dead"].append(f"{bv}《{v['title']}》：{ERRORS.get(code, f'错误码 {code}')}")
            v["dead"] = True
            continue
        if v.pop("dead", None):
            report["revived"].append(f"{bv}《{v['title']}》")
        pic = cover_url(d.get("pic", ""))
        if pic:
            v["cover"] = pic
        page = int(v.get("page") or 1)
        pages = d.get("pages") or []
        dur = pages[page - 1]["duration"] if 0 < page <= len(pages) else d.get("duration")
        if dur:
            v["duration"] = dur
        if len(pages) > 1:
            v["parts"] = len(pages)
        else:
            v.pop("parts", None)
        owner = (d.get("owner") or {}).get("name", "")
        if owner and owner != v["channel"]:
            report["renamed"].append(f"{bv}：页面写的是「{v['channel']}」，B 站现在是「{owner}」")
        if d.get("title") and d["title"] != v["title"] and not v.get("titleLocked"):
            report["retitled"].append(f"{bv}：「{v['title']}」→「{d['title']}」（没改，需要的话手动改）")
    print()


def refresh_tiers(data, report):
    rows = [r for t in data.get("tiers", []) for r in t["items"]] + data.get("originals", [])
    for i, r in enumerate(rows, 1):
        print(f"\r  频道 {i}/{len(rows)} {r['name']}" + " " * 10, end="", flush=True)
        try:
            hits = search_user(r["name"])
        except RuntimeError as e:
            report["warn"].append(f"{r['name']} {e}")
            continue
        hit = next((h for h in hits if h["mid"] == str(r["mid"])), None)
        if not hit:
            report["warn"].append(f"频道榜：按名字「{r['name']}」没搜到 mid {r['mid']}，可能改名了，去 https://space.bilibili.com/{r['mid']} 看看")
            continue
        fans, n = parse_stats(hit["stats"])
        if fans:
            r["fans"] = fans
        if n is not None:
            r["videos"] = n
        if hit["name"] and hit["name"] != r["name"]:
            report["renamed"].append(f"频道榜：「{r['name']}」现在叫「{hit['name']}」")
    print()


def print_report(report):
    labels = {
        "dead": "失效（页面上已自动隐藏，可以换一条）",
        "revived": "恢复可看",
        "renamed": "UP 主改名",
        "retitled": "标题变了",
        "warn": "其他提醒",
    }
    any_ = False
    for k, label in labels.items():
        if report[k]:
            any_ = True
            print(f"\n{label}：")
            for line in report[k]:
                print("  - " + line)
    if not any_:
        print("\n一切正常。")


def deploy(msg):
    root = subprocess.run(["git", "rev-parse", "--show-toplevel"], cwd=HERE,
                          capture_output=True, text=True, check=True).stdout.strip()
    rel = str(INDEX.relative_to(root))
    if not subprocess.run(["git", "status", "--porcelain", rel], cwd=root,
                          capture_output=True, text=True).stdout.strip():
        print("没有变化，不用部署。")
        return
    subprocess.run(["git", "add", rel], cwd=root, check=True)
    subprocess.run(["git", "commit", "-m", msg], cwd=root, check=True)
    subprocess.run(["git", "push"], cwd=root, check=True)
    print("已推送。如果网站托管在 GitHub Pages / Cloudflare Pages，一两分钟后自动上线。")


def cmd_refresh(args):
    text, data = load()
    report = {k: [] for k in ("dead", "revived", "renamed", "retitled", "warn")}
    print("检查视频…")
    refresh_videos(data, report)
    if not args.skip_tiers and (data.get("tiers") or data.get("originals")):
        print("更新频道榜…")
        refresh_tiers(data, report)
    data["built"] = datetime.date.today().isoformat()
    print_report(report)
    if args.check:
        print("\n（--check：没有写入文件）")
        return
    changed = save(text, data)
    print("\n已写入 data.js" if changed else "\n数据没有变化")
    if args.deploy:
        deploy(f"BiliPicks: 数据刷新 {data['built']}")


def cmd_add(args):
    text, data = load()
    bv = args.bvid
    if not re.fullmatch(r"BV1[0-9A-Za-z]{9}", bv):
        sys.exit(f"{bv} 不像 BV 号（应为 BV1 开头、共 12 位）")
    if any(v["id"] == bv for v in data["videos"]):
        sys.exit(f"{bv} 已经在列表里了")
    if args.cat not in data["categories"]:
        sys.exit(f"分类「{args.cat}」不存在，可选：{'、'.join(data['categories'])}")
    code, d = view(bv)
    if code != 0:
        sys.exit(f"{bv} 读不到：{ERRORS.get(code, f'错误码 {code}')}")
    owner = d["owner"]
    page = args.page or 1
    pages = d.get("pages") or []
    if page > len(pages):
        sys.exit(f"{bv} 只有 {len(pages)} P，没有第 {page} P")
    v = {
        "id": bv,
        "title": args.title or d["title"],
        "url": f"https://www.bilibili.com/video/{bv}" + (f"?p={page}" if page > 1 else ""),
        "channel": owner["name"],
        "channelUrl": f"https://space.bilibili.com/{owner['mid']}",
        "category": args.cat,
        "minAge": args.age,
        "caution": args.caution or "",
        "reason": args.reason,
        "cover": cover_url(d.get("pic", "")),
        "duration": pages[page - 1]["duration"] if pages else d.get("duration"),
    }
    if args.title:
        v["titleLocked"] = True  # 标题是自己写的，刷新时不再提醒「标题变了」
    if args.orig:
        v["orig"] = args.orig
    if page > 1:
        v["page"] = page
    if len(pages) > 1:
        v["parts"] = len(pages)
    data["videos"].append(v)
    if not any(c["name"] == owner["name"] for c in data["channels"]):
        data["channels"].append({"name": owner["name"], "url": v["channelUrl"], "category": args.cat,
                                 "minAge": args.age, "caution": "", "reason": "", "hasVideos": True})
    if args.stage:
        stages = data.get("path", {}).get("stages", [])
        if not 1 <= args.stage <= len(stages):
            sys.exit(f"成长路线只有 {len(stages)} 个阶段")
        stages[args.stage - 1]["items"].append({"id": bv, "note": args.note or args.reason})
    data["built"] = datetime.date.today().isoformat()
    save(text, data)
    m, s = divmod(v["duration"] or 0, 60)
    print(f"已加入：{v['title']}（{owner['name']}，{m}:{s:02d}）→ {args.cat}"
          + (f"，成长路线第 {args.stage} 阶段" if args.stage else ""))
    if args.deploy:
        deploy(f"BiliPicks: 加入 {bv}")


def main():
    p = argparse.ArgumentParser(description="BiliPicks 数据更新")
    p.add_argument("--check", action="store_true", help="只检查不写入")
    p.add_argument("--deploy", action="store_true", help="写入后 git 提交并推送")
    p.add_argument("--skip-tiers", action="store_true", help="不刷新频道榜")
    sub = p.add_subparsers(dest="cmd")
    a = sub.add_parser("add", help="加一条视频")
    a.add_argument("bvid")
    a.add_argument("--cat", required=True, help="分类")
    a.add_argument("--age", type=int, default=10, help="建议年龄，默认 10")
    a.add_argument("--reason", required=True, help="推荐理由")
    a.add_argument("--caution", help="家长提示")
    a.add_argument("--page", type=int, help="默认播第几 P")
    a.add_argument("--stage", type=int, help="加进成长路线第几阶段")
    a.add_argument("--note", help="在成长路线里的说明，默认用推荐理由")
    a.add_argument("--title", help="自己写的标题，默认用 B 站标题")
    a.add_argument("--orig", help="搬运、译制视频的原作者")
    a.add_argument("--deploy", action="store_true", help="加完后 git 提交并推送")
    args = p.parse_args()
    if args.cmd == "add":
        cmd_add(args)
    else:
        cmd_refresh(args)


if __name__ == "__main__":
    main()
