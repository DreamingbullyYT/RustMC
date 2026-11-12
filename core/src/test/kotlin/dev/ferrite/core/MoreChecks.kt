package dev.ferrite.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun levelDat(name: String, version: String, lastPlayed: Long): ByteArray {
    val b = ByteArrayOutputStream()
    DataOutputStream(GZIPOutputStream(b)).use { o ->
        o.writeByte(10); o.writeUTF("")
        o.writeByte(10); o.writeUTF("Data")
        o.writeByte(8); o.writeUTF("LevelName"); o.writeUTF(name)
        o.writeByte(4); o.writeUTF("LastPlayed"); o.writeLong(lastPlayed)
        o.writeByte(10); o.writeUTF("Version"); o.writeByte(8); o.writeUTF("Name"); o.writeUTF(version); o.writeByte(0)
        o.writeByte(0); o.writeByte(0)
    }
    return b.toByteArray()
}

private fun hnmod(f: File, id: String, game: String, depends: List<String> = emptyList(), conflicts: List<String> = emptyList()) {
    ZipOutputStream(f.outputStream()).use { z -> z.putNextEntry(ZipEntry("horizon-native.json")); z.write(Json.write(linkedMapOf("schema" to 1, "id" to id, "game" to game, "depends" to depends, "conflicts" to conflicts)).toByteArray()); z.closeEntry() }
}

