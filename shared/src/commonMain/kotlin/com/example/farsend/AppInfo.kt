package com.example.farsend

/**
 * App name, folder names, identifiers and file names in one place.
 *
 * Keeping every name here stops Android, Windows and iOS (Kotlin and Swift) from hard-coding
 * "FarSend" / "farsend" separately and drifting apart. Storage locations, notification IDs and
 * preference keys are all built from these values, so changing one is a breaking change.
 *
 * From Swift use `AppInfo.shared.name` etc. (`const val` is a property in Swift too).
 *
 * Out of scope (can't be built from here):
 * - Build-time name/version (Android label, versionName; Windows packageName, packageVersion) live in gradle.properties.
 *   iOS uses project.yml (XcodeGen). Both are fixed at build time, so they can't reference Kotlin constants.
 * - The wire protocol ALPN ([com.example.farsend.proto.Wire.ALPN_STRING]) must match the Rust side (native/farsend-iroh)
 *   and the deployed browser client, so it is a protocol constant kept in Wire, independent of the name
 *   (it still reads "peersync/0"; see Wire).
 */
object AppInfo {
    /** Display name. Basis for screen titles, tray, window name, notification titles and folder names. */
    const val NAME = "FarSend"

    /** Lowercase identifier (pairing code URI scheme, file name prefix). */
    const val SLUG = "farsend"

    /** Folder name for received files (Android `Download/FarSend`, Windows `<Downloads>\FarSend`). */
    const val DOWNLOAD_FOLDER = NAME

    /** Suffix of partially received files (`<name>.farsend-part`). Used to resume. */
    const val PART_SUFFIX = ".$SLUG-part"

    /** URI scheme of the pairing code (QR, shared text) (`farsend:<ID>?name=<device name>`). */
    const val PAIRING_SCHEME = SLUG

    /** Log folder name (iOS `Documents/logs`, Windows `<FarSend folder>\data\logs`). */
    const val LOG_DIR = "logs"

    /** Log file name base (iOS `farsend.log`, Windows `farsend-YYYYMMDD.log`). */
    const val LOG_FILE_BASE = SLUG

    /** Android SharedPreferences name (`farsend`; fixed, since changing it loses settings and the device ID). */
    const val PREFS_NAME = SLUG

    /** Windows settings file name (`<FarSend folder>\data\settings.json`). */
    const val SETTINGS_FILE = "settings.json"

    /** Android: the last crash, written by the uncaught-exception handler and logged on the next launch. */
    const val CRASH_FILE = "last-crash.txt"

    /** Windows: settings and logs live in this folder next to the exe (portable app). */
    const val PORTABLE_DATA_DIR = "data"

    /** Log tag (`FarSend/<name>`). Builds the same form as `const val TAG = "${AppInfo.NAME}/Main"` via a function. */
    fun tag(name: String): String = "$NAME/$name"

    /**
     * Identifier for iOS notifications, background tasks, etc. (`FarSend.<suffix>`).
     * From Swift: `AppInfo.shared.identifier(suffix:)` (avoids the Objective-C reserved word `id` as the name).
     */
    fun identifier(suffix: String): String = "$NAME.$suffix"

    /** Temp folder where iOS "Send" exports photos etc. (`tmp/FarSendOutbox`). */
    const val OUTBOX_FOLDER = "${NAME}Outbox"
}
