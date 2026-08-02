"""Mixin member validation against the real jars.

The compiler cannot see mixin semantics: a @Shadow naming a member the target class does not itself
declare, or an injector naming a method that is not there, compiles clean and dies at class-transform
time - as a startup crash, in whatever mod happens to touch the class first. Inherited members are
the usual trap; @Shadow resolves only what the target class itself declares, so shadowing something
like ChunkHolder.getPos() (declared on its superclass GenerationChunkHolder) fails at apply time.

This resolves every vanilla and modded target against the same jars the build compiles against and
checks the two rules mixin enforces at apply time:
  - every @Shadow field/method must be DECLARED in the target class (inheritance does not count);
  - every @Inject/@Redirect/@WrapOperation/@ModifyVariable/... `method` target must be DECLARED in
    the target class.
A target class that cannot be resolved (a mod jar not on the reference path, @Pseudo targets for
optional mods) is reported as skipped, never failed - absence of evidence is not a failure here,
but a missing MEMBER in a class we did resolve is.

Exit 1 only on definite violations, so the build gate cannot fire spuriously.
"""

import io
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java"
JAVAP = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Zulu\zulu-21")) / "bin" / "javap.exe"

MIXIN_RE = re.compile(r'@Mixin\s*\(([^)]*)\)', re.S)
TARGET_CLASS_RE = re.compile(r'(?:value\s*=\s*)?\{?\s*([\w.$]+)\.class')
TARGET_STRING_RE = re.compile(r'targets\s*=\s*\{?\s*"([^"]+)"')
#Procedural, not one regex: the declaration after @Shadow may share its line with the annotations or
#sit on the next one, and a regex tuned to one form silently skips the other - a validator that can
#skip is worse than none.
def find_shadows(source):
    out = []
    #The annotation's own arguments are consumed by the match so they cannot read as the member
    for m in re.finditer(r'@Shadow\b(?:\s*\([^)]*\))?', source):
        window = source[m.end():m.end() + 400]
        stop = len(window)
        for ch in (';', '{'):
            i = window.find(ch)
            if i != -1:
                stop = min(stop, i)
        decl = window[:stop]
        #Strip companion annotations so their names are not mistaken for the member
        decl = re.sub(r'@[\w.$]+(\([^)]*\))?', ' ', decl)
        paren = decl.find('(')
        if paren != -1:
            names = re.findall(r'[\w$]+', decl[:paren])
            if names:
                out.append((names[-1], True))
            continue
        #Field: the name is the last identifier of the declarator, before any initializer
        head = decl.split('=', 1)[0]
        names = re.findall(r'[\w$]+', head)
        if names:
            out.append((names[-1], False))
    return out
INJECTOR_RE = re.compile(
    r'@(?:Inject|Redirect|ModifyVariable|ModifyExpressionValue|ModifyArg|ModifyArgs|ModifyConstant|'
    r'ModifyReturnValue|WrapOperation|WrapMethod|WrapWithCondition)\s*\((.*?)\)\s*\n', re.S)
METHOD_ATTR_RE = re.compile(r'method\s*=\s*(\{[^}]*\}|"[^"]*")')
IMPORT_RE = re.compile(r'^import\s+([\w.$]+);', re.M)


def build_classpath():
    jars = []
    loom = Path.home() / ".gradle" / "caches" / "fabric-loom" / "1.21.1" / "neoforge"
    if loom.is_dir():
        candidates = sorted(loom.glob("*/minecraft-merged-mojang-at-patched.jar"))
        if candidates:
            jars.append(candidates[-1])
    jars.extend(sorted((ROOT / "libs" / "aero-spike").glob("*.jar")))
    sodium = ROOT / "build" / "generated" / "compile-deps" / "sodium-neoforge-0.8.12-mod.jar"
    if sodium.exists():
        jars.append(sodium)
    return jars


class TargetInfo:
    __slots__ = ("fields", "methods")

    def __init__(self, fields, methods):
        self.fields = fields
        self.methods = methods


_CACHE = {}


