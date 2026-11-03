package org.entur.idun.rules

data class ConventionalTitle(
    val type: String,
    val scope: String?,
    val breaking: Boolean,
)

private val TITLE_REGEX = Regex("""^([a-z]+)(\(([^)]+)\))?(!)?: \S.*$""")

/** Returnerer null når tittelen ikke er en conventional commit-tittel. Case-sensitiv. */
fun parseTitle(title: String): ConventionalTitle? {
    val m = TITLE_REGEX.matchEntire(title) ?: return null
    return ConventionalTitle(
        type = m.groupValues[1],
        scope = m.groups[3]?.value,
        breaking = m.groups[4] != null,
    )
}

fun ConventionalTitle?.isRefactor(): Boolean = this?.type == "refactor"
