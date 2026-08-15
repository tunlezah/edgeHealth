package au.mark.kinetiq.data.db

import androidx.room.migration.Migration

/**
 * Every migration ever shipped, in version order.
 *
 * Empty at version 1 — this is the baseline. The database deliberately has **no**
 * `fallbackToDestructiveMigration()`: it holds the user's entire workout history and body
 * measurements, the app is offline-only so there is no server copy, and the backup rules restore
 * only at device setup, not across an in-place app update — which is exactly when a destructive
 * fallback would fire. A missing migration must therefore fail loudly (Room throws on first DB
 * access) rather than silently dropping every table.
 *
 * To change an entity: bump `KinetiqDatabase.version`, build to emit the new `schemas/<n>.json`,
 * diff it against the previous one, write the `Migration` here, and add it to [ALL].
 */
object KinetiqMigrations {
    val ALL: Array<Migration> = arrayOf()
}
