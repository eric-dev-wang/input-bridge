// Top-level build file where you can add configuration options common to all sub-projects/modules.
private val ANDROID_MAX_VERSION_CODE = 2_100_000_000L
private val DEFAULT_INPUT_BRIDGE_SHARED_SECRET = "input-bridge-development-default-secret"

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
}

private data class BridgeVersion(
  val major: Long,
  val minor: Long,
  val patch: Long,
  val isSnapshot: Boolean,
) {
  val displayName: String
    get() = if (isSnapshot) "0.0.0-SNAPSHOT" else "$major.$minor.$patch"

  val versionCode: Int
    get() {
      if (isSnapshot) return 1
      val calculated = try {
        Math.addExact(
          Math.addExact(Math.multiplyExact(major, 1_000_000L), Math.multiplyExact(minor, 1_000L)),
          patch,
        )
      } catch (exception: ArithmeticException) {
        error("bridgeVersion produces an Android versionCode outside the supported range.")
      }
      check(calculated in 1L..ANDROID_MAX_VERSION_CODE) {
        "bridgeVersion produces an Android versionCode outside the supported range: $calculated"
      }
      return calculated.toInt()
    }
}

private fun parseBridgeVersion(rawVersion: String): BridgeVersion {
  if (rawVersion == "0.0.0-SNAPSHOT") {
    return BridgeVersion(major = 0, minor = 0, patch = 0, isSnapshot = true)
  }

  val match = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$").matchEntire(rawVersion)
    ?: error("bridgeVersion must be SemVer major.minor.patch or 0.0.0-SNAPSHOT: $rawVersion")
  val (major, minor, patch) = match.destructured
  val parsedMajor = major.toLongOrNull() ?: error("bridgeVersion major is out of range: $rawVersion")
  val parsedMinor = minor.toLongOrNull() ?: error("bridgeVersion minor is out of range: $rawVersion")
  val parsedPatch = patch.toLongOrNull() ?: error("bridgeVersion patch is out of range: $rawVersion")
  check(parsedMinor in 0L..999L && parsedPatch in 0L..999L) {
    "bridgeVersion minor and patch must be between 0 and 999: $rawVersion"
  }
  return BridgeVersion(
    major = parsedMajor,
    minor = parsedMinor,
    patch = parsedPatch,
    isSnapshot = false,
  )
}

private val bridgeVersion = parseBridgeVersion(
  providers.gradleProperty("bridgeVersion").orElse("0.0.0-SNAPSHOT").get(),
)

allprojects {
  version = bridgeVersion.displayName
}

extra["bridgeVersionCode"] = bridgeVersion.versionCode
extra["inputBridgeSharedSecret"] = providers.gradleProperty("inputBridgeSharedSecret")
  .orElse(DEFAULT_INPUT_BRIDGE_SHARED_SECRET)
  .get()

tasks.register("verifyBridgeVersion") {
  notCompatibleWithConfigurationCache("Version parser self-check uses a Gradle script action.")
  doLast {
    check(parseBridgeVersion("0.0.0-SNAPSHOT").versionCode == 1)
    check(parseBridgeVersion("1.2.3").versionCode == 1_002_003)
    check(parseBridgeVersion("1.2.3").displayName == "1.2.3")
    check(runCatching { parseBridgeVersion("v1.2.3") }.isFailure)
    check(runCatching { parseBridgeVersion("01.2.3") }.isFailure)
    check(runCatching { parseBridgeVersion("1.2") }.isFailure)
    check(runCatching { parseBridgeVersion("1.1000.0") }.isFailure)
    check(runCatching { parseBridgeVersion("1.0.1000") }.isFailure)
    check(runCatching { parseBridgeVersion("2101.0.0").versionCode }.isFailure)
    check(runCatching { parseBridgeVersion("9223372036854.0.0").versionCode }.isFailure)
  }
}

tasks.register("printBridgeVersion") {
  doLast {
    println(bridgeVersion.displayName)
  }
}
