package com.samsung.screenshoterjetpackcompose

import android.content.Context
import androidx.annotation.StringRes
import java.util.Locale
import kotlin.collections.any
import kotlin.collections.map
import kotlin.collections.mapNotNull
import kotlin.collections.sortedBy
import kotlin.collections.toMutableList
import kotlin.collections.toSet
import kotlin.text.isEmpty
import kotlin.text.lowercase
import kotlin.text.replace
import kotlin.text.split
import kotlin.text.trim

object CollectionRepository {

    private const val PREF_NAME = "collection_prefs"
    private const val KEY_CUSTOM_COLLECTIONS = "custom_collection_entries"
    private const val KEY_LAST_USERNAME = "last_zip_username"
    private const val ENTRY_DELIMITER = "||"

    data class CustomCollection(val key: String, val label: String)

    sealed class AddResult {
        data class Success(val collections: List<CustomCollection>) : AddResult()
        data class Error(@StringRes val messageRes: Int) : AddResult()
    }

    fun load(context: Context): List<CustomCollection> {
        val stored = prefs(context).getStringSet(KEY_CUSTOM_COLLECTIONS, emptySet()) ?: emptySet()
        return stored.mapNotNull { decodeEntry(it) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    fun add(
        context: Context,
        rawLabel: String,
        reservedKeys: Set<String>
    ): AddResult {
        val trimmed = rawLabel.trim()
        if (trimmed.isEmpty()) {
            return AddResult.Error(R.string.error_collection_empty)
        }

        val sanitizedKey = ScreenCaptureManager.normalizeDirectoryName(trimmed)
        if (sanitizedKey.isEmpty() || reservedKeys.contains(sanitizedKey)) {
            return AddResult.Error(R.string.error_collection_duplicate)
        }

        val current = load(context).toMutableList()
        if (current.any { it.key == sanitizedKey }) {
            return AddResult.Error(R.string.error_collection_duplicate)
        }

        val normalizedLabel = trimmed.replace(ENTRY_DELIMITER, " ")
        current.add(CustomCollection(sanitizedKey, normalizedLabel))
        val updated = current.sortedBy { it.label.lowercase(Locale.getDefault()) }
        prefs(context).edit()
            .putStringSet(KEY_CUSTOM_COLLECTIONS, updated.map { encodeEntry(it) }.toSet())
            .apply()

        return AddResult.Success(updated)
    }

    fun getLastZipUsername(context: Context): String? =
        prefs(context).getString(KEY_LAST_USERNAME, null)

    fun saveLastZipUsername(context: Context, username: String) {
        prefs(context).edit()
            .putString(KEY_LAST_USERNAME, username)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun encodeEntry(entry: CustomCollection): String =
        "${entry.key}$ENTRY_DELIMITER${entry.label}"

    private fun decodeEntry(value: String): CustomCollection? {
        val parts = value.split(ENTRY_DELIMITER, limit = 2)
        if (parts.size != 2) return null
        val key = parts[0]
        val label = parts[1]
        if (key.isEmpty()) return null
        return CustomCollection(key, label)
    }
}


