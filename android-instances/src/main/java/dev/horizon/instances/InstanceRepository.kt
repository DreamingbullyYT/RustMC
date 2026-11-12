package dev.horizon.instances

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Stores launcher-owned instance profiles under filesDir/instances.
 * No engine files are modified and no arbitrary external paths are accepted.
 */
class InstanceRepository(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "instances")
    private val preferences = appContext.getSharedPreferences("instance_profiles", Context.MODE_PRIVATE)

    @Synchronized
    fun listInstances(): List<InstanceProfile> {
        if (!root.exists()) root.mkdirs()
        val activeId = preferences.getString(KEY_ACTIVE, null)
        return root.listFiles()?.filter { it.isDirectory }?.mapNotNull { directory ->
            val id = directory.name
            val name = preferences.getString(nameKey(id), null) ?: return@mapNotNull null
            val created = preferences.getLong(createdKey(id), directory.lastModified())
            val updated = preferences.getLong(updatedKey(id), directory.lastModified())
            InstanceProfile(id, name, directory.absolutePath, created, updated, id == activeId)
        }?.sortedWith(compareByDescending<InstanceProfile> { it.isActive }.thenBy { it.name.lowercase() })
            ?: emptyList()
    }

    @Synchronized
    fun createInstance(rawName: String): InstanceProfile {
        val name = validateName(rawName)
        if (listInstances().any { it.name.equals(name, ignoreCase = true) }) {
            throw IllegalArgumentException("An instance with this name already exists.")
        }
        val id = UUID.randomUUID().toString()
        val directory = File(root, id)
        if (!directory.mkdirs()) throw IllegalStateException("Could not create the instance directory.")
        val now = System.currentTimeMillis()
        val layout = listOf("game", "mods", "config", "resourcepacks", "shaderpacks", "logs", "screenshots", "saves")
        layout.forEach { folder ->
            if (!File(directory, folder).mkdirs()) {
                directory.deleteRecursively()
                throw IllegalStateException("Could not initialize the instance folder: $folder")
            }
        }
        preferences.edit()
            .putString(nameKey(id), name)
            .putLong(createdKey(id), now)
            .putLong(updatedKey(id), now)
            .apply()
        if (preferences.getString(KEY_ACTIVE, null) == null) {
            preferences.edit().putString(KEY_ACTIVE, id).apply()
        }
        return listInstances().first { it.id == id }
    }

    @Synchronized
    fun renameInstance(id: String, rawName: String): InstanceProfile {
        val name = validateName(rawName)
        val current = listInstances().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Instance not found.")
        if (listInstances().any { it.id != id && it.name.equals(name, ignoreCase = true) }) {
            throw IllegalArgumentException("An instance with this name already exists.")
        }
        val now = System.currentTimeMillis()
        preferences.edit().putString(nameKey(id), name).putLong(updatedKey(id), now).apply()
        return current.copy(name = name, updatedAt = now)
    }

    @Synchronized
    fun deleteInstance(id: String) {
        val profile = listInstances().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Instance not found.")
        val directory = File(root, profile.id)
        if (!directory.canonicalFile.parentFile.equals(root.canonicalFile)) {
            throw IllegalStateException("Invalid instance directory.")
        }
        if (!directory.deleteRecursively()) throw IllegalStateException("Could not delete the instance files.")
        preferences.edit()
            .remove(nameKey(id))
            .remove(createdKey(id))
            .remove(updatedKey(id))
            .apply()
        if (preferences.getString(KEY_ACTIVE, null) == id) {
            val fallback = listInstances().firstOrNull()?.id
            preferences.edit().putString(KEY_ACTIVE, fallback).apply()
        }
    }

    @Synchronized
    fun selectInstance(id: String): InstanceProfile {
        val profile = listInstances().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Instance not found.")
        preferences.edit().putString(KEY_ACTIVE, id).apply()
        return profile.copy(isActive = true)
    }

    fun getActiveInstance(): InstanceProfile? = listInstances().firstOrNull { it.isActive }

    private fun validateName(raw: String): String {
        val name = raw.trim()
        require(name.isNotEmpty()) { "Enter an instance name." }
        require(name.length <= 40) { "The name must be 40 characters or fewer." }
        require(name.none { it == '/' || it == '\\' || it.isISOControl() }) {
            "The name contains unsupported characters."
        }
        return name
    }

    private fun nameKey(id: String) = "name_$id"
    private fun createdKey(id: String) = "created_$id"
    private fun updatedKey(id: String) = "updated_$id"

    private companion object {
        const val KEY_ACTIVE = "active_instance_id"
    }
}
