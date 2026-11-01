# Changelog

## 0.1.0 (2026-11-01)


### ⚠ BREAKING CHANGES

* the SignatureRefusal cases, KEY_BITS_MINIMUM, Configuration.isOnConfiguredHost, Evaluator.evaluate and WebViewGate are renamed or gone.
* a channel whose index is gone fails the check and is never changed by the SDK ([#31](https://github.com/hotcodepush-team/core-android/issues/31))
* protocol b485e58, the readers refuse what the protocol refuses, the core owns the metered check, and the names follow the protocol ([#27](https://github.com/hotcodepush-team/core-android/issues/27))
* the wire after the contract audit: no rolled_back event, conditionType, pausedAt, rolloutPercent, STATE_VERSION 6 ([#26](https://github.com/hotcodepush-team/core-android/issues/26))
* every status, reason, apply strategy and the rollback's device event type is lowercase snake_case on the wire and in every result and event the core hands the SDK; SkippedReason, FailedReason and RollbackReason carry their value as wire, and their name is no longer it.
* `StateResult.lastReportAt` is renamed `reportedAt`, its JSON key with it, and holds the kept stamp: the server time of the month's first acknowledged device report.
* `handleAppReload()` is removed. A host serves the running bundle at a reload the core did not perform and tells the core nothing, as the React Native SDK answers React Native's request for a bundle with the running bundle.
* StateStore.nextRelease is a NextRelease, the release and its applyAt; Core.downloadUpdate() takes DownloadUpdateOptions.
* SyncStatus.UPDATED is SyncStatus.APPLIED and SyncResult.updated() is SyncResult.applied(); SyncResult.downloaded() takes applyAt; SyncResult.installAt is applyAt; downloadBytes is downloadSizeBytes on SyncResult and UpdateAvailableEvent; UpdateDownloadedEvent.installAt is applyAt; RolledBackEvent is UpdateRolledBackEvent and CoreListener.rolledBack() is updateRolledBack(); ApplyResult is ApplyUpdateResult; InstallMoment is gone, applyAt is an ApplyStrategy.
* Configuration's members, the strategy enums and SyncOptions carry the new names: InstallStrategy is ApplyStrategy, MandatoryInstallStrategy is MandatoryApplyStrategy, autoCheck is checkStrategy, and the durations end in Seconds. SyncOptions lists its members alphabetically, applyStrategy first, and DownloadUpdateOptions joins it.
* handleAppStart() answers the bundle, the blocking start has a bound, and a reload is handleAppReload()
* the core brings OkHttp 4.12.0, never OkHttp 5
* every pack entry, patch and file streams to disk, never whole in memory
* a channel id is a UUID, and the index and the manifest name the device's app
* appliedIndexSequence leaves DeviceInfo
* every reason names the thing at fault first, SUBJECT_STATE
* the runtime condition and the runtime version leave the core
* the channel's id is null while no id is known
* a build without an embedded bundle skips every cycle with DEBUG_BUILD
* the library is core-android, its Kotlin package com.hotcodepush.core
* the app's rollback is rollbackUpdate()
* the pack's patch entries applied from the delta pack, and patches leave the manifest and the envelope
* signing is RSA alone, verified by java.security — BouncyCastle and Ed25519 leave the library

### Features

* a batch refused with a 4xx drops its events and keeps the report unacknowledged ([0d4ab28](https://github.com/hotcodepush-team/core-android/commit/0d4ab2883b9a74a091786b5d06aafeff93b17b60))
* a build without a channel answers UNKNOWN_CHANNEL without a request and reports nothing ([2879fb9](https://github.com/hotcodepush-team/core-android/commit/2879fb9f93a21180d19f9ff79eccc16280474e76))
* a build without an embedded bundle skips every cycle with DEBUG_BUILD ([0b89f1a](https://github.com/hotcodepush-team/core-android/commit/0b89f1a51ba320224bdf4f2d9ccf01c29ef2a1c1))
* a channel id is a UUID, and the index and the manifest name the device's app ([0c69212](https://github.com/hotcodepush-team/core-android/commit/0c692123970471b395c179d9f436d32578e872b8))
* a channel whose index is gone fails the check and is never changed by the SDK ([#31](https://github.com/hotcodepush-team/core-android/issues/31)) ([f71d994](https://github.com/hotcodepush-team/core-android/commit/f71d9942b6c4f6d2aac83c9b9709018a458bbe40))
* a device without a channel starts no automatic cycle ([ced91a4](https://github.com/hotcodepush-team/core-android/commit/ced91a4f086ec54c061872bb45ab9b2aee75a5b4))
* a headless start applies no waiting release and arms no gate ([d559399](https://github.com/hotcodepush-team/core-android/commit/d55939926157524abb9f057b7b41ec7ca345d97f))
* a reload the core did not perform is never interpreted ([023ed01](https://github.com/hotcodepush-team/core-android/commit/023ed016b6d862d825d371ae3eb6ad1dc2db05f8))
* Ed25519 is verified through BouncyCastle on every Android version ([861bf2b](https://github.com/hotcodepush-team/core-android/commit/861bf2bc857dd47d8ce641894ee3bc88499d5a17)), closes [#2](https://github.com/hotcodepush-team/core-android/issues/2)
* every catalog value is lowercase snake_case, held to catalog.json ([2af4210](https://github.com/hotcodepush-team/core-android/commit/2af4210f48715513dd0a8028f00d08d12c530fda))
* every pack entry, patch and file streams to disk, never whole in memory ([f509c66](https://github.com/hotcodepush-team/core-android/commit/f509c66923447b0ada89cfb03d27d1b1f7399f33))
* every reason names the thing at fault first, SUBJECT_STATE ([9220621](https://github.com/hotcodepush-team/core-android/commit/9220621104f171ae391eb5155fd1dcc796542246))
* FreeBSD's bspatch as a native library for every ABI, with bzip2 1.0.8's decompression ([3a073de](https://github.com/hotcodepush-team/core-android/commit/3a073de425660f89f58899700f5c8c45f2d6cfd0))
* handleAppStart() answers the bundle, the blocking start has a bound, and a reload is handleAppReload() ([e64d1c6](https://github.com/hotcodepush-team/core-android/commit/e64d1c6b4a0d676df10fe9a99c2b1f149fb8b423))
* protocol b485e58, the readers refuse what the protocol refuses, the core owns the metered check, and the names follow the protocol ([#27](https://github.com/hotcodepush-team/core-android/issues/27)) ([429070e](https://github.com/hotcodepush-team/core-android/commit/429070ec80066b8f21396434d1e608b0317d34a3))
* signature refusals, wire enums and host check named as in core-ios and the protocol ([#34](https://github.com/hotcodepush-team/core-android/issues/34)) ([d7497ae](https://github.com/hotcodepush-team/core-android/commit/d7497ae24ad9f10dff540fe0e5e458a1b2028500))
* signing is RSA alone, verified by java.security — BouncyCastle and Ed25519 leave the library ([36b1f01](https://github.com/hotcodepush-team/core-android/commit/36b1f01f43b986ae268dc1e1a8be43363fcb9348))
* the Android library protocol-android — the core moved from capacitor-live-updates, renamed, minSdk 23 ([8ffd5bd](https://github.com/hotcodepush-team/core-android/commit/8ffd5bd62b6f469dee9385c0241b1a1124b0a5a0))
* the app's rollback is rollbackUpdate() ([ba1dc0e](https://github.com/hotcodepush-team/core-android/commit/ba1dc0e377404465c1b16d1ceac6ebc8ea75ca4c))
* the apply moment is decided at the download and stored with the release ([28a52e9](https://github.com/hotcodepush-team/core-android/commit/28a52e98e47db20b69bd6914538a21a5975853e6))
* the channel's id is null while no id is known ([2f60078](https://github.com/hotcodepush-team/core-android/commit/2f60078a76cb7d837f3a506d65d296da5bef17ad))
* the core brings OkHttp 4.12.0, never OkHttp 5 ([63a156c](https://github.com/hotcodepush-team/core-android/commit/63a156c06c55ba21d4f00fe2671cb37555f18347))
* the core emits the download progress event ([468ce4b](https://github.com/hotcodepush-team/core-android/commit/468ce4b40b7adc779bded84fcad85407b91a27d9))
* the core owns what every bridge repeated ([1506fc4](https://github.com/hotcodepush-team/core-android/commit/1506fc48d3ca40164db4c540b6bf4917ea9bc094))
* the core ships the Android state store and scheduler ([de4abb8](https://github.com/hotcodepush-team/core-android/commit/de4abb88048048abf47af6286cb3b1acf452df98))
* the debug screen, the session log behind it and the report the share sheet carries ([52fcbc5](https://github.com/hotcodepush-team/core-android/commit/52fcbc55a43d915ae4beb74da3c4f82c58ef1fd1)), closes [#3](https://github.com/hotcodepush-team/core-android/issues/3)
* the device never sends a report or a batch the events endpoint refuses ([87b1179](https://github.com/hotcodepush-team/core-android/commit/87b117908972749487bd4c17ad072f6f8643e0f4))
* the host asks for the start's bundle synchronously, and the start fails open ([60fd92a](https://github.com/hotcodepush-team/core-android/commit/60fd92ab271a1eae944b8ac7f3c63f591f954f2e))
* the index decides the pack; an unlisted base takes the streamed pack directly ([ab75db2](https://github.com/hotcodepush-team/core-android/commit/ab75db2afeef71341bd8c1e0137170c022fd7ed2))
* the index says when it was published, the host rows go ([ba7e83b](https://github.com/hotcodepush-team/core-android/commit/ba7e83bff9cc18f85894bf9816948f93d9e24627))
* the library converges on the protocol fixtures and reads the wire strictly ([d9360e0](https://github.com/hotcodepush-team/core-android/commit/d9360e0604e013ee7a2dfc2e58e13b90ac3d5e91)), closes [#1](https://github.com/hotcodepush-team/core-android/issues/1)
* the manifest's signature is verified against publicKeys — the pinned scheme list, the key the keyId names, RSA everywhere and Ed25519 where the platform has it ([c830fce](https://github.com/hotcodepush-team/core-android/commit/c830fce25f5543a93fae0d81476205424fdce9ce)), closes [#2](https://github.com/hotcodepush-team/core-android/issues/2)
* the pack's patch entries applied from the delta pack, and patches leave the manifest and the envelope ([7423246](https://github.com/hotcodepush-team/core-android/commit/742324658357bcf40f165296bcbc745e4106df15)), closes [#4](https://github.com/hotcodepush-team/core-android/issues/4)
* the resource file's keys say their category and their unit ([52b8c19](https://github.com/hotcodepush-team/core-android/commit/52b8c19cfa3263a41c62e71a890969d2d5c2d07f))
* the rollback notice is stored and announced at every start until the app is up after it ([900f0ed](https://github.com/hotcodepush-team/core-android/commit/900f0edd2ffdb1d0471147779ba06c2828369261))
* the runtime condition and the runtime version leave the core ([1366505](https://github.com/hotcodepush-team/core-android/commit/1366505c6d363a75fa4a76bade547685c4cfc91b))
* the streamed delta from the updates host for a base the bucket holds no delta for ([d720db4](https://github.com/hotcodepush-team/core-android/commit/d720db40d4af290c83ccf8c16e0bc59838f695b2)), closes [#5](https://github.com/hotcodepush-team/core-android/issues/5)
* the third stage is apply, and APPLIED says the apply happened ([5ad3d24](https://github.com/hotcodepush-team/core-android/commit/5ad3d24dd818990dc5b440eb2c9cd9ef6f1df058))
* the wire after the contract audit: no rolled_back event, conditionType, pausedAt, rolloutPercent, STATE_VERSION 6 ([#26](https://github.com/hotcodepush-team/core-android/issues/26)) ([85278b8](https://github.com/hotcodepush-team/core-android/commit/85278b8af77698aea36081b0d1fa4ca69cff2979))


### Bug Fixes

* a binary version reads with zeros to three components, and no component overflows ([485a0d1](https://github.com/hotcodepush-team/core-android/commit/485a0d12d6f2231fae53d250a111a0be3ecef393))
* a call joins the running cycle of its own stage ([7572b91](https://github.com/hotcodepush-team/core-android/commit/7572b91acb0054ba68ba9ec18d8fec8060cd75b2))
* a device with a base takes the delta route for any missing file, its static URL first ([b962f88](https://github.com/hotcodepush-team/core-android/commit/b962f88ea12d4cbb27b36eb29be6d1cc731d1be3)), closes [#22](https://github.com/hotcodepush-team/core-android/issues/22)
* a download is tried three times inside the check before it fails ([551ea0a](https://github.com/hotcodepush-team/core-android/commit/551ea0ab06125daf176256ba3c51070970651089))
* a download never answers UPDATED ([5d3f1e9](https://github.com/hotcodepush-team/core-android/commit/5d3f1e9f9ddc1e5ac64b348650d957701493f53a))
* a FAILED or BUILD_DEBUG result carries no release when none was evaluated ([4c001c3](https://github.com/hotcodepush-team/core-android/commit/4c001c39df7ca584c92892e969e7a77ff3d84cbb)), closes [#11](https://github.com/hotcodepush-team/core-android/issues/11)
* a headless start keeps the persisted next-start marker ([e58d517](https://github.com/hotcodepush-team/core-android/commit/e58d5170a7ac5e327b2df0205c0c2abb939afc0c))
* a manifest off the index's hash or naming another bundle is MANIFEST_INVALID ([f61bea3](https://github.com/hotcodepush-team/core-android/commit/f61bea30e7834e2fe4c60582657b02cfb226aae3))
* a new binary drops the kept index ([bf0de8a](https://github.com/hotcodepush-team/core-android/commit/bf0de8a58843ce1dbb921fd680cc150568016f9d))
* a process started in the background reports background ([5245a4a](https://github.com/hotcodepush-team/core-android/commit/5245a4ab33311b1ab47ead2fecdba36add45ad70))
* a readiness-timeout rollback reloads while an apply is held ([eacfde1](https://github.com/hotcodepush-team/core-android/commit/eacfde15e9a71d90b2616e51bc4d6d74323642b0))
* a release of the embedded bundle is adopted in place, not installed ([268fdbe](https://github.com/hotcodepush-team/core-android/commit/268fdbe082469e81fb9d8ca05baf5446c71aac11))
* a release of the embedded bundle reports no bytes to download ([04802a6](https://github.com/hotcodepush-team/core-android/commit/04802a6a734f4674df81207f6e7ea3a143fdddd4))
* a release of the embedded bundle skips the download gate ([fc19437](https://github.com/hotcodepush-team/core-android/commit/fc19437858081a3f1c8c3d1574d08e68c2a6f9ec))
* a release of the embedded bundle switches without the gate and is never blocklisted ([6496a1a](https://github.com/hotcodepush-team/core-android/commit/6496a1a2d662f0929a97e104613597dbe10a46fd))
* a reload after the start's timeout unsettles the start ([bd9ce13](https://github.com/hotcodepush-team/core-android/commit/bd9ce13022a762314046f418f2ea39d45075343d))
* a reload the core performs drops the restart held at the gate ([78b4413](https://github.com/hotcodepush-team/core-android/commit/78b4413627a10fefedf2838205d26029d34f441b))
* a restart held at the gate drops a release revoked while it waited ([45f11f7](https://github.com/hotcodepush-team/core-android/commit/45f11f7c9531c9b3fa34815798aad3c0dca2561b))
* a resume never installs a mandatory release the app took over ([5625b82](https://github.com/hotcodepush-team/core-android/commit/5625b82df8fabe3b77ab85867aa6e6dc4e1d4808))
* a rollback to the embedded bundle sends toReleaseId as null, the key the events endpoint requires ([8d6fac3](https://github.com/hotcodepush-team/core-android/commit/8d6fac39d6af4b8d1b5ca8cec57550a038ca7ae6))
* a single file's and a patch's temporaries are named per download ([c5a4253](https://github.com/hotcodepush-team/core-android/commit/c5a4253ed436df4a60f33282c92de579d99c72be))
* a stored value that does not decode drops the cache instead of throwing ([1183d07](https://github.com/hotcodepush-team/core-android/commit/1183d074b9f0d9dfd2fea63d981b13a9a9905232))
* a wire URL is http or https, and the configured-host rule is the configuration's ([42af753](https://github.com/hotcodepush-team/core-android/commit/42af753cbb2723db60e9436a0e98e2cbd6a46198))
* an attribute value is 256 code points without a C0, DEL or C1 control ([112e58f](https://github.com/hotcodepush-team/core-android/commit/112e58f232a52ceb406e2963c2e63c569e5643cc))
* before the app is up in this run only a rollback reloads; checking and downloading never wait ([e240ea1](https://github.com/hotcodepush-team/core-android/commit/e240ea1c49df64c06518cfaef0433df79a10b1fe))
* BouncyCastle comes as bcprov-jdk15to18, the artifact an app's Jetifier can read ([c409e4e](https://github.com/hotcodepush-team/core-android/commit/c409e4ee741c370895075cadd02635992f810097)), closes [#2](https://github.com/hotcodepush-team/core-android/issues/2)
* checkInterval has a floor of 60 seconds ([b914228](https://github.com/hotcodepush-team/core-android/commit/b914228026eff590fcaa46c48e7eacc84f28999d))
* mandatory counts only the skipped releases the device could take ([63988bc](https://github.com/hotcodepush-team/core-android/commit/63988bc78b3b9721688d1b447d07228f33eab300))
* nothing thrown inside a cycle or a task of the core crashes the app ([f659923](https://github.com/hotcodepush-team/core-android/commit/f659923e694419f67b70974ed9007f59badb985f))
* notifyReady's previousRelease is the release before a switch too ([4c0414f](https://github.com/hotcodepush-team/core-android/commit/4c0414fbd723ff7ebea17fe518237cd934872372))
* one cycle runs at a time, whatever its stage ([8b53f09](https://github.com/hotcodepush-team/core-android/commit/8b53f096d13801d4a0f1996088e6c3d7832f9fb1))
* reportedAt keeps the month's first acknowledged device report ([be54eea](https://github.com/hotcodepush-team/core-android/commit/be54eea698493fe290a3bd11152a8cb01907ec60)), closes [#21](https://github.com/hotcodepush-team/core-android/issues/21)
* the check adopts the revoke target that carries the running bundle ([751bcf7](https://github.com/hotcodepush-team/core-android/commit/751bcf79be2d6b53cc13afb312672b20bd86229c))
* the debug screen labels the kept stamp Reported at, and the fixtures move to protocol cf04f51 ([38ea189](https://github.com/hotcodepush-team/core-android/commit/38ea1896a1d920cc98b170e1a24943c5abd44337))
* the debug screen's check runs off the main thread ([db83792](https://github.com/hotcodepush-team/core-android/commit/db83792324d642afd256c50214f19417801bbc2e))
* the file cleanup runs after the start, never on it and never beside a cycle ([3d04228](https://github.com/hotcodepush-team/core-android/commit/3d04228eb0610cd766980f2273ef7f0a08bcdab4))
* the index sequence is a 64-bit integer ([72208bb](https://github.com/hotcodepush-team/core-android/commit/72208bb60d5ec0816db1a352cebef0d4d3bd26fd))
* the never-backwards rule holds for one day ([19e5abc](https://github.com/hotcodepush-team/core-android/commit/19e5abc7cd60316354fd06fa329b348f1be81afe))
* the readiness window pauses in the background ([eba3dde](https://github.com/hotcodepush-team/core-android/commit/eba3ddea1f766c972e382e900d6706bb87e59169))
* the revoke path adopts the running bundle instead of reloading it ([d208abf](https://github.com/hotcodepush-team/core-android/commit/d208abf6c1a07b5c1f0b66786712c7944867b97d))
* the signature allow-list is ed25519 alone — the Expo bridge's RSA scheme verifies nothing on a device ([310d5a3](https://github.com/hotcodepush-team/core-android/commit/310d5a35d3a2c4b8d3f3d2d0fd8cd3cddf8a9b79)), closes [#2](https://github.com/hotcodepush-team/core-android/issues/2)
* the start sync and the SDK's own restarts wait until the app is up in this run ([d36f5f8](https://github.com/hotcodepush-team/core-android/commit/d36f5f87aaee76ee7428ddcbf79ced7a79bf1a07))
* the start's own reload unsettles the start like every reload the core performs ([8b9cb81](https://github.com/hotcodepush-team/core-android/commit/8b9cb81f8dd621faf4e7b19efa5c0f91c9400337))


### Code Refactoring

* appliedIndexSequence leaves DeviceInfo ([c704ae9](https://github.com/hotcodepush-team/core-android/commit/c704ae975f468aa52b04fd67cab38b6fb0bd8517))
* the library is core-android, its Kotlin package com.hotcodepush.core ([ccb2789](https://github.com/hotcodepush-team/core-android/commit/ccb2789ec1626c138043f6d2b132fa7f4033336b))
