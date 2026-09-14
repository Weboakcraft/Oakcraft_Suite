#!/usr/bin/env python3
"""
OakCraft Suite - catalogue auto-sync.

Owner ke saare repos scan karta hai. Jis repo ke latest release me ek .apk hai,
wo apne aap www/apps.json me aa jaata hai. Package name, app ka naam, version
aur icon - sab APK ke andar se hi (aapt) nikaale jaate hain, isliye naya app
jodne ke liye kuch bhi haath se likhna nahi padta.

Jo cheezein aap khud control karna chahte hain wo www/apps.overrides.json me
likhein (naam, desc, colour, icon, ignore list, order). Wo file kabhi
overwrite nahi hoti - har sync ke baad usi ki values upar rehti hain.
"""

import io
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile

OWNER = os.environ.get("OWNER", "Weboakcraft")
TOKEN = os.environ.get("GITHUB_TOKEN", "")
AAPT = os.environ.get("AAPT", "aapt")

ROOT = pathlib.Path(__file__).resolve().parents[1]
WWW = ROOT / "www"
ICONS = WWW / "icons"
CATALOG = WWW / "apps.json"
OVERRIDES = WWW / "apps.overrides.json"
CACHE = ROOT / "tools" / "sync-cache.json"

# Debug / test builds ko chhod kar koi bhi .apk. Version badalne par bhi
# chalta rehta hai, isliye asset ka poora naam pin nahi karte.
ASSET_RE = r"^(?!.*(?:debug|testing)).+\.apk$"
DEFAULT_COLOR = "#0F1729"

log = lambda *a: print(*a, flush=True)


# ---------------------------------------------------------------- github ---
def gh(path):
    url = path if path.startswith("http") else "https://api.github.com" + path
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "oakcraft-suite-sync",
        **({"Authorization": "Bearer " + TOKEN} if TOKEN else {}),
    })
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        log("  ! %s -> HTTP %s" % (url, e.code))
        return None
    except Exception as e:                                    # noqa: BLE001
        log("  ! %s -> %s" % (url, e))
        return None


def list_repos():
    """Owner ke saare public repos - account user ho ya organization."""
    for kind in ("orgs", "users"):
        out, page = [], 1
        while True:
            batch = gh("/%s/%s/repos?per_page=100&page=%d" % (kind, OWNER, page))
            if batch is None:
                break
            out += batch
            if len(batch) < 100:
                return out
            page += 1
        if out:
            return out
    return []


def download(url, dest):
    req = urllib.request.Request(url, headers={"User-Agent": "oakcraft-suite-sync"})
    with urllib.request.urlopen(req, timeout=300) as r, open(dest, "wb") as f:
        shutil.copyfileobj(r, f)


# ---------------------------------------------------------------- picking ---
def pick_asset(assets):
    apks = [a for a in assets or [] if a.get("name", "").lower().endswith(".apk")]
    apks = [a for a in apks if "testing" not in a["name"].lower()]
    if not apks:
        return None

    def rank(a):
        n = a["name"].lower()
        return 0 if "release" in n else (2 if "debug" in n else 1)

    apks.sort(key=lambda a: (rank(a), a["name"]))
    return apks[0]


def pick_release(full_name):
    rels = gh("/repos/%s/releases?per_page=10" % full_name) or []
    for rel in rels:
        if rel.get("draft"):
            continue
        asset = pick_asset(rel.get("assets"))
        if asset:
            return rel, asset
    return None, None


# ------------------------------------------------------------------ aapt ---
def badging(apk):
    try:
        return subprocess.run([AAPT, "dump", "badging", str(apk)],
                              capture_output=True, text=True, timeout=120).stdout
    except Exception as e:                                    # noqa: BLE001
        log("  ! aapt failed: %s" % e)
        return ""


def parse_badging(txt):
    out = {}
    m = re.search(r"package: name='([^']+)'", txt)
    if m:
        out["package"] = m.group(1)
    m = re.search(r"versionCode='(\d+)'", txt)
    if m:
        out["versionCode"] = m.group(1)
    m = re.search(r"versionName='([^']*)'", txt)
    if m:
        out["versionName"] = m.group(1)
    m = re.search(r"application-label:'([^']*)'", txt)
    if m and m.group(1).strip():
        out["label"] = m.group(1).strip()
    icons = re.findall(r"application-icon-(\d+):'([^']+)'", txt)
    icons.sort(key=lambda t: -int(t[0]))
    paths = [p for _, p in icons]
    m = re.search(r"application:[^\n]*icon='([^']+)'", txt)
    if m:
        paths.append(m.group(1))
    out["iconPaths"] = paths
    return out


