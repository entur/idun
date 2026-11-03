package org.entur.idun.config

/**
 * Gitignore-lignende glob.
 *
 * - `**` matcher null eller flere hele mappenivåer, `*` og `?` matcher innenfor ett nivå.
 * - Et mønster uten `/` matcher filnavnet på alle nivåer (`*.sql` matcher `a/b/c.sql`).
 * - Et mønster med `/` er forankret i roten av repoet.
 * - Matching er case-sensitiv.
 */
class Glob(val pattern: String) {
    private val regex: Regex = toRegex(pattern)

    fun matches(path: String): Boolean = regex.matches(path)

    override fun equals(other: Any?): Boolean = other is Glob && other.pattern == pattern

    override fun hashCode(): Int = pattern.hashCode()

    override fun toString(): String = "Glob($pattern)"

    private companion object {
        fun toRegex(raw: String): Regex {
            var p = raw
            val anchored = p.contains('/')
            p = p.removePrefix("/")
            if (p.endsWith("/")) p += "**"
            if (!anchored) p = "**/$p"

            val sb = StringBuilder()
            var i = 0
            while (i < p.length) {
                val c = p[i]
                when {
                    c == '*' && p.startsWith("**", i) -> {
                        val atSegmentStart = i == 0 || p[i - 1] == '/'
                        val next = i + 2
                        if (atSegmentStart && next < p.length && p[next] == '/') {
                            sb.append("(?:.*/)?")
                            i = next + 1
                        } else {
                            sb.append(".*")
                            i = next
                        }
                    }
                    c == '*' -> {
                        sb.append("[^/]*")
                        i++
                    }
                    c == '?' -> {
                        sb.append("[^/]")
                        i++
                    }
                    else -> {
                        sb.append(Regex.escape(c.toString()))
                        i++
                    }
                }
            }
            return Regex(sb.toString())
        }
    }
}
