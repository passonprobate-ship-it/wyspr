package com.wyspr.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton row holding the local user's public-facing profile.
 *
 * The profile is served over Tor at `http://<onion>:80/` by
 * `ProfileHttpServer`. Anyone with the user's onion can fetch it;
 * the user controls every visible field. Defaults are empty so a
 * fresh install advertises nothing until the user opts in.
 *
 * Row identity is always `id = 0` — Room rejects `Boolean` PKs and
 * a singleton-via-PrimaryKey is the cleanest pattern.
 */
@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey @ColumnInfo("id") val id: Int = 0,
    /** Display name. Free-form text, can be empty/null. */
    @ColumnInfo("display_name") val displayName: String?,
    /** Short bio paragraph, plaintext (no HTML / markdown rendering in v1). */
    @ColumnInfo("bio") val bio: String?,
    /** Single user-chosen emoji (or null) shown as the avatar. */
    @ColumnInfo("avatar_emoji") val avatarEmoji: String?,
    /** Newline-separated URLs the user wants to advertise. */
    @ColumnInfo("links") val links: String?,
)
