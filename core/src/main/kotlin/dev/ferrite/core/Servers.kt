package dev.ferrite.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

data class Server(val name: String, val host: String, val port: Int = 25565, val lastJoinedMs: Long = 0) {
    init { require(host.isNotBlank() && port in 1..65535) { "bad server address" } }
    fun toMap() = linkedMapOf("name" to name, "host" to host, "port" to port, "last_joined_ms" to lastJoinedMs)
}

data class Status(val motd: String, val online: Int, val max: Int, val version: String, val pingMs: Long)

/** Saved servers for one instance (`servers.json`), with join history. */
class ServerList(private val file: File) {
    fun all(): List<Server> = runCatching { Json.parse(file.readText()).arr().map { val m = it.obj(); Server(m["name"].str(), m["host"].str(), m["port"].int(25565), (m["last_joined_ms"] as? Number)?.toLong() ?: 0) } }.getOrDefault(emptyList())
    private fun write(l: List<Server>) { file.parentFile?.mkdirs(); file.writeText(Json.write(l.map { it.toMap() }, true)) }
    fun add(s: Server) { require(all().none { it.name == s.name }) { "a server named '${s.name}' exists" }; write(all() + s) }
    fun edit(name: String, s: Server) { val l = all(); require(l.any { it.name == name }) { "no such server" }; write(l.map { if (it.name == name) s.copy(lastJoinedMs = it.lastJoinedMs) else it }) }
    fun remove(name: String) = write(all().filter { it.name != name })
    fun markJoined(name: String, nowMs: Long) = write(all().map { if (it.name == name) it.copy(lastJoinedMs = nowMs) else it })
    fun history(): List<Server> = all().filter { it.lastJoinedMs > 0 }.sortedByDescending { it.lastJoinedMs }
}

/** Minecraft Server List Ping (handshake, status request, ping). Blocking; call off the main thread. */
object Ping {
    private fun varint(o: DataOutputStream, v: Int) { var x = v; while (x and 0x7F.inv() != 0) { o.writeByte(x and 0x7F or 0x80); x = x ushr 7 }; o.writeByte(x) }
    private fun readVarint(i: DataInputStream): Int { var r = 0; var s = 0; while (true) { val b = i.readUnsignedByte(); r = r or (b and 0x7F shl s); if (b and 0x80 == 0) return r; s += 7; require(s < 35) { "bad varint" } } }
    private fun packet(build: DataOutputStream.() -> Unit): ByteArray {
        val body = ByteArrayOutputStream().also { DataOutputStream(it).build() }.toByteArray()
        return ByteArrayOutputStream().also { val o = DataOutputStream(it); varint(o, body.size); o.write(body) }.toByteArray()
    }

    fun query(host: String, port: Int = 25565, timeoutMs: Int = 4000, nowNs: () -> Long = System::nanoTime): Status = Socket().use { s ->
        s.connect(InetSocketAddress(host, port), timeoutMs); s.soTimeout = timeoutMs
        val o = DataOutputStream(s.getOutputStream()); val i = DataInputStream(s.getInputStream().buffered())
        o.write(packet { varint(this, 0); varint(this, -1); varint(this, host.length); writeBytes(host); writeShort(port); varint(this, 1) }); o.flush()
        o.write(packet { varint(this, 0) }); o.flush()
        val len = readVarint(i); require(len in 1..(1 shl 20)) { "bad status length" }
        val data = ByteArray(len).also { i.readFully(it) }; val d = DataInputStream(data.inputStream())
        require(readVarint(d) == 0) { "unexpected packet" }
        val json = ByteArray(readVarint(d)).also { d.readFully(it) }.decodeToString()
        val t0 = nowNs(); o.write(packet { varint(this, 1); writeLong(1) }); o.flush()
        readVarint(i); val ms = (nowNs() - t0) / 1_000_000
        val m = Json.parse(json).obj(); val p = m["players"].obj(); val desc = m["description"]
        Status(if (desc is String) desc else desc.obj()["text"].str(), p["online"].int(), p["max"].int(), m["version"].obj()["name"].str(), ms)
    }
}
