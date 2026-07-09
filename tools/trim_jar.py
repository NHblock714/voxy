"""Post-build surgery on the voxy jarjar libs. Run after `gradlew build`, before deploy.

Two transforms on nested (jarjar) jars:
  1. rocksdbjni: keep only win64.dll + linux64.so natives (drops ~60MB of unused platforms).
  2. lwjgl-zstd / lwjgl-lmdb: strip their module-info so they become AUTOMATIC modules.
     They declare `requires org.lwjgl`; a dedicated server has no org.lwjgl module, so the JPMS
     module graph fails at startup (`FindException: Module org.lwjgl not found, required by
     org.lwjgl.zstd`). As automatic modules they have no `requires`, so the server resolves; the
     client still works (an automatic module reads org.lwjgl, which is present there).

Nested jarjar jars must stay STORED (uncompressed) so NeoForge can read them.
"""
import zipfile, io, os

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "build", "libs")
SRC = os.path.join(BASE, "neo-voxy-0.2.16-beta.jar")
DST = os.path.join(BASE, "neo-voxy-0.2.16-beta-slim.jar")

ROCKS = "META-INF/jarjar/rocksdbjni-10.2.1.jar"
ROCKS_KEEP = {"librocksdbjni-win64.dll", "librocksdbjni-linux64.so"}
DEMODULARIZE = [
    "META-INF/jarjar/lwjgl-zstd-3.3.3.jar",
    "META-INF/jarjar/lwjgl-lmdb-3.3.3.jar",
]
MODULE_INFO = "META-INF/versions/9/module-info.class"


def is_rocks_native(name):
    base = name.rsplit("/", 1)[-1]
    return base.startswith("librocksdbjni-") and base.endswith((".so", ".dll", ".jnilib"))


def filter_rocks(nested):
    buf = io.BytesIO()
    dropped, kept = [], []
    with zipfile.ZipFile(io.BytesIO(nested)) as nz, zipfile.ZipFile(buf, "w") as out:
        for e in nz.infolist():
            base = e.filename.rsplit("/", 1)[-1]
            if is_rocks_native(e.filename) and base not in ROCKS_KEEP:
                dropped.append(base); continue
            if is_rocks_native(e.filename):
                kept.append(base)
            out.writestr(e, nz.read(e.filename))   # ZipInfo preserves per-entry compression
    print("  rocksdb: kept %s, dropped %d" % (sorted(kept), len(dropped)))
    return buf.getvalue()


def demodularize(nested, label):
    buf = io.BytesIO()
    removed = 0
    with zipfile.ZipFile(io.BytesIO(nested)) as nz, zipfile.ZipFile(buf, "w") as out:
        for e in nz.infolist():
            if e.filename == MODULE_INFO:
                removed += 1; continue
            out.writestr(e, nz.read(e.filename))
    print("  %s: stripped module-info x%d -> automatic module" % (label, removed))
    return buf.getvalue()


with zipfile.ZipFile(SRC) as zin:
    transforms = {ROCKS: filter_rocks(zin.read(ROCKS))}
    for nj in DEMODULARIZE:
        transforms[nj] = demodularize(zin.read(nj), nj.rsplit("/", 1)[-1])

    with zipfile.ZipFile(DST, "w") as zout:
        for e in zin.infolist():
            if e.filename in transforms:
                zi = zipfile.ZipInfo(e.filename, date_time=e.date_time)
                zi.compress_type = zipfile.ZIP_STORED   # jarjar nested jars must be STORED
                zi.external_attr = e.external_attr
                zout.writestr(zi, transforms[e.filename])
            else:
                zout.writestr(e, zin.read(e.filename))

print("outer jar: %.1f MB -> %.1f MB" % (os.path.getsize(SRC) / 1048576, os.path.getsize(DST) / 1048576))
