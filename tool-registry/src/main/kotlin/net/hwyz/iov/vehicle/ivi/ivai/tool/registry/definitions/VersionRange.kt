package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions

/**
 * Minimal semantic-version range matcher used by L0 rule applicability and tool
 * availability filtering (IVI-IVAI-DSN-CR-005).
 *
 * Supported spec forms (space / `,` / `&&` / `;` separated):
 *  - ">=0.1.0", "<2.0.0", ">1.0", "<=1.5.0", "==1.2.0", "1.2.0", "*"
 */
object VersionRange {

    fun matches(current: String, spec: String?): Boolean {
        if (spec.isNullOrBlank()) return true
        val normalized = spec.trim().replace("==", "=")
        val parts = normalized.split(Regex("\\s*(?:,|&&|;)\\s*")).filter { it.isNotBlank() }
        return parts.all { part -> matchesPart(current, part) }
    }

    private fun matchesPart(current: String, part: String): Boolean = when {
        part == "*" -> true
        part.startsWith(">=") -> compare(current, part.removePrefix(">=").trim()) >= 0
        part.startsWith("<=") -> compare(current, part.removePrefix("<=").trim()) <= 0
        part.startsWith(">") -> compare(current, part.removePrefix(">").trim()) > 0
        part.startsWith("<") -> compare(current, part.removePrefix("<").trim()) < 0
        part.startsWith("=") -> compare(current, part.removePrefix("=").trim()) == 0
        else -> compare(current, part) == 0
    }

    /** Numeric dot-segment comparison; non-numeric trailing parts are ignored. */
    fun compare(a: String, b: String): Int {
        val sa = a.trim().split(".").mapNotNull { it.toIntOrNull() }
        val sb = b.trim().split(".").mapNotNull { it.toIntOrNull() }
        val max = maxOf(sa.size, sb.size)
        for (i in 0 until max) {
            val va = sa.getOrElse(i) { 0 }
            val vb = sb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
