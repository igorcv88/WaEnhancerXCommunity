# WhatsApp consumer preview and compatibility evidence

Community base is `5bf501ce2bb21235a110fc21f1f3611c52ec0dcb`. This preview adds the exact original consumer target `com.whatsapp 2.26.46.82 (264608200)` to the ordinary loading path. The control is `2.26.38.74 (263807433)`. The intermediate `2.26.46.71 (264607149)` was used for comparison in the handoff scanners. Business needs separate APKs and its own evidence. The APKs and decompiled code are outside the repository.

## What was exercised

| Check | Result |
| --- | --- |
| Original `scan_target.py` and `inspect_contracts.py` | Recomputed inventory and xref JSON files were byte-identical to the handoff outputs. The inventory includes the intermediate build. |
| Android SDK Build Tools 36.0.0 `apksigner verify --verbose --print-certs` | Both bases and all 12 target splits verified. The two legacy/current signing fingerprints agree across the APKs. Exact split and base digests are in the offline records. |
| Android SDK `aapt2 dump badging` and `dump resources` | Both version codes and all target split names verified. Four ABI-only APKs carry native libraries without a resource table. `drawable/ic_viewonce` is `0x7f0807ed` in the control and `0x7f080884` in the target density splits. Runtime resources depend on the installed split set. |
| JADX 1.5.3 | The control export completed with 173 decompilation errors. The target export and a single-worker retry were partial due to heap exhaustion. Verified contract evidence below comes from DEX definitions and references. Baksmali 3.0.9 exported the selected target key, tablet getter and list classes from classes.dex for inspection. |
| DexKit 2.2.0 | Desktop JNI compiled from tag `2.2.0` at `ffa6c51c38fe3ecfddb18d8949c30c48dbfbfd6a`, using the repository-pinned version and matching AAR classes. Re-run 2026-11-27: 22 shared contract queries; 20 returned one candidate in both fixtures and the two navigation-bar contracts returned the implementation sets that are hooked (control 2/2, target 2/3). See [control](offline-old.json) and [target](offline-target.json). |
| Runtime install and behavior (L2–L5) | NOT_TESTED. This executor had no connected Android target or KVM, and no two consenting WhatsApp endpoints. |

The DEX records include candidate counts, declaration descriptors, DEX index, signature and opcode fingerprint for methods, and a bounded sample of relationship descriptors. Invoke and caller entries are references. The tablet lookup has the same normalized fingerprint across both builds. The chat list source moved from `ConversationsFragment` to `ConversationsFragmentKt`, where a second zero-argument List method forwards an interface call. The fallback checks for the invocation of the hidden-chat list filter. The old declared getter is unique and remains preferred. The tablet delegate path relies on field reads and a matching lifecycle method declaration through the delegate interface. Its final JID extraction and per-chat behavior still require runtime testing.

The main message key uses the final field that the sender lookup reads and the declaring constructor writes. In both builds a second mutable field has the same key type. The incoming read-receipt query uses its existing anchor and requires a unique two-argument instance method returning void, with the second argument an int. It gives no evidence about delivery, played or status outcomes. The resource scan does not demonstrate installed resource lookup and the measured contract set covers only part of the core and optional resolvers.

## Upstream comparison

