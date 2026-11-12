plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
// Dependency-free checks (src/test/.../CoreChecks.kt): `./gradlew :core:check` runs them.
val checks by tasks.registering(JavaExec::class) {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.ferrite.core.CoreChecksKt")
}
tasks.named("check") { dependsOn(checks) }
