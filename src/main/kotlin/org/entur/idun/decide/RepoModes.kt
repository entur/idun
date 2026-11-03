package org.entur.idun.decide

import org.entur.idun.github.RepoRef

/**
 * Modus per repo fra Iduns egen konfigurasjon (`IDUN_REPO_MODES`), aldri fra repoet som vurderes.
 * Nøklene er `owner/name` med små bokstaver. Et repo som ikke er listet, er `OFF`.
 * [invalidReason] er satt når verdien var misformet; da er alle repoer `OFF`.
 */
data class RepoModes(
    private val modes: Map<String, Mode>,
    val invalidReason: String? = null,
) {
    fun modeOf(repo: RepoRef): Mode = modes[key(repo.owner, repo.name)] ?: Mode.OFF

    companion object {
        val NONE = RepoModes(emptyMap())
    }
}

private val KNOWN_MODES = setOf("off", "advise", "approve")

private fun key(
    owner: String,
    name: String,
) = "${owner.lowercase()}/${name.lowercase()}"

/**
 * Parser `owner/name=mode,owner/name=mode`. Mellomrom trimmes, reponavn sammenlignes uten hensyn til
 * store og små bokstaver, og modusen må være nøyaktig `off`, `advise` eller `approve`. Tom eller manglende verdi
 * gir `OFF` for alle. Feiler lukket: én misformet oppføring (uten `=` eller `/`, en tom del, et duplikat, en ukjent modus)
 * gjør hele verdien ugyldig, og da er alle repoer `OFF`.
 */
fun parseRepoModes(value: String?): RepoModes {
    if (value.isNullOrBlank()) return RepoModes.NONE
    val modes = mutableMapOf<String, Mode>()
    for ((index, entry) in value.split(',').withIndex()) {
        fun invalid(why: String) = RepoModes(emptyMap(), "oppføring ${index + 1}: $why")

        val parts = entry.split('=').map { it.trim() }
        if (parts.size != 2) return invalid("forventet owner/name=mode")
        val (repo, mode) = parts
        val repoParts = repo.split('/').map { it.trim() }
        if (repoParts.size != 2 || repoParts.any { it.isEmpty() }) return invalid("forventet owner/name")
        if (mode.isEmpty()) return invalid("mangler modus")
        // En skrivefeil som `aprove` skal synes i loggen, ikke stille bli off.
        if (mode !in KNOWN_MODES) return invalid("ukjent modus")
        val key = key(repoParts[0], repoParts[1])
        if (key in modes) return invalid("$key er oppgitt flere ganger")
        modes[key] = parseMode(mode)
    }
    return RepoModes(modes)
}