fun moreChecks() {
    val root = Files.createTempDirectory("ferrite2").toFile()

    // Worlds
    val game = File(root, "game"); val w = File(game, "saves/Alpha").apply { mkdirs() }
    File(w, "level.dat").writeBytes(levelDat("Alpha World", "26.3", 1234)); File(w, "region").mkdirs(); File(w, "region/r.0.0.mca").writeText("data")
    val wm = WorldManager(game)
    val info = wm.list().single()
    check("world info", info.folder == "Alpha" && info.displayName == "Alpha World" && info.version == "26.3" && info.lastPlayedMs == 1234L && info.sizeBytes > 4)
    val bk = File(root, "alpha.zip"); check("world backup", wm.backup("Alpha", bk) == 2)
    check("restore refuses existing world", throws { wm.restore(bk, "26.3") })
    check("restore under new name", wm.restore(bk, "26.3", asName = "Alpha-copy") == "Alpha-copy" && wm.list().size == 2)
    check("restore refuses other version", runCatching { wm.restore(bk, "26.4", asName = "Alpha-new") }.exceptionOrNull() is IncompatibleBackup)
    check("restore accepts mismatch when told", wm.restore(bk, "26.4", asName = "Alpha-new", acceptVersionMismatch = true) == "Alpha-new")
    wm.rename("Alpha-new", "Beta"); check("rename", wm.list().any { it.folder == "Beta" } && throws { wm.rename("Beta", "Alpha") })
    check("rename rejects path tricks", throws { wm.rename("Beta", "../x") })
    wm.delete("Beta"); check("delete world", wm.list().none { it.folder == "Beta" })
    val outside = File(root, "evilworld.zip")
    ZipOutputStream(outside.outputStream()).use { z -> z.putNextEntry(ZipEntry("ferrite-backup.json")); z.write("""{"format":1,"kind":"world","world":"E","game_version":"26.3"}""".toByteArray()); z.closeEntry(); z.putNextEntry(ZipEntry("world/../../pwn")); z.write(1); z.closeEntry() }
    check("world restore blocks zip-slip", throws { wm.restore(outside, "26.3") })
    val src = File(root, "src-world").apply { mkdirs(); File(this, "level.dat").writeBytes(levelDat("Imp", "26.3", 1)) }
    check("import folder", wm.import(src, "Imported") == "Imported" && throws { wm.import(src, "Imported") })
    val zw = File(root, "w.zip"); ZipOutputStream(zw.outputStream()).use { z -> z.putNextEntry(ZipEntry("MyWorld/level.dat")); z.write(levelDat("Z", "26.3", 2)); z.closeEntry() }
    check("import zip with root folder", wm.import(zw, "FromZip") == "FromZip" && File(game, "saves/FromZip/level.dat").isFile)

    // Full backup
    File(game, "mods").mkdirs(); File(game, "mods/a.hnmod").writeText("m")
    val full = File(root, "full.zip"); check("full backup", FullBackup.create(game, full, "26.3") >= 4)
    val g2 = File(root, "g2"); check("full restore", FullBackup.restore(full, g2, "26.3") >= 4 && File(g2, "mods/a.hnmod").isFile)
    check("full restore refuses non-empty", throws { FullBackup.restore(full, g2, "26.3") })
    check("full restore refuses other version", runCatching { FullBackup.restore(full, File(root, "g3"), "1.0") }.exceptionOrNull() is IncompatibleBackup)

    // Instances: clone, export, import
    val store = InstanceStore(File(root, "app")); val base = store.create("Base", Instance("x", "Base", engineVersion = "26.2"))
    File(store.gameDir(base), "saves/S").mkdirs(); File(store.gameDir(base), "saves/S/level.dat").writeText("1")
    val c = store.clone(base, "Base copy"); check("clone keeps worlds and pinned engine", File(store.gameDir(c), "saves/S/level.dat").isFile && c.engineVersion == "26.2")
    val c2 = store.clone(base, "No worlds", withWorlds = false); check("clone without worlds", !File(store.gameDir(c2), "saves").exists())
    val ex = File(root, "inst.zip"); store.export(base, ex); val imp = store.import(ex)
    check("export/import instance", imp.id != base.id && imp.engineVersion == "26.2" && File(store.gameDir(imp), "saves/S/level.dat").isFile)

    // Mods
    val mg = File(root, "mg"); File(mg, "mods").mkdirs()
    val a = File(root, "a.hnmod").also { hnmod(it, "a", "26.3", depends = listOf("b")) }
    val bm = File(root, "b.hnmod").also { hnmod(it, "b", "26.2", conflicts = listOf("c")) }
    val cm = File(root, "c.hnmod").also { hnmod(it, "c", "26.3") }
    val mm = ModManager(mg); val ea = mm.install(a); mm.install(bm); mm.install(cm)
    check("install reads manifest", ea.id == "a" && ea.game == "26.3" && throws { mm.install(a) })
    val probs = mm.problems("26.3").map { it.modId to it.message }
    check("compat + conflict detected", probs.any { it.first == "b" && "26.2" in it.second } && probs.any { it.first == "b" && "conflicts with c" in it.second })
    val bd = mm.setEnabled(mm.list().first { it.id == "b" }, false)
    check("disable keeps file", !bd.enabled && bd.file.name == "b.hnmod.disabled" && bd.file.isFile)
    check("disabled mod missing dependency", mm.problems("26.3").any { it.modId == "a" && "needs b" in it.message })
    check("re-enable", mm.setEnabled(bd, true).file.name == "b.hnmod")
    mm.uninstall(mm.list().first { it.id == "c" }); check("uninstall", mm.list().none { it.id == "c" })
    File(root, "x.hshader").writeText("s"); check("shader goes to shaderpacks", mm.install(File(root, "x.hshader")).file.parentFile.name == "shaderpacks")
    check("unknown type rejected", throws { mm.install(File(root, "w.zip")) })

    // Servers
    val sl = ServerList(File(root, "servers.json"))
    sl.add(Server("A", "a.example")); sl.add(Server("B", "b.example", 25566))
    check("duplicate server name", throws { sl.add(Server("A", "z")) } && throws { Server("bad", "") })
    sl.markJoined("B", 10); sl.markJoined("A", 20); sl.edit("A", Server("A2", "a2.example"))
    check("history by recency, edit keeps it", sl.history().map { it.name } == listOf("A2", "B") && sl.all().first { it.name == "A2" }.host == "a2.example")
    sl.remove("B"); check("remove server", sl.all().size == 1)
    val ss = ServerSocket(0); val port = ss.localPort
    val t = Thread { ss.accept().use { s ->
        val i = java.io.DataInputStream(s.getInputStream()); val o = DataOutputStream(s.getOutputStream())
        fun rv(): Int { var r = 0; var sh = 0; while (true) { val b = i.readUnsignedByte(); r = r or (b and 0x7F shl sh); if (b and 0x80 == 0) return r; sh += 7 } }
        i.readFully(ByteArray(rv())); i.readFully(ByteArray(rv()))
        val body = Json.write(linkedMapOf("version" to linkedMapOf("name" to "26.3"), "players" to linkedMapOf("online" to 3, "max" to 20), "description" to linkedMapOf("text" to "hi"))).toByteArray()
        val pkt = ByteArrayOutputStream().also { val d = DataOutputStream(it); d.writeByte(0); d.writeByte(body.size and 0x7F or 0x80); d.writeByte(body.size ushr 7); d.write(body) }.toByteArray()
        o.writeByte(pkt.size and 0x7F or 0x80); o.writeByte(pkt.size ushr 7); o.write(pkt)
        rv(); i.readFully(ByteArray(8)); o.writeByte(9); o.writeByte(1); o.writeLong(1); o.flush()
    } }.apply { start() }
    val st = Ping.query("127.0.0.1", port); t.join()
    check("ping parses status", st.motd == "hi" && st.online == 3 && st.max == 20 && st.version == "26.3" && st.pingMs >= 0)
    ss.close()
    check("ping unreachable fails", throws { Ping.query("127.0.0.1", 1, 300) })

    // Accounts
    val acc = AccountStore(File(root, "acc.json")); val steve = acc.addOffline("Steve")
    check("offline uuid is the vanilla name-based uuid", steve.uuid == java.util.UUID.nameUUIDFromBytes("OfflinePlayer:Steve".toByteArray()).toString() && steve.uuid.length == 36)
    check("account rules", throws { acc.addOffline("steve") } && throws { acc.addOffline("a") } && throws { acc.addOffline("bad name") } && acc.all().size == 1)

    // Graphics
    val caps = DeviceCaps(Graphics.vulkanVersion(1, 0), 0x30001)
    val all = setOf(GraphicsApi.VULKAN_1_1, GraphicsApi.VULKAN_1_0, GraphicsApi.GLES_3_0)
    check("vulkan 1.1 refused on 1.0 device", Graphics.options(caps, all).first { it.api == GraphicsApi.VULKAN_1_1 }.let { !it.usable && "< 1.1" in it.reason })
    check("pick falls back in order", Graphics.pick(caps, all) == GraphicsApi.VULKAN_1_0 && Graphics.pick(caps.copy(driverBlocklist = setOf(GraphicsApi.VULKAN_1_0)), all) == GraphicsApi.GLES_3_0)
    check("gles 2.0 not offered when engine lacks it", !Graphics.options(caps, all).first { it.api == GraphicsApi.GLES_2_0 }.usable && Graphics.pick(DeviceCaps(0, 0x20000), all) == null)

    // Diagnostics
    val cd = File(root, "crash").apply { mkdirs() }; File(cd, "c1.txt").writeText("thread panicked\nmemory allocation of 8 bytes failed")
    check("crash history diagnosed", Diagnostics.crashes(cd).single().diagnosis.cause == Cause.OUT_OF_MEMORY)
    val red = Diagnostics.redact("access_token=abcdef123456789 Bearer eyJhbGciOiJI.eyJzdWIiOiIx.SflKxwRJSMeKKF2QT4 ok")
    check("redaction", "abcdef123456789" !in red && "eyJhbGci" !in red && "ok" in red)
    val sup = File(root, "support.zip"); Diagnostics.exportForSupport("session=ZZZZZZZZZZZZ boom", mapOf("android" to "15"), sup)
    check("support export has no secret", java.util.zip.ZipFile(sup).use { z -> z.entries().asSequence().all { e -> "ZZZZZZZZZZZZ" !in z.getInputStream(e).readBytes().decodeToString() } })
    val files = File(root, "files").apply { mkdirs(); File(this, "ok.bin").writeText("good"); File(this, "bad.bin").writeText("bad") }
    val good = Diagnostics.sha256(File(files, "ok.bin"))
    val integ = Diagnostics.verify(files, mapOf("ok.bin" to good, "bad.bin" to good, "gone.bin" to good))
    check("integrity", integ.corrupt == listOf("bad.bin") && integ.missing == listOf("gone.bin") && !integ.ok)

    // Jobs
    var job = BuildJob("job-0001", "26.3", "dev", "a".repeat(64))
    check("job command carries engine, profile, hash", job.command().containsAll(listOf("26.3", "dev", "a".repeat(64), "job-0001")))
    job = job.apply("""{"job":"job-0001","state":"building","progress":40}""")
    check("job progress", job.state == JobState.BUILDING && job.progress == 40)
    check("stale progress ignored", job.apply("""{"job":"job-0001","state":"building","progress":10}""").progress == 40)
    check("other job ignored", job.apply("""{"job":"job-9999","state":"failed"}""") == job)
    check("illegal transition ignored", job.apply("""{"job":"job-0001","state":"queued"}""").state == JobState.BUILDING && job.apply("garbage") == job)
    job = job.apply("""{"job":"job-0001","state":"succeeded","artifact_url":"https://x/a.hnmod"}""")
    check("job done", job.state == JobState.SUCCEEDED && job.progress == 100 && job.artifactUrl != null)
    check("finished job is final", job.apply("""{"job":"job-0001","state":"building"}""").state == JobState.SUCCEEDED)

    // Components
    val payload = File(root, "engine.so").apply { writeText("engine-bytes") }; val hash = Diagnostics.sha256(payload)
    val man = Components.parse("""{"components":[{"id":"engine","version":"26.3.2","url":"https://x/e","sha256":"$hash"},{"id":"launcher","version":"1.0.0","url":"https://x/l","sha256":"$hash"}]}""")
    check("update plan", Components.plan(mapOf("engine" to "26.3.1", "launcher" to "1.0.0"), man).map { it.id } == listOf("engine") && Components.plan(emptyMap(), man).size == 2)
    val inst = File(root, "components"); val placed = Components.install(man[0], payload, inst)
    check("install verified component", placed.readText() == "engine-bytes")
    check("hash mismatch leaves nothing", throws { Components.install(man[0].copy(sha256 = "0".repeat(64)), payload, File(root, "c2")) } && !File(root, "c2/engine-26.3.2").exists())
    check("non-https url rejected", throws { Components.parse("""{"components":[{"id":"e","version":"1","url":"http://x","sha256":"${"a".repeat(64)}"}]}""") })

    // Touch settings (the engine's horizon-touch.json)
    val tf = File(root, "touch/horizon-touch.json"); val ts = TouchSettings(tf)
    check("defaults match the engine", ts.sensitivity == 1.0 && ts.virtualMouse && ts.vibrate && ts.gameAnimations && ts.resolutionPercent == 100 && ts.scheme == Scheme.JOYSTICK_CROSSHAIR && ts.controlStyle == "Mcpe")
    check("default placements match the engine", ts.placement(TouchMode.CROSSHAIR, TouchControl.JUMP) == Placement(0.92, 0.78, 0.16) && ts.placement(TouchMode.DPAD, TouchControl.SNEAK) == Placement(0.13, 0.76, 0.10) && ts.placement(TouchMode.TAP, TouchControl.SNEAK) == Placement(0.81, 0.86, 0.115))
    tf.parentFile.mkdirs(); tf.writeText("""{"custom_buttons":[{"k":1}],"future_option":"keep","sensitivity":2.5,"mode":"dpad","split_controls":true}""")
    val ts2 = TouchSettings(tf); check("reads existing file", ts2.sensitivity == 2.5 && ts2.scheme == Scheme.DPAD_CROSSHAIR)
    ts2.sensitivity = 99.0; ts2.resolutionPercent = 10; ts2.virtualMouseSpeed = 0.0; ts2.gameAnimations = false; ts2.leftHanded = true; ts2.buttonScale = 5.0
    check("setters clamp to engine ranges", ts2.sensitivity == 3.0 && ts2.resolutionPercent == 25 && ts2.virtualMouseSpeed == 0.25 && ts2.buttonScale == 2.0 && !ts2.gameAnimations)
    ts2.save(); val saved = Json.parse(tf.readText()).obj()
    check("unknown keys survive a save", saved["future_option"] == "keep" && saved["custom_buttons"].arr().size == 1)
    check("uses the engine's key names", saved["sensitivity"] == 3L && saved["resolution_scale_percent"] == 25L && saved["disable_game_animations"] == true && saved["left_handed"] == true && saved["mode"] == "dpad")
    ts2.scheme = Scheme.JOYSTICK_TAP; ts2.save()
    check("split cleared outside dpad", TouchSettings(tf).scheme == Scheme.JOYSTICK_TAP && Json.parse(tf.readText()).obj()["split_controls"] == false)
    ts2.setPlacement(TouchMode.TAP, TouchControl.ATTACK, Placement(2.0, -1.0, 9.0, 3.0)); ts2.save()
    val lay = Json.parse(tf.readText()).obj()["layouts"].arr()
    check("layouts always 3 x 14", lay.size == 3 && lay.all { it.arr().size == 14 })
    val att = TouchSettings(tf).placement(TouchMode.TAP, TouchControl.ATTACK)
    check("placement clamped on save", att == Placement(0.975, 0.025, 0.45, 1.0) && TouchSettings(tf).placement(TouchMode.CROSSHAIR, TouchControl.ATTACK) == Placement(0.80, 0.64, 0.16))
    ts2.resetPlacements(TouchMode.TAP); check("reset one mode", ts2.placement(TouchMode.TAP, TouchControl.ATTACK) == Placement(0.80, 0.64, 0.16))
    ts2.setVisibility(TouchControl.CHAT, HIDDEN); check("visibility per control", ts2.visibility(TouchControl.CHAT) == HIDDEN && ts2.visibility(TouchControl.PAUSE) == FOLLOW_SCHEME && throws { ts2.setVisibility(TouchControl.CHAT, 7) })
    check("bad style rejected", throws { ts2.controlStyle = "Nope" })
    tf.writeText("not json"); check("corrupt file reads as defaults", TouchSettings(tf).sensitivity == 1.0)

    // Safe document paths (Files app access)
    val sb = File(root, "docs/instances").apply { mkdirs() }; File(sb, "a/game").mkdirs(); File(sb, "a/game/f.txt").writeText("x")
    check("root and child resolve", SafePaths.resolve(sb, "ferrite") == sb.canonicalFile && SafePaths.resolve(sb, "ferrite/a/game/f.txt").readText() == "x")
    check("id round trip", SafePaths.idOf(sb, File(sb, "a/game/f.txt")) == "ferrite/a/game/f.txt" && SafePaths.idOf(sb, sb) == "ferrite")
    check("traversal refused", listOf("ferrite/../x", "ferrite/a/../../x", "../etc", "/etc/passwd", "ferrite/a\\b", "ferrite/a/\u0000", "other/a", "ferrite/./a").all { throws { SafePaths.resolve(sb, it) } })
    val outsideDir = File(root, "docs/secret").apply { mkdirs(); File(this, "s.txt").writeText("s") }
    val link = File(sb, "a/link"); val linked = runCatching { Files.createSymbolicLink(link.toPath(), outsideDir.toPath()); true }.getOrDefault(false)
    check("symlink escape refused", !linked || throws { SafePaths.resolve(sb, "ferrite/a/link/s.txt") })
    check("isChild", SafePaths.isChild(sb, "ferrite/a", "ferrite/a/game/f.txt") && !SafePaths.isChild(sb, "ferrite/a", "ferrite/a") && !SafePaths.isChild(sb, "ferrite/a", "ferrite/../x"))
    check("valid names", SafePaths.validName("ok.txt") && listOf("", "..", "a/b", "a\\b").none { SafePaths.validName(it) })

    root.deleteRecursively()
}
