#!/usr/bin/env python3
"""Transforme les erreurs Gradle / Kotlin d'un log en annotations GitHub.

Les annotations sont lisibles via l'API (check runs), ce qui permet de corriger
une compilation sans avoir à télécharger le log complet.
"""
import os
import re
import sys

KOTLIN_ERROR = re.compile(r"^e: (?:file://)?(/\S+?):(\d+):(\d+) (.*)$")
KSP_ERROR = re.compile(r"^e: \[ksp\] (/\S+?):(\d+): (.*)$")


def escape_data(value: str) -> str:
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escape_property(value: str) -> str:
    return escape_data(value).replace(":", "%3A").replace(",", "%2C")


def relative(path: str) -> str:
    workspace = os.environ.get("GITHUB_WORKSPACE", "")
    if workspace and path.startswith(workspace + "/"):
        return path[len(workspace) + 1:]
    return path


def main(log_path: str) -> None:
    if not os.path.exists(log_path):
        print("::error title=Build::Aucun log de build")
        return
    with open(log_path, encoding="utf-8", errors="replace") as handle:
        lines = handle.read().splitlines()

    located = []
    for line in lines:
        match = KOTLIN_ERROR.match(line)
        if match:
            path, row, col, message = match.groups()
            located.append((relative(path), row, col, message))
            continue
        match = KSP_ERROR.match(line)
        if match:
            path, row, message = match.groups()
            located.append((relative(path), row, "1", message))

    for path, row, col, message in located[:9]:
        print(f"::error file={escape_property(path)},line={row},col={col}::{escape_data(message)}")

    # Toutes les lignes d'erreur du compilateur et de KSP, quel que soit leur format.
    compiler_errors = [line for line in lines if line.startswith("e: ") or "[ksp]" in line]
    if compiler_errors:
        summary = "\n".join(compiler_errors)[:30000]
        print(f"::error title=Erreurs de compilation ({len(compiler_errors)})::{escape_data(summary)}")

    blocks = []
    for index, line in enumerate(lines):
        if line.strip() == "* What went wrong:":
            block = []
            for following in lines[index + 1:index + 60]:
                if following.startswith("* Try:") or following.startswith("* Exception is:"):
                    break
                block.append(following)
            blocks.append("\n".join(block).strip())
    if blocks:
        print(f"::error title=What went wrong::{escape_data(chr(10).join(blocks)[:30000])}")

    failed_tests = []
    for index, line in enumerate(lines):
        if line.rstrip().endswith(" FAILED") and " > " in line:
            failed_tests.append("\n".join(lines[index:index + 6]))
    if failed_tests:
        print(f"::error title=Tests en échec::{escape_data(chr(10).join(failed_tests)[:30000])}")

    if not (compiler_errors or failed_tests or blocks):
        tail = "\n".join(lines[-80:])
        print(f"::error title=Fin du log::{escape_data(tail[:30000])}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "build.log")
