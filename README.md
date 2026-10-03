# protocol-android

`com.hotcodepush:protocol-android` is the HotCodePush update-protocol client for Android: the wire types, the evaluator, the downloader with its signature check, the state machine and the debug screen behind every HotCodePush SDK on Android, held to the same fixture suite as the JavaScript and iOS clients. Learn more at [hotcodepush.com](https://hotcodepush.com).

## Installation

The library is not on Maven Central yet; JitPack builds any commit on request, and a consumer pins one commit and bumps it deliberately, never a branch.

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.hotcodepush-team:protocol-android:<sha>'
}
```

The library supports Android 6.0 (API 23) and later.

## Usage

```kotlin
import com.hotcodepush.protocol.ChannelIndex
import com.hotcodepush.protocol.Evaluator
import org.json.JSONObject

val index = ChannelIndex.fromJson(JSONObject(body))
val evaluation = Evaluator.evaluation(index, deviceInfo)
// evaluation.outcome: the release to take, or the reason not to; evaluation.verdicts: every release explained
```

An SDK opens the debug screen from any context. The screen shows the device, the channel, the releases, the last check with its code, the index and this session's log, and its share button hands the same content to the share sheet as text:

```kotlin
DebugScreen.show(context, core)
```

Once the resource file lists `publicKeys`, the downloader refuses a manifest that is unsigned or whose signature does not verify against them, before it fetches a byte of the bundle. Ed25519 is verified by the platform's own implementation, which Android has from version 13 (API 33); an older device refuses a signed update.

The library is the foundation of the HotCodePush SDKs, not their supported API: an app uses the SDK for its framework.

## Documentation

See [hotcodepush.com/docs](https://hotcodepush.com/docs).

## Development

```sh
nvm use
npm ci                       # the protocol fixtures the tests read
./gradlew lint test
```

`npm run verify` adds the release build; `./gradlew publishToMavenLocal` builds the Maven publication JitPack serves.

## License

See [LICENSE](./LICENSE).
