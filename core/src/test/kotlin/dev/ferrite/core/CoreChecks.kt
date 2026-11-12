package dev.ferrite.core

import java.io.File
import java.nio.file.Files

internal var failed = 0
internal fun check(name: String, cond: Boolean) { if (!cond) { failed++; println("FAIL $name") } else println("ok   $name") }
internal fun throws(block: () -> Unit) = runCatching(block).isFailure

fun main() {
    // Json
    val j = Json.parse("""{"a":[1,2.5,"x\nA",true,null],"b":{"c":-3}}""").obj()
    check("json parse", j["a"].arr()[2] == "x\nA" && j["b"].obj()["c"] == -3L && j["a"].arr()[1] == 2.5)
    check("json roundtrip", Json.parse(Json.write(j, true)) == j)
    check("json rejects garbage", throws { Json.parse("{\"a\":1} x") } && throws { Json.parse("[1,") })

    // Instances
    val root = Files.createTempDirectory("ferrite").toFile()
    val store = InstanceStore(root)
    val a = store.create("My World!"); val b = store.create("My World!")
    check("slug + unique ids", a.id == "my-world" && b.id == "my-world-2")
    check("empty name slug", Instance.slug("!!!") == "instance")
    store.markPlayed(b, 5)
    check("list orders by last played", store.list().map { it.id } == listOf("my-world-2", "my-world"))
    store.save(a.copy(resolutionScale = 9.0, renderDistance = 1))
    check("values clamp on load", store.list().first { it.id == a.id }.let { it.resolutionScale == 2.0 && it.renderDistance == 2 })
    store.delete(a)
    check("delete", store.list().map { it.id } == listOf("my-world-2"))
    check("bad id rejected", throws { Instance("../x", "x") })

    // Touch layouts
    val l = TouchLayout.fromJson(TouchLayout.JAVA.toJson())
    check("layout roundtrip", l == TouchLayout.JAVA)
    check("move clamps on screen", TouchLayout.MCPE.move("jump", 5.0, -5.0).widgets.first { it.id == "jump" }.let { it.x == 1 - it.size / 2 && it.y == it.size / 2 })
    check("presets have no overlaps", TouchLayout.PRESETS.all { it.overlaps().isEmpty() })
    val dup = TouchLayout("d", listOf(Widget("a", WidgetKind.BUTTON, .5, .5, .1), Widget("b", WidgetKind.BUTTON, .52, .5, .1)))
    check("overlap detected", dup.overlaps() == listOf("a" to "b"))
    check("duplicate ids rejected", throws { TouchLayout.fromJson(TouchLayout("x", listOf(dup.widgets[0], dup.widgets[0])).toJson()) })
    check("future format rejected", throws { TouchLayout.fromJson("""{"format":2,"widgets":[]}""") })

    // Crash analyzer
    check("oom", CrashAnalyzer.analyze("a\nmemory allocation of 4096 bytes failed\n").cause == Cause.OUT_OF_MEMORY)
    check("gpu", CrashAnalyzer.analyze("ERROR VK_ERROR_DEVICE_LOST in submit").cause == Cause.GPU_DRIVER)
    check("unknown shows panic", CrashAnalyzer.analyze("x\nthread main panicked at src/a.rs:1\n").evidence.contains("panicked"))
    check("empty log", CrashAnalyzer.analyze("").cause == Cause.UNKNOWN)

    // Backup
    val game = File(root, "g").apply { File(this, "saves/W1/region").mkdirs() }
    File(game, "saves/W1/level.dat").writeText("L"); File(game, "saves/W1/region/r.0.0.mca").writeText("R")
    val zip = File(root, "b.zip")
    check("backup counts files", Backup.create(game, zip) == 2)
    val game2 = File(root, "g2").apply { mkdirs() }
    check("restore counts files", Backup.restore(zip, game2) == 2 && File(game2, "saves/W1/level.dat").readText() == "L")
    check("restore never overwrites", Backup.restore(zip, game2) == 0)
    val evil = File(root, "evil.zip")
    java.util.zip.ZipOutputStream(evil.outputStream()).use { it.putNextEntry(java.util.zip.ZipEntry("saves/../../x.txt")); it.write(1); it.closeEntry() }
    check("zip-slip rejected", throws { Backup.restore(evil, game2) } && !File(root, "x.txt").exists())

    // Updates + Modrinth
    check("version order", Updates.isNewer("1.0.9", "v1.0.10") && !Updates.isNewer("1.2.0", "1.2") && Updates.isNewer("1.2", "1.2.1"))
    val rel = Updates.latest({ """{"tag_name":"v2.0.0","body":"n","assets":[{"name":"a.zip"},{"name":"f.apk","browser_download_url":"https://x/f.apk"}]}""" }, "o/r")
    check("release parse", rel?.tag == "v2.0.0" && rel.apkUrl == "https://x/f.apk")
    check("release failure is null", Updates.latest({ error("offline") }, "o/r") == null)
    val hits = Modrinth.search({ """{"hits":[{"slug":"s","title":"T","description":"d","downloads":7,"icon_url":null}]}""" }, "x")
    check("modrinth parse", hits.single().let { it.slug == "s" && it.downloads == 7L && it.iconUrl == null })
    check("modrinth url encoded", Modrinth.searchUrl("a b&c", "horizon").contains("query=a+b%26c") && Modrinth.searchUrl("x", limit = 999).contains("limit=50"))
    check("modrinth failure is empty", Modrinth.search({ error("offline") }, "x").isEmpty())

    root.deleteRecursively()
    moreChecks()
    if (failed > 0) { println("$failed failed"); kotlin.system.exitProcess(1) } else println("all passed")
}
