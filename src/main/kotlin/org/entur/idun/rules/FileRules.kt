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

enum class FileClass { PROTECTED, TEST, SOURCE, OTHER }

/**
 * Kontrolltegn og linjeskift (alt under U+0020, U+007F, U+0085, U+2028 og U+2029). En filsti med slike tegn er
 * `OTHER`, og de fjernes fra tittelen før den når modellen, så de ikke kan bryte opp prompten eller rapportene.
 */
val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001f\\u007f\\u0085\\u2028\\u2029]")

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

    // Alle filer under teststier er TEST, også nye: en ny testklasse kan svekke eksisterende tester uten at
    // modellen ser det (for eksempel en `@Configuration` med `@Primary`-bønner eller en klasse som skygger for produksjonskode).
    if (config.isTest(file.path)) return FileClass.TEST
    // Flyttet ut av en teststi: fortsatt en endret test.
    if (file.status == FileStatus.RENAMED && file.previousPath != null && config.isTest(file.previousPath)) {
        return FileClass.TEST
    }

    if (paths.all { config.isSource(it) }) return FileClass.SOURCE

    return FileClass.OTHER
}

/**
 * Én [Reason] per fil som stopper PR-en, pluss `TOO_LARGE` hvis kildekodeendringene er for store, og `NO_SOURCE`
 * når ingen fil stopper PR-en, men det heller ikke er noen kildekode å vurdere.
 */
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
            FileClass.TEST -> reasons += testReason(file)
            FileClass.OTHER -> reasons += Reason(ReasonCode.OTHER_FILE, "Fil utenfor kildekode og tester: ${describe(file)}")
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
    // Ellers ville modellen fått en tom endring (for eksempel bare nye tester) og ingenting å vurdere.
    if (sourceFiles.isEmpty() && reasons.isEmpty()) {
        reasons += Reason(ReasonCode.NO_SOURCE, "Ingen endret kildekode å vurdere")
    }

    return RuleResult(reasons, sourceFiles, changedLines)
}

private fun testReason(file: ChangedFile): Reason =
    if (file.status == FileStatus.ADDED) {
        Reason(ReasonCode.TEST_ADDED, "Ny testfil ${file.path}: en refactor-PR skal ikke endre testene")
    } else {
        Reason(ReasonCode.TEST_CHANGED, "Eksisterende test er endret: ${describe(file)}")
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
