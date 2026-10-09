#!/usr/bin/env python3
"""Résumé des tests unitaires (rapports JUnit) en annotations GitHub, lisibles via l'API.

Une annotation pour le bilan, puis une par test en échec avec le message et le début de la
pile d'appels, et les lignes utiles du journal de la classe de test (exceptions, erreurs).
"""
import glob
import xml.etree.ElementTree as ElementTree


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


reports = sorted(glob.glob("app/build/test-results/testDebugUnitTest/*.xml"))
total = failed = skipped = 0
lines = []
failures = []
journals = []
for report in reports:
    suite = ElementTree.parse(report).getroot()
    tests = int(suite.get("tests", 0))
    bad = int(suite.get("failures", 0)) + int(suite.get("errors", 0))
    skip = int(suite.get("skipped", 0))
    total += tests
    failed += bad
    skipped += skip
    name = suite.get("name", "?").split(".")[-1]
    lines.append(f"{name} : {tests - bad - skip}/{tests}")
    for case in suite.iter("testcase"):
        for kind in ("failure", "error"):
            node = case.find(kind)
            if node is not None:
                text = (node.text or node.get("message") or "").strip()
                kept = [line for line in text.splitlines() if not line.strip().startswith("at org.junit") and "jdk.internal" not in line]
                failures.append((f"{name}.{case.get('name')}", "\n".join(kept[:40])[:3500]))
    if bad:
        output = (suite.findtext("system-out") or "") + "\n" + (suite.findtext("system-err") or "")
        useful = [
            line for line in output.splitlines()
            if any(key in line for key in ("Exception", "Error", "DIAG", "FATAL", "\tat io.github", "Caused by"))
        ]
        if useful:
            journals.append((name, "\n".join(useful[-80:])[:5000]))

level = "error" if failed else "notice"
title = f"Tests : {total - failed - skipped}/{total} réussis" + (f", {skipped} ignorés" if skipped else "")
print(f"::{level} title={title}::" + "%0A".join(lines or ["aucun rapport de test"]))
for name, text in failures:
    print(f"::error title=Échec {name}::" + escape(text))
for name, text in journals:
    print(f"::warning title=Journal {name}::" + escape(text))
