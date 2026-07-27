"""构建 jar 瘦身:89MB -> 14MB。

jarjar 进来的 rocksdbjni 和 sqlite-jdbc 各自带了全平台原生库,加起来 87MB 里有 80MB 是本包用不到的
(musl / aarch64 / ppc64le / riscv64 / s390x / osx / 32 位)。测试者只在 Windows 和 Linux x86_64 上跑,
留这两个平台就够。

lwjgl-lmdb / lwjgl-zstd 里的 META-INF/versions/9/module-info.class 是另一回事:它让 JPMS 把这两个
扩展当成模块解析,专用服务端上没有对应的 lwjgl 本体就抛 FindException,报的还是「缺依赖」这种误导性错误。
删掉 module-info 即可,类本身照常从类路径加载。

用法: py _make_slim_jar.py [build/libs/neo-voxy-<版本>.jar]
"""

import io
import sys
import zipfile
from pathlib import Path

# 保留的原生库平台。判断放在文件名上,避免依赖各 jar 各自的目录约定。
ROCKSDB_KEEP = ("librocksdbjni-linux64.so", "librocksdbjni-win64.dll")
SQLITE_KEEP = ("org/sqlite/native/Linux/x86_64/", "org/sqlite/native/Windows/x86_64/")
NATIVE_SUFFIXES = (".so", ".dll", ".jnilib", ".dylib")


def strip_nested(data, name):
    """返回瘦身后的嵌套 jar 字节;不需要处理的返回 None。"""
    base = name.rsplit("/", 1)[-1]

    if base.startswith("lwjgl-"):
        def keep(entry):
            return entry != "META-INF/versions/9/module-info.class"
    elif base.startswith("rocksdbjni-"):
        def keep(entry):
            return not entry.endswith(NATIVE_SUFFIXES) or entry in ROCKSDB_KEEP
    elif base.startswith("sqlite-jdbc-"):
        def keep(entry):
            return not entry.endswith(NATIVE_SUFFIXES) or entry.startswith(SQLITE_KEEP)
    else:
        return None

    src = zipfile.ZipFile(io.BytesIO(data))
    out = io.BytesIO()
    dropped = 0
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            if not keep(info.filename):
                dropped += 1
                continue
            # 逐条保留原压缩方式:jarjar 的嵌套 jar 有 STORED 的条目,改压缩方式会让加载器找不到
            dst.writestr(info, src.read(info.filename), info.compress_type)
    if dropped == 0:
        return None
    print("  %-46s dropped %d entries" % (base, dropped))
    return out.getvalue()


def main():
    if len(sys.argv) > 1:
        fat = Path(sys.argv[1])
    else:
        candidates = [p for p in Path("build/libs").glob("neo-voxy-*.jar")
                      if not p.name.endswith("-slim.jar")]
        if not candidates:
            sys.exit("no build/libs/neo-voxy-*.jar found - run gradlew build first")
        fat = max(candidates, key=lambda p: p.stat().st_mtime)

    slim = fat.with_name(fat.stem + "-slim.jar")
    print("slimming %s" % fat.name)

    src = zipfile.ZipFile(fat)
    with zipfile.ZipFile(slim, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            data = src.read(info.filename)
            if info.filename.startswith("META-INF/jarjar/") and info.filename.endswith(".jar"):
                replacement = strip_nested(data, info.filename)
                if replacement is not None:
                    data = replacement
            dst.writestr(info, data, info.compress_type)

    print("%s: %.1f MB -> %.1f MB" % (slim.name,
                                      fat.stat().st_size / 1e6,
                                      slim.stat().st_size / 1e6))


if __name__ == "__main__":
    main()
