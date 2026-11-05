plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "org.entur"
version = file("version.txt").readText().trim()

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass = "org.entur.idun.ApplicationKt"
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.java.jwt)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.kaml)
    implementation(libs.google.genai)
    implementation(libs.java.diff.utils)
    implementation(libs.logback.classic)
    implementation(libs.entur.logback.logstash.encoder.gcp)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Kjører kanarifuglene mot den ekte modellen. Koster penger, så den er bevisst ikke koblet til `test` eller `check`.
tasks.register<JavaExec>("eval") {
    group = "verification"
    description = "Kjører regler og Gemini-review på kanarifuglene i eval/canaries og feiler hvis en forventning brytes."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.entur.idun.eval.EvalMainKt"
    workingDir = projectDir
}

// Tørrkjøring: vurderer én ekte PR lokalt uten å skrive til GitHub. Bruker Vertex AI, så den er ikke koblet til `check`.
tasks.register<JavaExec>("assess") {
    group = "verification"
    description = "Tørrkjører Idun mot en ekte PR: --args=\"owner/repo 123 [--mode approve|advise]\". Skriver ingenting til GitHub."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.entur.idun.cli.AssessMainKt"
    workingDir = projectDir
    standardInput = System.`in`
}
