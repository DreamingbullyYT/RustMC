package dev.ferrite.core

enum class JobState { QUEUED, BUILDING, SUCCEEDED, FAILED, CANCELLED }

/** An HNMod cloud build. The Discord bot and the launcher share these ids and states. */
data class BuildJob(val id: String, val engineVersion: String, val profile: String, val sourceSha256: String, val state: JobState = JobState.QUEUED, val progress: Int = 0, val artifactUrl: String? = null, val error: String? = null) {
    init { require(id.matches(Regex("[a-z0-9-]{6,64}"))) { "bad job id" }; require(progress in 0..100) { "bad progress" } }

    /** The command a runner executes. Everything it needs is in the job: engine version, build profile and the exact file. */
    fun command(): List<String> = listOf("horizon-launcher", "native-build", "--engine", engineVersion, "--profile", profile, "--sha256", sourceSha256, "--job", id)

    private val allowed = mapOf(JobState.QUEUED to setOf(JobState.BUILDING, JobState.CANCELLED), JobState.BUILDING to setOf(JobState.SUCCEEDED, JobState.FAILED, JobState.CANCELLED))

    /** Applies a status message (`{"job":..,"state":..,"progress":..,"artifact_url":..,"error":..}`); stale or illegal updates are ignored. */
    fun apply(message: String): BuildJob {
        val m = runCatching { Json.parse(message).obj() }.getOrNull() ?: return this
        if (m["job"].str() != id) return this
        val next = JobState.entries.firstOrNull { it.name.equals(m["state"].str(), true) } ?: return this
        if (next != state && next !in allowed[state].orEmpty()) return this
        val p = m["progress"].int(progress).coerceIn(0, 100)
        if (next == state && p < progress) return this
        return copy(state = next, progress = if (next == JobState.SUCCEEDED) 100 else p, artifactUrl = m["artifact_url"] as? String ?: artifactUrl, error = m["error"] as? String ?: error)
    }
}
