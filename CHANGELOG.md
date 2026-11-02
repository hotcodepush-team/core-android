# Changelog

## [0.2.0](https://github.com/hotcodepush-team/core-android/compare/0.1.1...0.2.0) (2026-11-02)


### ⚠ BREAKING CHANGES

* ProcessState and ActivityManagerProcessState are AppState and ActivityManagerAppState, GetStateResult.index replaces indexSequence and indexFetchedAt, DownloadOutcome.bytes is downloadSizeBytes, and Gzip.inflate is Gzip.decompress.

### Features

* Configuration.load answers the configuration or the not-configured message ([#46](https://github.com/hotcodepush-team/core-android/issues/46)) ([b29f71a](https://github.com/hotcodepush-team/core-android/commit/b29f71ae4d63957fc5f9bb9580f280920d052e8b))


### Code Refactoring

* one shape and one name per seam and function, as in core-ios ([#44](https://github.com/hotcodepush-team/core-android/issues/44)) ([d219f5d](https://github.com/hotcodepush-team/core-android/commit/d219f5d26a9082935141d04bcd6e7feb0416e3d6))

## [0.1.1](https://github.com/hotcodepush-team/core-android/compare/0.1.0...0.1.1) (2026-11-02)


### Bug Fixes

* **ci:** the release job's archive variable is not named ZIP ([#42](https://github.com/hotcodepush-team/core-android/issues/42)) ([ec153a7](https://github.com/hotcodepush-team/core-android/commit/ec153a7c1fbfff2963dcdf430b755b7210cf99d3))
* **ci:** the release zip is written into the workspace, and a dispatch re-attaches it ([#40](https://github.com/hotcodepush-team/core-android/issues/40)) ([c7b24d6](https://github.com/hotcodepush-team/core-android/commit/c7b24d6e0fb57c60ad605c24f811a948d1096d6a))

## 0.1.0 (2026-11-02)


### Features

* the first release of the Android core ([147fb9b](https://github.com/hotcodepush-team/core-android/commit/147fb9b4bcb3d9bd50c361c5f33f6315375fc350))