# ------------------------------------------------------------------ icon ---
def save_icon(apk, icon_paths, dest):
    """APK ke andar se launcher icon nikaal kar 160x160 PNG bana deta hai."""
    from PIL import Image

    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        want = [p for p in icon_paths if p.lower().endswith(".png") and p in names]
        if not want:
            pngs = [n for n in names
                    if re.search(r"(mipmap|drawable)[^/]*/.*(ic_launcher|icon)[^/]*\.png$", n, re.I)]
            pngs.sort(key=lambda n: z.getinfo(n).file_size, reverse=True)
            want = pngs[:1]
        for p in want:
            try:
                im = Image.open(io.BytesIO(z.read(p))).convert("RGBA")
            except Exception:                                 # noqa: BLE001
                continue
            if min(im.size) < 48:
                continue
            bg = dominant_color(im)
            flat = Image.new("RGBA", im.size, bg + (255,))
            flat.alpha_composite(im)
            flat.resize((160, 160), Image.LANCZOS).save(dest, optimize=True)
            return "#%02X%02X%02X" % bg
    return None


def dominant_color(im):
    """Icon ka sabse gehra prominent colour - tile ke background ke liye."""
    small = im.resize((32, 32)).convert("RGBA")
    counts = {}
    for r, g, b, a in small.getdata():
        if a < 200:
            continue
        key = (r // 32 * 32, g // 32 * 32, b // 32 * 32)
        counts[key] = counts.get(key, 0) + 1
    if not counts:
        return (15, 23, 41)
    top = sorted(counts.items(), key=lambda kv: -kv[1])[:5]
    return min((c for c, _ in top), key=lambda c: sum(c))


# ------------------------------------------------------------- versioning ---
SEMVER_TAG = re.compile(r"^v?\d+(\.\d+)+$")


def detect_workflow(full_name, rel, info):
    """
    Jin repos me ek hi 'latest' release hoti hai (file overwrite hoti rehti hai),
    unka versionCode = us build ke workflow run ka number hota hai. Yahan pata
    lagate hain ki kaunsi workflow file thi - APK ka versionCode us workflow ke
    successful run number se match karke.
    """
    body = rel.get("body") or ""
    tag = rel.get("tag_name") or ""
    vcode, vname = info.get("versionCode"), info.get("versionName") or ""
    if re.search(r"versionCode\s*[:=]\s*\d+", body):
        return None, None                     # release notes me hi likha hai
    if SEMVER_TAG.match(tag):
        return None, None                     # tag hi version hai
    if not vcode or not vname.endswith("." + vcode):
        return None, None                     # run-number scheme nahi lagta
    wfs = (gh("/repos/%s/actions/workflows" % full_name) or {}).get("workflows", [])
    for wf in wfs:
        name = wf.get("path", "").split("/")[-1]
        runs = gh("/repos/%s/actions/workflows/%s/runs?status=success&per_page=30"
                  % (full_name, name)) or {}
        if any(r.get("run_number") == int(vcode) for r in runs.get("workflow_runs", [])):
            return name, vname[: -len(vcode)] + "{run}"
    return None, None


# ----------------------------------------------------------------- helpers ---
def slug(s):
    return re.sub(r"-+", "-", re.sub(r"[^a-z0-9]+", "-", s.lower())).strip("-") or "app"


def load(path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:                                         # noqa: BLE001
        return default


OVERRIDE_HELP = (
    "Ye file aap ke control me hai - auto-sync ise kabhi nahi badalta. Yahan likhi "
    "har cheez APK se nikaali gayi value ke upar lag jaati hai. 'ignore' me repo ka "
    "naam daalne se wo Suite me nahi aayega. 'order' tiles ka kram set karta hai. "
    "'apps' ki key repo ka poora naam hai (owner/repo)."
)


def bootstrap_overrides(catalog):
    """Pehli baar chalte waqt jo apps abhi apps.json me hain, unhe hi override
    bana kar rakh deta hai - taaki auto-sync unke naam/colour/icon na badle."""
    existing = load(OVERRIDES, None)
    if existing is not None:
        return existing
    seed = {"_comment": OVERRIDE_HELP, "ignore": [], "order": [], "apps": {}}
    for a in catalog.get("apps") or []:
        if not a.get("repo"):
            continue
        seed["order"].append(a.get("id"))
        seed["apps"][a["repo"]] = {k: a[k] for k in ("id", "name", "desc", "icon", "color") if k in a}
    OVERRIDES.write_text(json.dumps(seed, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    log("Created www/apps.overrides.json from the %d apps already in the catalogue." % len(seed["apps"]))
    return seed


# -------------------------------------------------------------------- main ---
def main():
    catalog = load(CATALOG, {})
    ov = bootstrap_overrides(catalog)
    cache = load(CACHE, {})
    ov_apps = ov.get("apps") or {}
    ignore = {s.lower() for s in (ov.get("ignore") or [])}
    suite_repo = ((catalog.get("suite") or {}).get("repo") or "").lower()

    ICONS.mkdir(parents=True, exist_ok=True)
    repos = list_repos()
    log("Scanning %d repos of %s" % (len(repos), OWNER))

    entries, new_cache = [], {}
    for repo in sorted(repos, key=lambda r: r["name"].lower()):
        full = repo["full_name"]
        if full.lower() == suite_repo or full.lower() in ignore or repo["name"].lower() in ignore:
            continue
        rel, asset = pick_release(full)
        if not asset:
            continue

        key = str(asset.get("id") or (full + asset["name"] + str(asset.get("updated_at"))))
        info = cache.get(key)
        ovr = ov_apps.get(full) or ov_apps.get(repo["name"]) or {}
        app_id = ovr.get("id") or slug(repo["name"])
        icon_rel = ovr.get("icon") or ("icons/%s.png" % app_id)
        icon_abs = WWW / icon_rel

        if not info or not icon_abs.exists():
            log("  %s: reading %s" % (full, asset["name"]))
            with tempfile.TemporaryDirectory() as td:
                apk = pathlib.Path(td) / "app.apk"
                try:
                    download(asset["browser_download_url"], apk)
                except Exception as e:                        # noqa: BLE001
                    log("  ! download failed: %s" % e)
                    continue
                info = parse_badging(badging(apk))
                if not info.get("package"):
                    log("  ! no package name - skipped")
                    continue
                if not ovr.get("icon"):
                    info["color"] = save_icon(apk, info.get("iconPaths") or [], icon_abs)
            wf, pattern = detect_workflow(full, rel, info)
            info["workflow"], info["versionNamePattern"] = wf, pattern
        else:
            log("  %s: unchanged" % full)
        new_cache[key] = info

        entry = {
            "id": app_id,
            "name": ovr.get("name") or info.get("label") or repo["name"].replace("_", " "),
            "desc": ovr.get("desc") if "desc" in ovr else (repo.get("description") or ""),
            "package": ovr.get("package") or info["package"],
            "repo": full,
            "asset": ovr.get("asset") or ASSET_RE,
            "icon": icon_rel,
            "color": ovr.get("color") or info.get("color") or DEFAULT_COLOR,
        }
        web = ovr.get("web")
        if web is None and repo.get("has_pages"):
            web = "https://%s.github.io/%s/" % (OWNER.lower(), repo["name"])
        if web:
            entry["web"] = web
        if info.get("workflow"):
            entry["workflow"] = info["workflow"]
            if info.get("versionNamePattern"):
                entry["versionNamePattern"] = info["versionNamePattern"]
        entries.append(entry)

    if not entries:
        log("No APK repos found - apps.json untouched (safety).")
        return 0

    order = [str(x).lower() for x in (ov.get("order") or [])]
    entries.sort(key=lambda e: (order.index(e["id"].lower()) if e["id"].lower() in order
                                else len(order), e["name"].lower()))

    before = json.dumps(catalog.get("apps"), sort_keys=True)
    catalog["apps"] = entries
    if json.dumps(entries, sort_keys=True) != before:
        log("Catalogue changed: %s" % ", ".join(e["id"] for e in entries))
    CATALOG.write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    CACHE.write_text(json.dumps(new_cache, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    log("Wrote %d apps." % len(entries))
    summary(entries)
    return 0


def summary(entries):
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path:
        return
    rows = ["### App catalogue (%d apps)" % len(entries), "",
            "| id | app | package | version source | repo |",
            "|---|---|---|---|---|"]
    for e in entries:
        src = ("workflow run number (%s)" % e["workflow"]) if e.get("workflow") else "release notes / tag"
        rows.append("| %s | %s | `%s` | %s | %s |"
                    % (e["id"], e["name"], e["package"], src, e["repo"]))
    with open(path, "a", encoding="utf-8") as f:
        f.write("\n".join(rows) + "\n")


if __name__ == "__main__":
    sys.exit(main())
