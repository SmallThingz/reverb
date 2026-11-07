# Retained journal wire fixtures

These bytes were generated before the encoder allocation change by the retained compiled prior encoder, not by the successor implementation. The generator used the existing `DurableSaveJournalTest` fixture, then assigned a fixed job UUID and synthetic source paths/object identities. `unicode-v2.bin` also exercises modified UTF-8 (NUL, Omega and a supplementary character).

- Prior source SHA-256: `bc06233aebd8ad6a2448ddff2321849c9b21403c1430978d6d0729a9035e35db`
- Prior `DurableSaveJournal.class` SHA-256: `64aefe3e47b41f30ff450ec1aacb84ad9382eac06c03e060d3d2c9cbdd3d93d3`
- Prior runtime classes JAR SHA-256: `8079e830922223861709a1fd457b1b85a673fb43cd58cb838121a28e8bbda15a`

The v1 fixture was derived from the prior encoder's `legacy-pinning-v2.bin` using the retained test class's existing `versionOneManifest` helper: change the version to 1, omit the three v2 source-reference text fields per proof, and recompute the CRC. It is a legacy-format compatibility fixture, not a captured Android v1 file.

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| queued-v2.bin | 864 | 2a32c38090ae073db0b94d2a2592a9435e49c643825dc0f54d151dc94ddb26f4 |
| unicode-v2.bin | 871 | 9528a457a9d96c3ce32115fdda801c596b688623bb1ecdcad4a288a7b5324319 |
| legacy-pinning-v2.bin | 717 | 037cb1d0846f866d031e7871e430c8eb59c3cb1fd32696864bd58ea77008ecb1 |
| legacy-pinning-v1.bin | 699 | e7e05ecb8769289086fb1c0f1bf54461dc43ab053f1e1cd05e315ad93879e989 |

The original generator, classpath and before/after binary hashes are archived under `.maintenance/calibration-silence-20261103/journal-encoding-optimization/` and `.maintenance/calibration-silence-20261103/JournalGoldenGenerator.java`. The generator exited successfully and left the encoder class/JAR and source unchanged.