def resolve(classpath, class_name):
    if class_name in _CACHE:
        return _CACHE[class_name]
    sep = ";" if os.name == "nt" else ":"
    try:
        out = subprocess.run(
            [str(JAVAP), "-p", "-classpath", sep.join(str(j) for j in classpath), class_name],
            capture_output=True, text=True, timeout=60)
    except Exception:
        _CACHE[class_name] = None
        return None
    if out.returncode != 0 or "Error" in (out.stderr or ""):
        _CACHE[class_name] = None
        return None
    fields, methods = set(), set()
    for line in out.stdout.splitlines():
        line = line.strip()
        m = re.match(r'(?:public|protected|private|static|final|abstract|native|synchronized|volatile|transient|\s)*'
                     r'[\w.$<>?\[\], ]+?\s+([\w$]+)\s*\((.*)\)\s*(?:throws [\w.$, ]+)?;', line)
        if m:
            methods.add(m.group(1))
            continue
        m = re.match(r'(?:public|protected|private|static|final|volatile|transient|\s)*'
                     r'[\w.$<>?\[\], ]+?\s+([\w$]+);', line)
        if m:
            fields.add(m.group(1))
    #Constructors surface under the simple class name; injectors refer to them as <init>
    methods.add("<init>")
    methods.add("<clinit>")
    info = TargetInfo(fields, methods)
    _CACHE[class_name] = info
    return info


def targets_of(source, mixin_ann):
    imports = {i.rsplit(".", 1)[-1]: i for i in IMPORT_RE.findall(source)}
    found = []
    for m in TARGET_CLASS_RE.finditer(mixin_ann):
        name = m.group(1)
        if name in ("value", "targets", "priority", "remap"):
            continue
        found.append(imports.get(name.split(".")[0], name) if "." not in name else name)
    found.extend(TARGET_STRING_RE.findall(mixin_ann))
    return found


def method_names(attr):
    names = set()
    for raw in re.findall(r'"([^"]+)"', attr):
        name = raw.split("(")[0]
        if name and name != "*" and not name.startswith("L"):
            names.add(name)
    return names


def main():
    classpath = build_classpath()
    if not classpath:
        print("mixin-validate: no reference jars found; skipping")
        return 0

    violations = []
    skipped = set()
    checked = 0
    for path in SRC.rglob("*.java"):
        source = io.open(path, encoding="utf-8").read()
        if "@Mixin" not in source:
            continue
        #Comments mention annotations by name when explaining them; only code may be scanned
        source = re.sub(r'//[^\n]*', '', source)
        source = re.sub(r'/\*.*?\*/', '', source, flags=re.S)
        ann = MIXIN_RE.search(source)
        if not ann:
            continue
        rel = path.relative_to(SRC)
        infos = []
        for target in targets_of(source, ann.group(1)):
            info = resolve(classpath, target)
            if info is None:
                skipped.add(target)
            else:
                infos.append((target, info))
        if not infos:
            continue
        checked += 1

        for name, is_method in find_shadows(source):
            ok = any(name in (info.methods if is_method else info.fields) for _, info in infos)
            if not ok:
                what = "method" if is_method else "field"
                violations.append(f"{rel}: @Shadow {what} '{name}' not declared in "
                                  f"{'/'.join(t for t, _ in infos)}")

        for inj in INJECTOR_RE.finditer(source):
            attr = METHOD_ATTR_RE.search(inj.group(1))
            if not attr:
                continue
            for name in method_names(attr.group(1)):
                if not any(name in info.methods for _, info in infos):
                    violations.append(f"{rel}: injector target '{name}' not declared in "
                                      f"{'/'.join(t for t, _ in infos)}")

    print(f"mixin-validate: {checked} mixins checked against {len(classpath)} jars; "
          f"{len(skipped)} unresolvable targets skipped")
    if skipped:
        print("  skipped: " + ", ".join(sorted(skipped)[:8]) + ("..." if len(skipped) > 8 else ""))
    if violations:
        print("VIOLATIONS:")
        for v in violations:
            print("  " + v)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
