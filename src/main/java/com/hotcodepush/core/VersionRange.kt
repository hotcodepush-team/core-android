package com.hotcodepush.core

/**
 * The range subset the three evaluators share, over dotted numeric versions: comparators `>=`, `>`, `<=`, `<`, `=`,
 * a bare version as equality, `x` or `*` wildcards and partial versions as intervals, alternatives joined by `||`.
 * Anything else does not parse, and a condition that does not parse is not satisfied; a component above `COMPONENT_MAXIMUM`
 * does not parse either.
 */
object VersionRange {
    /**
     * The largest component a version or a range holds: the largest integer the TypeScript evaluator holds exactly,
     * `Number.MAX_SAFE_INTEGER`, so a component never overflows here and compares as it does there.
     */
    const val COMPONENT_MAXIMUM = 9_007_199_254_740_991L

    private data class Comparator(val op: String, val version: List<Long>)

    /** A version's numeric components; `null` when the string is not a dotted number of components up to `COMPONENT_MAXIMUM`. */
    fun parseVersion(value: String): List<Long>? {
        val components = value.trim().split('.')
        if (components.any { component -> component.isEmpty() || !component.all { it in '0'..'9' } }) return null
        return parseComponents(components)
    }

    /** The components as numbers, `null` when one is above `COMPONENT_MAXIMUM`. */
    private fun parseComponents(components: List<String>): List<Long>? = components.map { component ->
        component.toLongOrNull()?.takeIf { it <= COMPONENT_MAXIMUM } ?: return null
    }

    /**
     * Whether the version satisfies the range: `null` when the range does not parse.
     * A comparator compares only as many components as it names, so `2.4.1` matches `2.4.1.57`.
     */
    fun isVersionInRange(version: List<Long>, range: String): Boolean? {
        val alternatives = range.split("||").map { parseAlternative(it) ?: return null }
        return alternatives.any { comparators -> comparators.all { isSatisfied(version, it) } }
    }

    private fun compare(left: List<Long>, right: List<Long>, length: Int): Int {
        for (index in 0 until length) {
            val order = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
            if (order != 0) return order
        }
        return 0
    }

    private fun isSatisfied(version: List<Long>, comparator: Comparator): Boolean {
        val order = compare(version, comparator.version, comparator.version.size)
        return when (comparator.op) {
            "<" -> order < 0
            "<=" -> order <= 0
            "=" -> order == 0
            ">" -> order > 0
            else -> order >= 0
        }
    }

    /**
     * One comparator: an optional operator, optional whitespace, a version that may end in wildcards or be
     * wildcards alone, then whitespace or the end; an alternative parses only when comparators cover it entirely.
     */
    private val comparatorPattern = Regex("""(>=|<=|>|<|=)?\s*(\d+(?:\.\d+)*(?:\.[xX*])*|[xX*](?:\.[xX*])*)(?:\s+|$)""")

    private fun parseAlternative(alternative: String): List<Comparator>? {
        val trimmed = alternative.trim()
        if (trimmed.isEmpty()) return null
        val comparators = mutableListOf<Comparator>()
        var position = 0
        while (position < trimmed.length) {
            val match = comparatorPattern.matchAt(trimmed, position) ?: return null
            if (match.value.isEmpty()) return null
            comparators += parseComparator(match.groups[1]?.value, match.groupValues[2]) ?: return null
            position = match.range.last + 1
        }
        return comparators
    }

    private fun parseComparator(op: String?, version: String): List<Comparator>? {
        val components = version.split('.')
        val wildcardIndex = components.indexOfFirst { it == "x" || it == "X" || it == "*" }
        if (wildcardIndex != -1 && op != null) return null
        if (wildcardIndex == -1 && (op != null || components.size >= 3)) return parseComponents(components)?.let { listOf(Comparator(op ?: "=", it)) }
        return parseComponents(components.take(if (wildcardIndex == -1) components.size else wildcardIndex))?.let(::intervalComparators)
    }

    /** A partial or wildcard version as the interval it names: `1.2` and `1.2.x` are `>=1.2 <1.3`, `x` is everything. */
    private fun intervalComparators(fixed: List<Long>): List<Comparator> {
        if (fixed.isEmpty()) return emptyList()
        val upper = fixed.toMutableList().also { it[it.size - 1] += 1 }
        return listOf(Comparator(">=", fixed), Comparator("<", upper))
    }
}
