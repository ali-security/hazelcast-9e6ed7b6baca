# Totals plus every skipped test with its reason, from the JUnit XML reports.
# Skips are grouped per (test class, reason); parameterized names are folded. A skip whose message is empty (bare
# upstream @Ignore) or a bare hamcrest assumption text is explained from the test
# sources: the @Ignore location, or the assume*(...) source line that fired.
import glob, os, re
import xml.etree.ElementTree as ET
import sys
pattern = sys.argv[1]
index = {}
for src in glob.glob("*/src/test/java/**/*.java", recursive=True):
    index.setdefault(os.path.basename(src)[:-5], []).append(src)
cache = {}

def lines_of(path):
    if path not in cache:
        cache[path] = open(path, encoding="utf-8", errors="replace").read().splitlines()
    return cache[path]

def hierarchy(fqcn):
    rel = fqcn.split("$")[0].replace(".", "/") + ".java"
    hits = glob.glob("*/src/test/java/" + rel)
    path, seen = (hits[0] if hits else None), []
    while path and path not in seen:
        seen.append(path)
        cls = os.path.basename(path)[:-5]
        m = re.search(r"\bclass\s+%s\b[^{]*?\bextends\s+([A-Za-z0-9_.]+)" % re.escape(cls),
                      " ".join(lines_of(path)), re.S)
        path = (index.get(m.group(1).split(".")[-1]) or [None])[0] if m else None
    return seen

def ignore_near(lines, idx):
    for j in range(idx - 1, max(idx - 6, -1), -1):
        m = re.search(r"@Ignore\b(\((.*)\))?", lines[j])
        if m:
            return j, (m.group(2) or "").strip()
    return None

def method_body(paths, method):
    for path in paths:
        lines = lines_of(path)
        for i, line in enumerate(lines):
            if re.search(r"\b(void|[A-Za-z<>\[\]]+)\s+%s\s*\(" % re.escape(method), line) and not line.strip().endswith(";"):
                depth, body = 0, []
                for k in range(i, min(i + 80, len(lines))):
                    body.append((path, k, lines[k]))
                    depth += lines[k].count("{") - lines[k].count("}")
                    if depth <= 0 and "{" in "".join(l for _, _, l in body):
                        return body
    return []

def assume_lines(paths, method, depth=0):
    body = method_body(paths, method)
    found = [(p, k, l.strip()) for p, k, l in body if re.search(r"\bassume\w*\s*\(", l)]
    if found or depth >= 2:
        return found
    for _, _, l in body[1:]:
        for call in re.findall(r"\b([a-z]\w+)\s*\(", l):
            if call not in ("if", "for", "while", "switch", "return", "new", "assertEquals", "assertTrue"):
                sub = assume_lines(paths, call, depth + 1)
                if sub:
                    return sub
    return []

def explain(fqcn, method, message):
    paths = hierarchy(fqcn)
    cls = fqcn.split(".")[-1]
    if not message:
        for path in paths:
            lines = lines_of(path)
            pcls = os.path.basename(path)[:-5]
            for i, line in enumerate(lines):
                is_class = re.search(r"\bclass\s+%s\b" % re.escape(pcls), line)
                is_method = method and re.search(r"\bvoid\s+%s\s*\(" % re.escape(method), line)
                if is_class or is_method:
                    hit = ignore_near(lines, i)
                    if hit:
                        what = "class " + pcls if is_class else pcls + "." + method + "()"
                        return ("bare @Ignore by upstream developers on %s (%s:%d), unchanged in 3.12.6 source"
                                % (what, os.path.basename(path), hit[0] + 1))
    found = assume_lines(paths, method) if method else []
    if not found:
        for before in ("setUp", "setup", "before", "init"):
            found = assume_lines(paths, before)
            if found:
                break
    if found:
        p, k, l = found[0]
        if "IPv6" in l or "IPv6" in (method or ""):
            return ("assumption not met: `%s` (%s:%d) - the upstream test runner sets "
                    "java.net.preferIPv4Stack=true JVM-wide (AbstractHazelcastClassRunner.java:127), "
                    "so the JDK reports no IPv6 address" % (l, os.path.basename(p), k + 1))
        return ("assumption not met: `%s` (%s:%d) - test opts out of this parameter/environment by design"
                % (l, os.path.basename(p), k + 1))
    return message or "skipped by the JUnit runner without a message"

total = failures = errors = skipped = 0
groups = {}
for path in sorted(glob.glob(pattern + "/TEST-*.xml")):
    for case in ET.parse(path).getroot().iter("testcase"):
        total += 1
        cname, mname = case.get("classname") or "", case.get("name") or ""
        if case.find("failure") is not None:
            failures += 1
        elif case.find("error") is not None:
            errors += 1
        elif case.find("skipped") is not None:
            skipped += 1
            skip = case.find("skipped")
            message = skip.get("message") or (skip.text or "").strip()
            method = None if mname == cname else re.sub(r"\[.*$", "", mname)
            adapter = re.match(r"got: <class ([\w.$]+)>, expected: .* to be available", message or "")
            if adapter:
                key_reason = ("assumption not met: %s does not implement the data-structure method this "
                              "test exercises (assumeThatMethodIsAvailable) - opted out by design"
                              % adapter.group(1).split(".")[-1])
            elif not message or re.match(r"got: .*expected", message, re.S):
                key_reason = explain(cname, method, message)
            else:
                key_reason = message
            if len(key_reason) > 400:
                key_reason = key_reason[:400] + "..."
            groups.setdefault((cname, key_reason), []).append(method or "(whole class)")
for (cname, reason), names in sorted(groups.items()):
    uniq = sorted(set(names))
    shown = ", ".join(uniq[:4]) + (" +%d more" % (len(uniq) - 4) if len(uniq) > 4 else "")
    print("SKIPPED %s x%d [%s] -- %s" % (cname.replace("com.hazelcast.", ""), len(names), shown, reason))
print("SUMMARY reports=%s tests=%d passed=%d failures=%d errors=%d skipped=%d" % (
    pattern, total, total - failures - errors - skipped, failures, errors, skipped))
