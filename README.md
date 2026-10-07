# core-android

`com.hotcodepush:core-android` is the HotCodePush update-protocol client for Android: the wire types, the evaluator, the downloader with its signature check and the byte-level patches it applies from delta packs, the state machine and the debug screen behind every HotCodePush SDK on Android, held to the same fixture suite as the JavaScript and iOS clients. Learn more at [hotcodepush.com](https://hotcodepush.com).

## Installation

The library is not on Maven Central yet; CI publishes every commit on `main` to this repository's `maven` branch, versioned by the commit's full sha, and a consumer pins one commit and bumps it deliberately.

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
```

An SDK opens the debug screen from any context. The screen shows the device, the channel, the releases, the last check with its code, the index and this session's log, and its share button hands the same content to the share sheet as text:

```kotlin
DebugScreen.show(context, core)
```

Once the resource file lists `publicKeys`, the downloader refuses a manifest that is unsigned or whose signature does not verify against them, before it fetches a byte of the bundle. The one scheme is `rsa-v1_5-sha256`, RSA PKCS #1 v1.5 with SHA-256, which `java.security` verifies on every Android version: each key arrives as the base64 of its SPKI DER beside its key id, a key under 2048 bits verifies nothing, and the library brings no cryptography of its own.

The library is the foundation of the HotCodePush SDKs, not their supported API: an app uses the SDK for its framework.

## Documentation

See [hotcodepush.com/docs](https://hotcodepush.com/docs).

## Development

```sh
nvm use
npm ci                       # the protocol fixtures the tests read
./gradlew lint test
```

`npm run verify` adds the release build; `./gradlew publishToMavenLocal` installs the Maven publication, versioned by the full sha of `HEAD`, in the local Maven repository.

`./gradlew connectedDebugAndroidTest` runs the tests that need a device on a running emulator or an attached device: the signature fixtures, bspatch on its hostile patches, the patch cases of the protocol's fixtures, and a cookie a host sets coming back through the client, which OkHttp 5 would break. CI runs them on every push and pull request on two emulators, a 32-bit x86 one at API 23, where bspatch runs on a 32-bit `off_t`, and an x86_64 one at API 35.

## License

See [LICENSE](./LICENSE). The library includes FreeBSD's bspatch under the BSD 2-clause licence and the decompression of bzip2 1.0.8 under the bzip2 licence. [THIRD-PARTY-NOTICES](./THIRD-PARTY-NOTICES) carries both notices, and an app's distribution reproduces them: the BSD 2-clause licence requires it of a binary, the bzip2 licence appreciates the acknowledgment.
