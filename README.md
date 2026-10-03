# protocol-android

`com.hotcodepush:protocol-android` is the HotCodePush update-protocol client for Android: the wire types, the evaluator, the downloader and the state machine behind every HotCodePush SDK on Android, held to the same fixture suite as the JavaScript and iOS clients. Learn more at [hotcodepush.com](https://hotcodepush.com).

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
