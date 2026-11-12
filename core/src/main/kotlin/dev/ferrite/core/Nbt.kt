package dev.ferrite.core

import java.io.DataInputStream
import java.io.File
import java.util.zip.GZIPInputStream

/** Reads (never writes) Java-edition NBT, enough for `level.dat`. Compounds become maps, lists become lists. */
object Nbt {
    fun readGzipFile(f: File): Map<String, Any?> = GZIPInputStream(f.inputStream().buffered()).use { read(DataInputStream(it)) }

    fun read(i: DataInputStream): Map<String, Any?> {
        require(i.readByte().toInt() == 10) { "root is not a compound" }
        i.skipBytes(i.readUnsignedShort())
        @Suppress("UNCHECKED_CAST") return payload(i, 10, 0) as Map<String, Any?>
    }

    private fun payload(i: DataInputStream, t: Int, depth: Int): Any? {
        require(depth < 64) { "NBT nesting too deep" }
        return when (t) {
            1 -> i.readByte(); 2 -> i.readShort(); 3 -> i.readInt(); 4 -> i.readLong(); 5 -> i.readFloat(); 6 -> i.readDouble()
            7 -> ByteArray(len(i)).also { i.readFully(it) }
            8 -> i.readUTF()
            9 -> { val et = i.readByte().toInt(); val n = len(i); List(n) { payload(i, et, depth + 1) } }
            10 -> { val m = LinkedHashMap<String, Any?>()
                while (true) { val et = i.readByte().toInt(); if (et == 0) break; val k = i.readUTF(); m[k] = payload(i, et, depth + 1) }
                m }
            11 -> IntArray(len(i)) { i.readInt() }
            12 -> LongArray(len(i)) { i.readLong() }
            else -> error("bad NBT tag $t")
        }
    }
    private fun len(i: DataInputStream): Int = i.readInt().also { require(it in 0..(1 shl 24)) { "bad NBT length" } }
}
