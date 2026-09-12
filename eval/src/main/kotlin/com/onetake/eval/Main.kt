package com.onetake.eval

/**
 * The eval card, in one command:
 *
 *     ./gradlew :eval:run --args="--corpus corpus/ --out eval-card.md"
 *
 * §6.2's target is "clone the repo and produce the eval card from the corpus, one
 * documented command, under ten minutes". Keep it that way — a card that takes a
 * README paragraph to reproduce is a card a CTO discounts.
 *
 * What the card must carry, and why each line is there, is in eval/AGENTS.md.
 * The short version: held-out stranger reads only, N and the 95% upper bound, must-say
 * as its own slice, recall beside precision, R22's three latencies separately, the naive
 * baseline, and the build id + config version + flag-vector checksum it describes.
 */
fun main(args: Array<String>) {
    val opts = Options.parse(args)
    if (opts == null) {
        println(USAGE)
        return
    }
    // TODO(Lane B): load the corpus through :engine-fixtures, run each session through
    //   CoverageEngine, compare verdicts against labels, and render the card.
    //   docs/agents/lanes.md#lane-b has the ranked order.
    TODO("Lane B: corpus -> eval card. See eval/AGENTS.md")
}

data class Options(
    val corpus: String,
    val out: String,
    /**
     * Held-out data only, by default. Pass --include-tuning to see how the numbers look
     * on the tuning set — useful while iterating, and NEVER what goes on the card.
     */
    val includeTuning: Boolean = false,
) {
    companion object {
        fun parse(args: Array<String>): Options? {
            val m = mutableMapOf<String, String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                if (!a.startsWith("--")) return null
                val key = a.removePrefix("--")
                if (key == "include-tuning") { m[key] = "true"; i += 1; continue }
                if (i + 1 >= args.size) return null
                m[key] = args[i + 1]; i += 2
            }
            val corpus = m["corpus"] ?: return null
            return Options(corpus, m["out"] ?: "eval-card.md", m["include-tuning"] == "true")
        }
    }
}

private val USAGE = """
    one-take eval — corpus to eval card

      --corpus <dir>      recorded sessions with labels (required)
      --out <file>        output path (default: eval-card.md)
      --include-tuning    also report on the tuning set. Never use this for the card.

    Example:
      ./gradlew :eval:run --args="--corpus corpus/ --out eval-card.md"
""".trimIndent()
