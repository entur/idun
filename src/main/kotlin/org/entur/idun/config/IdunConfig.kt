package org.entur.idun.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable

/**
 * Konfigurasjon kan bare gjøre reglene strengere:
 * - `protectedPaths` og `protectedContent` legges til standardene.
 * - `sourcePaths` og `testPaths` er standardlistene. YAML-verdier lagres som innsnevring
 *   (`sourceNarrowing`/`testNarrowing`), og en fil teller bare som kilde/test når den
 *   matcher både standardlisten og innsnevringen (hvis satt).
 * - `maxChangedLines` er min(50, verdi).
 */
data class IdunConfig(
    val protectedPaths: List<Glob>,
    val protectedContent: List<Regex>,
    val sourcePaths: List<Glob>,
    val testPaths: List<Glob>,
    val maxChangedLines: Int,
    val sourceNarrowing: List<Glob>? = null,
    val testNarrowing: List<Glob>? = null,
) {
    fun isSource(path: String): Boolean = matchesAll(path, sourcePaths, sourceNarrowing)

    fun isTest(path: String): Boolean = matchesAll(path, testPaths, testNarrowing)

    private fun matchesAll(
        path: String,
        defaults: List<Glob>,
        narrowing: List<Glob>?,
    ): Boolean = defaults.any { it.matches(path) } && (narrowing == null || narrowing.any { it.matches(path) })

    companion object {
        const val MAX_CHANGED_LINES = 50

        val DEFAULT =
            IdunConfig(
                protectedPaths =
                    listOf(
                        "specs/**",
                        "**/openapi/**",
                        "*.openapi.*",
                        "**/db/migration/**",
                        "*.sql",
                        "application*.yml",
                        "application*.yaml",
                        "*.properties",
                        "helm/**",
                        ".entur/**",
                        "*.gradle.kts",
                        "*.gradle",
                        "gradle/**",
                        "gradlew*",
                        "settings.gradle*",
                        "Dockerfile",
                        "compose.yaml",
                        ".github/**",
                    ).map(::Glob),
                protectedContent =
                    listOf(
                        Regex(""":\s*(\w+\.)*(Table|IdTable|LongIdTable|IntIdTable|UUIDTable|UIntIdTable|ULongIdTable|CompositeIdTable)\s*[(<]"""),
                        Regex("""@(jakarta\.persistence\.|javax\.persistence\.)?(Entity|Table)\b"""),
                    ),
                sourcePaths = listOf("**/src/main/**/*.kt", "**/src/main/**/*.java").map(::Glob),
                testPaths = listOf("**/src/test/**", "**/src/testFixtures/**", "**/src/integrationTest/**").map(::Glob),
                maxChangedLines = MAX_CHANGED_LINES,
            )
    }
}

sealed interface ConfigResult {
    data class Ok(
        val config: IdunConfig,
    ) : ConfigResult

    data class Invalid(
        val reason: String,
    ) : ConfigResult
}

@Serializable
private data class IdunConfigYaml(
    val protectedPaths: List<String>? = null,
    val protectedContent: List<String>? = null,
    val sourcePaths: List<String>? = null,
    val testPaths: List<String>? = null,
    val maxChangedLines: Int? = null,
)

/** Parser `.github/idun.yaml`. `null` (eller blank YAML) gir `Ok(DEFAULT)`. Ukjente nøkler, ugyldig YAML og ugyldig regex gir `Invalid`. */
fun IdunConfig.Companion.fromYaml(yaml: String?): ConfigResult {
    if (yaml.isNullOrBlank()) return ConfigResult.Ok(IdunConfig.DEFAULT)

    val parsed =
        try {
            Yaml.default.decodeFromString(IdunConfigYaml.serializer(), yaml)
        } catch (e: Exception) {
            return ConfigResult.Invalid("Ugyldig .github/idun.yaml: ${e.message?.lineSequence()?.firstOrNull() ?: e::class.simpleName}")
        }

    val maxChangedLines = parsed.maxChangedLines
    if (maxChangedLines != null && maxChangedLines < 0) {
        return ConfigResult.Invalid("maxChangedLines kan ikke være negativ (var $maxChangedLines)")
    }

    val extraContent =
        parsed.protectedContent.orEmpty().map {
            try {
                Regex(it)
            } catch (e: IllegalArgumentException) {
                return ConfigResult.Invalid("Ugyldig regex i protectedContent: '$it'")
            }
        }

    val d = IdunConfig.DEFAULT
    return ConfigResult.Ok(
        d.copy(
            protectedPaths = d.protectedPaths + parsed.protectedPaths.orEmpty().map(::Glob),
            protectedContent = d.protectedContent + extraContent,
            maxChangedLines = minOf(IdunConfig.MAX_CHANGED_LINES, maxChangedLines ?: IdunConfig.MAX_CHANGED_LINES),
            sourceNarrowing = parsed.sourcePaths?.map(::Glob),
            testNarrowing = parsed.testPaths?.map(::Glob),
        ),
    )
}
