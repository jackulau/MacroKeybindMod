# MacroMod — release jars

MacroMod `0.1.0`, built for 23 Minecraft versions from a single
source tree. Each jar bundles the pure-JVM engine (Jar-in-Jar).

## Install

Download the jar matching your Minecraft version and drop it in `.minecraft/mods/`,
together with **Fabric Loader**, **Fabric API**, and **Fabric Language Kotlin**.
See the [README](../README.md#installation-in-detail) for details.

## Jars

| Minecraft | Jar | Size | Java | Fabric API |
| --- | --- | ---: | :---: | --- |
| 1.14.4 | `macromod-0.1.0+1.14.4.jar` | 360K | 8 | `0.28.5+1.14` |
| 1.15.2 | `macromod-0.1.0+1.15.2.jar` | 360K | 8 | `0.28.5+1.15` |
| 1.16.5 | `macromod-0.1.0+1.16.5.jar` | 412K | 8 | `0.42.0+1.16` |
| 1.17.1 | `macromod-0.1.0+1.17.1.jar` | 412K | 16 | `0.46.1+1.17` |
| 1.18.2 | `macromod-0.1.0+1.18.2.jar` | 412K | 17 | `0.77.0+1.18.2` |
| 1.19.2 | `macromod-0.1.0+1.19.2.jar` | 412K | 17 | `0.77.0+1.19.2` |
| 1.19.4 | `macromod-0.1.0+1.19.4.jar` | 412K | 17 | `0.87.2+1.19.4` |
| 1.20.1 | `macromod-0.1.0+1.20.1.jar` | 412K | 17 | `0.92.9+1.20.1` |
| 1.20.2 | `macromod-0.1.0+1.20.2.jar` | 412K | 17 | `0.91.6+1.20.2` |
| 1.20.4 | `macromod-0.1.0+1.20.4.jar` | 412K | 17 | `0.97.1+1.20.4` |
| 1.20.6 | `macromod-0.1.0+1.20.6.jar` | 412K | 21 | `0.100.8+1.20.6` |
| 1.21 | `macromod-0.1.0+1.21.jar` | 424K | 21 | `0.102.0+1.21` |
| 1.21.1 | `macromod-0.1.0+1.21.1.jar` | 424K | 21 | `0.116.12+1.21.1` |
| 1.21.2 | `macromod-0.1.0+1.21.2.jar` | 424K | 21 | `0.106.1+1.21.2` |
| 1.21.3 | `macromod-0.1.0+1.21.3.jar` | 424K | 21 | `0.114.1+1.21.3` |
| 1.21.4 | `macromod-0.1.0+1.21.4.jar` | 424K | 21 | `0.119.4+1.21.4` |
| 1.21.5 | `macromod-0.1.0+1.21.5.jar` | 424K | 21 | `0.128.2+1.21.5` |
| 1.21.6 | `macromod-0.1.0+1.21.6.jar` | 424K | 21 | `0.128.2+1.21.6` |
| 1.21.7 | `macromod-0.1.0+1.21.7.jar` | 424K | 21 | `0.129.0+1.21.7` |
| 1.21.8 | `macromod-0.1.0+1.21.8.jar` | 424K | 21 | `0.136.1+1.21.8` |
| 1.21.9 | `macromod-0.1.0+1.21.9.jar` | 424K | 21 | `0.134.1+1.21.9` |
| 1.21.10 | `macromod-0.1.0+1.21.10.jar` | 424K | 21 | `0.138.4+1.21.10` |
| 1.21.11 | `macromod-0.1.0+1.21.11.jar` | 424K | 21 | `0.141.4+1.21.11` |

## Verifying downloads

All SHA-256 checksums are in [`checksums.sha256`](checksums.sha256):

```bash
cd dist && shasum -a 256 -c checksums.sha256
```

_Regenerate this directory with `./gradlew chiseledBuild && bash tools/update-dist.sh`._
