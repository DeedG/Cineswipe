#!/usr/bin/env python3
"""Versions réellement résolues des principales bibliothèques, en annotation GitHub.

Lit la sortie de `:app:dependencies --configuration releaseRuntimeClasspath` dans le journal
de compilation : utile pour vérifier quelle version de Compose apporte la BOM.
"""
import re
import sys

WANTED = [
    "androidx.compose.runtime:runtime",
    "androidx.compose.ui:ui",
    "androidx.compose.foundation:foundation",
    "androidx.compose.material3:material3",
    "androidx.activity:activity-compose",
    "androidx.lifecycle:lifecycle-runtime-compose",
    "androidx.navigation3:navigation3-ui",
    "androidx.room:room-runtime",
    "io.coil-kt.coil3:coil-compose",
    "com.squareup.retrofit2:retrofit",
    "com.squareup.okhttp3:okhttp",
    "org.jetbrains.kotlinx:kotlinx-serialization-json",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core",
]

LINE = re.compile(r"[+\\]--- ([\w.\-]+):([\w.\-]+)(?::([\w.\-]+))?(?: -> ([\w.\-]+))?")

path = sys.argv[1] if len(sys.argv) > 1 else "build.log"
resolved = {}
try:
    with open(path, encoding="utf-8", errors="replace") as log:
        for line in log:
            match = LINE.search(line)
            if not match:
                continue
            key = f"{match.group(1)}:{match.group(2)}"
            version = match.group(4) or match.group(3)
            if key in WANTED and version:
                resolved.setdefault(key, version)
except FileNotFoundError:
    pass

lines = [f"{key.split(':')[1]} {resolved[key]}" for key in WANTED if key in resolved]
print("::notice title=Versions résolues::" + ("%0A".join(lines) if lines else "rapport de dépendances absent"))
