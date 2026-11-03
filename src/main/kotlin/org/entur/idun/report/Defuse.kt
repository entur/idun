package org.entur.idun.report

private val URL = Regex("(?i)\\b(?:[a-z][a-z0-9+.-]*://|www\\.)\\S+")
private val LINE_BREAKS = Regex("\\s*[\\r\\n]+\\s*")
private val MENTION = Regex("@")
private val ISSUE_REF = Regex("#(?=\\d)")
private const val ZERO_WIDTH_SPACE = "​"

/**
 * Gjør tekst fra PR-en eller modellen ufarlig før den når GitHub: ingen lenker, mentions, issue-referanser,
 * HTML, markdown-lenker eller kodeblokker, og alltid på én linje. Alt som ikke er Iduns egen faste tekst
 * skal gjennom denne. Resultatet er aldri lengre enn [maxLength].
 */
fun defuse(
    text: String,
    maxLength: Int = 300,
): String {
    require(maxLength >= 1) { "maxLength må være minst 1" }
    val cleaned =
        text
            .replace(URL, "(lenke fjernet)")
            .replace('`', '\'')
            .replace(MENTION, "@$ZERO_WIDTH_SPACE")
            .replace(ISSUE_REF, "#$ZERO_WIDTH_SPACE")
            .replace(LINE_BREAKS, " ")
            // Entiteter lages sist, slik at de ikke blir rørt av stegene over. `&` først, så `&#64;` i
            // input ikke kan bli en `@` når GitHub tolker den.
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("[", "&#91;")
            .replace("]", "&#93;")
    return if (cleaned.length <= maxLength) cleaned else cleaned.take(maxLength - 1) + "…"
}
