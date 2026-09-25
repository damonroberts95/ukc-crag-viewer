package dr.ukccrags

import android.content.Context

/**
 * Bouldering grades shown in one system, whichever one each crag's
 * guidebook used.
 *
 * UKC keeps a problem in the grade its first ascensionist or guide gave it:
 * Font at most venues, V at some, and bare UK tech grades on older gritstone
 * and limestone pages. Comparing a "6a" with an "f6B" and a "V4" in the same
 * list is guesswork, so the reader picks one system and every boulder problem
 * is shown in it. The grade as UKC gives it is always kept and shown in the
 * climb dialog, since every conversion here is the usual rough equivalence
 * and not a regrade.
 *
 * Everything goes through one Font ladder: each system maps onto a rung, and
 * the rung maps back out. Anything that does not parse — a range, a typo, a
 * B-grade — is shown exactly as UKC has it.
 */
object BoulderGrades {

    const val FONT = "font"
    const val V = "v"
    const val UK = "uk"

    /** The Font ladder, easiest first. Each rung's index is the common scale. */
    private val FONT_LADDER = listOf(
        "f3", "f3+", "f4", "f4+", "f5", "f5+",
        "f6A", "f6A+", "f6B", "f6B+", "f6C", "f6C+",
        "f7A", "f7A+", "f7B", "f7B+", "f7C", "f7C+",
        "f8A", "f8A+", "f8B", "f8B+", "f8C", "f8C+", "f9A",
    )

    /** The V grade usually given for each Font rung, index for index. */
    private val V_FOR_RUNG = listOf(
        "VB", "VB", "V0-", "V0", "V1", "V2",
        "V3", "V3", "V4", "V4", "V5", "V5",
        "V6", "V7", "V8", "V8", "V9", "V10",
        "V11", "V12", "V13", "V14", "V15", "V16", "V17",
    )

    /** Where each V grade lands on the ladder: its easiest Font equivalent. */
    private val RUNG_FOR_V = mapOf(
        "vb" to 0, "v0-" to 2, "v0" to 3, "v0+" to 4,
        "v1" to 4, "v2" to 5, "v3" to 6, "v4" to 8, "v5" to 10,
        "v6" to 12, "v7" to 13, "v8" to 14, "v9" to 16, "v10" to 17,
        "v11" to 18, "v12" to 19, "v13" to 20, "v14" to 21, "v15" to 22,
        "v16" to 23, "v17" to 24,
    )

    /**
     * UK tech grades for each rung. The tech scale is coarser at the top,
     * so several rungs share a grade; below 4a it has nothing finer.
     */
    private val UK_FOR_RUNG = listOf(
        "4a", "4a", "4b", "4c", "5a", "5b",
        "5c", "6a", "6a", "6a", "6b", "6b",
        "6b", "6c", "6c", "7a", "7a", "7a",
        "7b", "7b", "7b", "7b", "7b", "7b", "7b",
    )

    /** Where each tech grade lands on the ladder. */
    private val RUNG_FOR_UK = mapOf(
        "3c" to 0, "4a" to 1, "4b" to 2, "4c" to 3, "5a" to 4, "5b" to 5,
        "5c" to 6, "6a" to 8, "6b" to 10, "6c" to 13, "7a" to 15, "7b" to 18,
    )

    /** "f6B+", "Font 7A", and "6B+" with no prefix: capital letters are Font's. */
    private val FONT_GRADE = Regex("""^(?:f|font\s*)([3-9])([abc])?(\+)?$""", RegexOption.IGNORE_CASE)
    private val FONT_BARE = Regex("""^([6-9])([ABC])(\+)?$""")
    private val V_GRADE = Regex("""^v(b|\d{1,2})([+-])?$""", RegexOption.IGNORE_CASE)
    private val UK_GRADE = Regex("""^([3-7][abc])$""")

    fun system(context: Context): String = Settings.boulderGrades(context)

    fun isBoulder(type: String): Boolean = type.contains("boulder", ignoreCase = true)

    /** The problem's place on the common ladder, or null when it does not parse. */
    fun rung(grade: String): Int? {
        val raw = grade.trim()

        (FONT_GRADE.matchEntire(raw) ?: FONT_BARE.matchEntire(raw))?.let { m ->
            val number = m.groupValues[1]
            // Below 6 Font has no letters; a stray "f4a" still means f4.
            val letter = if (number.toInt() >= 6) m.groupValues[2].uppercase() else ""
            val plus = m.groupValues[3]
            val name = "f$number$letter$plus"
            FONT_LADDER.indexOf(name).takeIf { it >= 0 }?.let { return it }
            // f6 with no letter, f9A+ and the like: nearest rung below.
            return FONT_LADDER.indexOfLast { it.startsWith("f$number") }.takeIf { it >= 0 }
        }

        V_GRADE.matchEntire(raw)?.let { m ->
            return RUNG_FOR_V["v" + m.groupValues[1].lowercase() + m.groupValues[2]]
                ?: RUNG_FOR_V["v" + m.groupValues[1].lowercase()]
        }

        UK_GRADE.matchEntire(raw)?.let { m -> return RUNG_FOR_UK[m.groupValues[1]] }

        return null
    }

    /** A rung written in [system]. */
    private fun name(rung: Int, system: String): String = when (system) {
        V -> V_FOR_RUNG[rung]
        UK -> UK_FOR_RUNG[rung]
        else -> FONT_LADDER[rung]
    }

    /**
     * What to show for a climb's grade: converted when it is a boulder problem
     * whose grade parses, and exactly UKC's otherwise.
     */
    fun show(grade: String, type: String, system: String): String {
        if (!isBoulder(type)) return grade
        val rung = rung(grade) ?: return grade
        return name(rung, system)
    }

    fun show(context: Context, grade: String, type: String): String =
        show(grade, type, system(context))

    /** True when [show] would change what UKC gives, so the original is worth a mention. */
    fun converted(grade: String, type: String, system: String): Boolean =
        show(grade, type, system) != grade
}
