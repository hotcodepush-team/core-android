# core-android

`com.hotcodepush:core-android` is the HotCodePush update-protocol client for Android: the wire types, the evaluator, the downloader with its signature check and delta patches, the state machine and the debug screen behind every HotCodePush SDK on Android.
It is held to the same fixture suite as the [JavaScript](https://github.com/hotcodepush-team/protocol) and [iOS](https://github.com/hotcodepush-team/core-ios) clients.

## Installation

The library is not on Maven Central yet. CI publishes every commit on `main` to this repository's `maven` branch, versioned by the commit's full sha; pin one commit and bump it deliberately.

```groovy
repositories {
    maven { url 'https://raw.githubusercontent.com/hotcodepush-team/core-android/maven/' }
}

dependencies {
    implementation 'com.hotcodepush:core-android:<full sha>'
}
```

The library supports Android 6.0 (API 23) and later.

## Usage

```kotlin
import com.hotcodepush.core.ChannelIndex
import com.hotcodepush.core.Evaluator
import org.json.JSONObject

val index = ChannelIndex.fromJson(JSONObject(body))
val evaluation = Evaluator.evaluation(index, deviceInfo)
// evaluation.outcome: the release to take, or the reason not to; evaluation.verdicts: every release explained

DebugScreen.show(context, core)
```

Once the resource file lists `publicKeys`, the downloader refuses a manifest that is unsigned or whose signature does not verify, before it fetches a byte of the bundle.
The library is the foundation of the HotCodePush SDKs, not their supported API: an app uses the SDK for its framework.

## Documentation

See [hotcodepush.com/docs](https://hotcodepush.com/docs).

## Development

```sh
nvm use
npm ci                                 # the protocol fixtures the tests read
npm test                               # ./gradlew test
npm run lint                           # ./gradlew lint
npm run fmt                            # ./gradlew ktlintFormat
npm run verify                         # lint, test and the release build
./gradlew connectedDebugAndroidTest    # the on-device tests, on a running emulator
./gradlew publishToMavenLocal          # the publication, versioned by the full sha of HEAD
```

`CLAUDE.md` holds the layout, the rules and the publishing flow.

## License

See [LICENSE](./LICENSE).
The library includes FreeBSD's bspatch under the BSD 2-clause licence and the decompression of bzip2 1.0.8 under the bzip2 licence; [THIRD-PARTY-NOTICES](./THIRD-PARTY-NOTICES) carries both notices, which an app's distribution reproduces.
