package org.entur.idun.rules

import org.entur.idun.config.IdunConfig

/** Filstatus slik GitHub rapporterer den. */
enum class FileStatus { ADDED, MODIFIED, REMOVED, RENAMED, COPIED, CHANGED, UNCHANGED }

data class ChangedFile(
    val path: String,
    val previousPath: String?,
    val status: FileStatus,
    val additions: Int,
    val deletions: Int,
    val patch: String?,
)

enum class FileClass { PROTECTED, EXISTING_TEST, NEW_TEST, SOURCE, OTHER }

/**
 * Kontrolltegn og linjeskift (alt under U+0020, U+007F, U+0085, U+2028 og U+2029). En filsti med slike tegn er
 * `OTHER`, og de fjernes fra tittelen før den når modellen, så de ikke kan bryte opp prompten eller rapportene.
 */
val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001f\\u007f\\u0085\\u2028\\u2029]")

/** Filendelsene en ny fil under en teststi må ha for å telle som ny test. */
private val TEST_CODE_SUFFIXES = listOf(".kt", ".java")

/** Filinnhold før og etter endringen. `null` betyr at filen ikke fantes på den siden. */
data class FileContents(
    val before: String?,
    val after: String?,
)

data class RuleResult(
    val reasons: List<Reason>,
    val sourceFiles: List<ChangedFile>,
    val changedLines: Int,
) {
    val eligible: Boolean get() = reasons.isEmpty()
}

/**
 * Klassifiserer en endret fil. Rekkefølgen er viktig og feiler lukket:
 * en sti (ny eller gammel) med [CONTROL_CHARACTERS] er `OTHER`, og beskyttet sti (ny eller gammel) vinner over resten. En sti (ny eller gammel) som matcher både et test- og et
 * kildemønster (`src/main/kotlin/x/src/test/A.kt`) er `OTHER`, ellers kunne kildekode sniket seg inn som en
 * ny test som verken telles eller vises modellen. Deretter test, deretter kilde. Alt annet er `OTHER`.
 */
fun classify(
    file: ChangedFile,
    config: IdunConfig,
): FileClass {
    val paths = listOfNotNull(file.path, file.previousPath)

    if (paths.any { CONTROL_CHARACTERS.containsMatchIn(it) }) return FileClass.OTHER

    if (paths.any { path -> config.protectedPaths.any { it.matches(path) } }) return FileClass.PROTECTED

    if (paths.any { config.looksLikeTestAndSource(it) }) return FileClass.OTHER

    if (config.isTest(file.path)) {
        return when {
            file.status != FileStatus.ADDED -> FileClass.EXISTING_TEST
            // Bare kode er en ny test. Andre nye filer under teststier (ressurser, `META-INF/services`) kan endre
            // hvilke tester som kjører, og må ses av et menneske.
            TEST_CODE_SUFFIXES.any { file.path.endsWith(it) } -> FileClass.NEW_TEST
            else -> FileClass.OTHER
        }
    }
    // Flyttet ut av en teststi: aldri en ny test.
    if (file.status == FileStatus.RENAMED && file.previousPath != null && config.isTest(file.previousPath)) {
        return FileClass.EXISTING_TEST
    }

    if (paths.all { config.isSource(it) }) return FileClass.SOURCE

    return FileClass.OTHER
}

/** Én [Reason] per fil som stopper PR-en, pluss `TOO_LARGE` hvis kildekodeendringene er for store. */
fun evaluateFiles(
    files: List<ChangedFile>,
    contents: Map<String, FileContents>,
    config: IdunConfig,
): RuleResult {
    val reasons = mutableListOf<Reason>()
    val sourceFiles = mutableListOf<ChangedFile>()

    for (file in files) {
        when (classify(file, config)) {
            FileClass.PROTECTED -> reasons += Reason(ReasonCode.PROTECTED_PATH, "Beskyttet sti: ${describe(file)}")
            FileClass.EXISTING_TEST -> reasons += Reason(ReasonCode.TEST_CHANGED, "Eksisterende test er endret: ${describe(file)}")
            FileClass.OTHER -> reasons += Reason(ReasonCode.OTHER_FILE, "Fil utenfor kildekode og tester: ${describe(file)}")
            FileClass.NEW_TEST -> Unit
            FileClass.SOURCE -> {
                sourceFiles += file
                sourceReason(file, contents[file.path], config)?.let { reasons += it }
            }
        }
    }

    val changedLines = sourceFiles.sumOf { it.additions + it.deletions }
    if (changedLines > config.maxChangedLines) {
        reasons += Reason(ReasonCode.TOO_LARGE, "$changedLines av høyst ${config.maxChangedLines} linjer")
    }

    return RuleResult(reasons, sourceFiles, changedLines)
}

private fun sourceReason(
    file: ChangedFile,
    contents: FileContents?,
    config: IdunConfig,
): Reason? {
    // Mangler diff eller innhold, kan vi ikke vurdere filen: send til menneske.
    if (file.patch == null || contents == null) {
        return Reason(ReasonCode.MISSING_DIFF, "Mangler diff for ${file.path}")
    }
    val hit =
        config.protectedContent.firstOrNull { regex ->
            listOfNotNull(contents.before, contents.after).any { regex.containsMatchIn(it) }
        }
    return hit?.let { Reason(ReasonCode.PROTECTED_CONTENT, "${file.path} inneholder beskyttet innhold (${it.pattern})") }
}

private fun describe(file: ChangedFile): String = file.previousPath?.takeIf { it != file.path }?.let { "$it -> ${file.path}" } ?: file.path
