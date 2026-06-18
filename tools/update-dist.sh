#!/usr/bin/env bash
# Collect the per-version MacroMod mod jars into dist/, then regenerate
# dist/checksums.sha256 and dist/MANIFEST.md.
#
# Run AFTER `./gradlew chiseledBuild` so the jars exist under
# fabric/versions/<mc>/build/libs/. Idempotent — safe to re-run.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DIST="dist"
MOD_VERSION="$(sed -n 's/^mod\.version=//p' gradle.properties)"

# Canonical version order (matches settings.gradle.kts).
VERSIONS=(
  1.14.4 1.15.2 1.16.5 1.17.1 1.18.2 1.19.2 1.19.4
  1.20.1 1.20.2 1.20.4 1.20.6
  1.21 1.21.1 1.21.2 1.21.3 1.21.4 1.21.5 1.21.6 1.21.7 1.21.8 1.21.9 1.21.10 1.21.11
)

mkdir -p "$DIST"
# Clear stale jars (keep MANIFEST/checksums until regenerated below).
rm -f "$DIST"/*.jar

# Read a per-version gradle property with a default fallback.
prop() { # <mc> <key> <default>
  local f="fabric/versions/$1/gradle.properties"
  local v=""
  [ -f "$f" ] && v="$(sed -n "s/^$2=//p" "$f" | head -1)"
  printf '%s' "${v:-$3}"
}

copied=0
missing=()
for mc in "${VERSIONS[@]}"; do
  jar="fabric/versions/$mc/build/libs/macromod-${MOD_VERSION}+${mc}.jar"
  if [ -f "$jar" ]; then
    cp "$jar" "$DIST/"
    copied=$((copied + 1))
  else
    missing+=("$mc")
  fi
done

if [ "${#missing[@]}" -gt 0 ]; then
  echo "WARNING: missing jars for: ${missing[*]}" >&2
  echo "         run ./gradlew chiseledBuild first." >&2
fi

# Checksums (run inside dist/ so paths are bare filenames, verifiable with -c).
( cd "$DIST" && shasum -a 256 *.jar > checksums.sha256 )

# MANIFEST.md
{
  echo "# MacroMod — release jars"
  echo
  echo "MacroMod \`${MOD_VERSION}\`, built for ${copied} Minecraft versions from a single"
  echo "source tree. Each jar bundles the pure-JVM engine (Jar-in-Jar)."
  echo
  echo "## Install"
  echo
  echo "Download the jar matching your Minecraft version and drop it in \`.minecraft/mods/\`,"
  echo "together with **Fabric Loader**, **Fabric API**, and **Fabric Language Kotlin**."
  echo "See the [README](../README.md#installation-in-detail) for details."
  echo
  echo "## Jars"
  echo
  echo "| Minecraft | Jar | Size | Java | Fabric API |"
  echo "| --- | --- | ---: | :---: | --- |"
  for mc in "${VERSIONS[@]}"; do
    jar="macromod-${MOD_VERSION}+${mc}.jar"
    [ -f "$DIST/$jar" ] || continue
    size="$(du -h "$DIST/$jar" | cut -f1 | tr -d ' ')"
    java="$(prop "$mc" 'deps.java' '21')"
    fapi="$(prop "$mc" 'deps.fabric_api' '—')"
    echo "| $mc | \`$jar\` | $size | $java | \`$fapi\` |"
  done
  echo
  echo "## Verifying downloads"
  echo
  echo "All SHA-256 checksums are in [\`checksums.sha256\`](checksums.sha256):"
  echo
  echo '```bash'
  echo "cd dist && shasum -a 256 -c checksums.sha256"
  echo '```'
  echo
  echo "_Regenerate this directory with \`./gradlew chiseledBuild && bash tools/update-dist.sh\`._"
} > "$DIST/MANIFEST.md"

echo "dist/ updated: $copied jars, MANIFEST.md + checksums.sha256 regenerated."
