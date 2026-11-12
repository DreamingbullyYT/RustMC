package dev.ferrite.core

import java.io.File
import java.util.UUID

data class Account(val name: String, val uuid: String, val kind: String = "offline")

/** Offline accounts work without internet. The UUID is derived like the vanilla client does for offline players. */
class AccountStore(private val file: File) {
    companion object {
        fun validName(n: String) = n.matches(Regex("[A-Za-z0-9_]{3,16}"))
        fun offlineUuid(name: String): String = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray()).toString()
    }
    fun all(): List<Account> = runCatching { Json.parse(file.readText()).arr().map { val m = it.obj(); Account(m["name"].str(), m["uuid"].str(), m["kind"].str("offline")) } }.getOrDefault(emptyList())
    fun addOffline(name: String): Account {
        require(validName(name)) { "name must be 3-16 letters, digits or _" }
        require(all().none { it.name.equals(name, true) }) { "account exists" }
        val a = Account(name, offlineUuid(name)); file.parentFile?.mkdirs()
        file.writeText(Json.write((all() + a).map { linkedMapOf("name" to it.name, "uuid" to it.uuid, "kind" to it.kind) }, true)); return a
    }
}
