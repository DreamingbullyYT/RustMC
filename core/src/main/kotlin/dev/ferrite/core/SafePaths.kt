package dev.ferrite.core

import java.io.File

/**
 * Maps Files-app document ids to files under one base folder and refuses anything that would leave it
 * (`..`, absolute paths, backslashes, NUL, symlinks that point outside). Ids look like `ferrite/instances-relative/path`.
 */
object SafePaths {
    const val ROOT_ID = "ferrite"

    fun resolve(base: File, docId: String): File {
        val b = base.canonicalFile
        require(docId == ROOT_ID || docId.startsWith("$ROOT_ID/")) { "unknown document id" }
        val rel = docId.removePrefix(ROOT_ID).trimStart('/')
        require('\u0000' !in rel && '\\' !in rel && rel.split('/').none { it == ".." || it == "." }) { "unsafe document id" }
        val f = File(b, rel).canonicalFile
        require(f == b || f.path.startsWith(b.path + File.separator)) { "document is outside the root" }
        return f
    }

    fun idOf(base: File, f: File): String {
        val b = base.canonicalFile; val c = f.canonicalFile
        require(c == b || c.path.startsWith(b.path + File.separator)) { "file is outside the root" }
        return if (c == b) ROOT_ID else ROOT_ID + "/" + c.relativeTo(b).invariantSeparatorsPath
    }

    fun isChild(base: File, parentId: String, childId: String): Boolean = runCatching {
        val p = resolve(base, parentId); val c = resolve(base, childId)
        c != p && c.path.startsWith(p.path + File.separator)
    }.getOrDefault(false)

    /** A new child name must be a single path segment. */
    fun validName(name: String) = name.isNotEmpty() && name.length <= 255 && '/' !in name && '\\' !in name && '\u0000' !in name && name != "." && name != ".."
}
