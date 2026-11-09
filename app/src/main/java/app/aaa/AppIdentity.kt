package app.aaa

/**
 * Stable identity for an installed app in a specific Android user/profile.
 * The package name alone is not unique on multi-user devices.
 */
internal fun appIdentityKey(packageName: String, userId: Int): String =
    "$packageName:$userId"