- [WaEnhancerX/WaEnhancerX](https://github.com/WaEnhancerX/WaEnhancerX/commit/9afc43d9582eb7afa84f9ce45eed746252332241) adds a 2.26.46 family entry at `ad67de519508e1444948a7ae9ea1a96205884934`. The commit changes the version catalog only. Retained Community's exact preview target and infrastructure from its audit ledger.
- [Legacy Mubashar-dev](https://github.com/mubashardev/WaEnhancer/commit/0b2f729e91aa1b773cf38ead91ba7bdb3ad2392c) points to the V2 transition. The inspected head contains the older settings sources and no comparable 2.26.46 host resolver to port.
- [Dev4Mod/WaEnhancer](https://github.com/Dev4Mod/WaEnhancer/commit/1f581f4b76618a810a2ebfd0863c719a3cddaee7) includes `a7c5b60133808b1d4a9371e96d549e567e3f8517` (chat list moved and tab installation order), `dce4e58ba598de1dc30f08462a8ad3d852279707` (tablet delegate), `eee118f56fe4c573f6fcd960e313d5e5df2d1659` (status profile lookup) and `97dfbc5f19fbb9efb17a34f25b93d2c5d23e827e` (common props resolver). Adapted the first two using unique DEX contracts. The props getters used by Community had one legacy Boolean and one legacy Int match in both fixtures, so they were left in place. The target had two Json anchor matches. Status photo hierarchy, system bar drawing, and property override behavior remain separate tests. See [prior integrated ports](../UPSTREAM_PORTS_2026-11-22.md) for already merged receipt, cache, JID and contact picker work.

## Run the audit

Use JDK 21, Android SDK Build Tools 36.0.0, Python 3.11+, and the repository-pinned DexKit version. Install Build Tools with Android SDK Manager. Build the desktop `libdexkit.so` from the official DexKit tag with CMake using `dexkit/src/main/cpp` as the source directory and `JAVA_HOME` set. Extract `classes.jar` from Maven Central's official `org.luckypray:dexkit:2.2.0` AAR into a private directory along with its Kotlin stdlib and FlatBuffers JVM dependencies. Keep the compiler, artifacts and APKs outside the checkout.

```sh
python3 tools/compatibility/inventory.py --check
python3 tools/compatibility/test_audit.py
python3 tools/compatibility/audit.py \
  --base /private/fixtures/old/base.apk \
  --sdk-build-tools /opt/android/build-tools/36.0.0 \
  --jdk /opt/jdk-21 \
  --dexkit-native /private/dexkit-build \
  --dexkit-classpath '/private/dexkit-jars/*' \
  --output /private/evidence/old.json
```

Run the same command against the target base and add a repeated `--split /private/fixtures/target/SPLIT.apk` for each user-authorized split under audit. Record the smaller selected set separately for an installed test device. APK analysis is a local optional workflow. PR CI runs the source inventory and synthetic checks without APKs. The source gate tracks literal dependency calls, owners and the loader's 70 registrations. It flags additions, removed sites and changed expressions. Dynamic dispatch, inferred hook semantics and runtime behavior remain audit work.

### Independent anchor census

`tools/compatibility/anchor_census.py` counts, per host APK and without DexKit, the methods and classes that load each of the 172 string anchors used by `Unobfuscator` and `HostDexContracts` (`dexdump -d`). The recorded result for both mandatory builds is in `tools/compatibility/fixtures/anchor_census.json`; CI checks that every source anchor has a census and runs the parameterized tests. Re-record after changing an anchor:

```sh
python3 tools/compatibility/anchor_census.py census --dexdump /opt/android/build-tools/36.0.0/dexdump   --apk /private/fixtures/target/base.apk --label 2.26.46.82 --version-code 264608200
WAE_DEXDUMP=... WAE_APK_2_26_46_82=... python3 tools/compatibility/test_anchor_census.py
```

The census is L0: it shows that an anchor exists or is unique, not that a resolver selects the right member. Triage of both PR #177 reviews, including the census findings, is in [pr177-triage.md](pr177-triage.md).

## Physical test and signed preview

Use the existing signed module artifact for the exact PR head once its CI completes. Use a disposable test profile with backups and two consenting test accounts. Start with the control, then repeat the [regression matrix](regression-matrix.csv) on the exact target plus the correct ABI and density splits. Do not use the target base alone. Record the installed split identity and source, module digest/version, Android and LSPosed API, screen/layout direction, time and both endpoints for external effects.

1. Use the unchanged stock host with LSPosed module scope off. Run cold and warm start; Home, group, direct and status navigation; direct and group typing/recording/read/delivery/playback; native stickers. If sound stickers are unavailable in stock, record SERVER_UNAVAILABLE for the gated cases.
2. Repeat with module on and Inspector, BubbleTouch debug and validation sessions off. Vary feature settings independently. Check Separate Groups, Home search, badges/FAB, tablet pane switches, distinct JID and LID chats, offline/reconnect, and background/foreground. Compare receipt icons and message state on both accounts.
3. With diagnostics off, repeat authorized consecutive view-once items with Text Bubbles Liquid Glass off then on. Check both taps, media rows after rebind, first frame, scrolling, RTL, light/dark, accessibility, layout restoration and renderer safety. Only use explicitly authorized synthetic media and preserve sender restrictions. Use a disposable library with a synthetic migration, interruption/resume, deduplication and source checksum.
4. For native sound stickers, compare download/play/pause, send/receive, voice interaction, competing audio focus, rebind and reconnect under stock, module ON with aesthetics OFF, and Glass ON. Record expected and observed separately for each endpoint. The preview does not add a sound-sticker backport or gate override.
5. If resolver or callback evidence is needed, open **Diagnostics**, start a 30-minute session, then force-stop and relaunch the test host. Wait until the report shows the feature loader as COMPLETE; a snapshot without that marker is UNKNOWN. Arm the individual smoke exercise before performing it. A required case passes only on its own behaviour path after arming (HideSeen: a suppressed direct read receipt; AntiRevoke: a blocked message revoke), plus user observation; reaching the hook is reported separately. That describes the instrumented exercise. It cannot certify the physical checks above. Changing the observed configuration or restarting the host invalidates the old process evidence. Business remains outside this matrix.

The official validated-build list stays empty. A future scoped record must match the report’s exact build key and declare its prerequisites. A report with partial resolver, callback or manual smoke evidence does not establish a validated build. Remaining work includes full resolver coverage, installed-resource and reflection checks, device L2–L5 and native account availability.
