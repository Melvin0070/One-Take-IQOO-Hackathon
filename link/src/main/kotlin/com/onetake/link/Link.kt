package com.onetake.link

/**
 * Multicam. Tier 2, ranked last on COST, not on value. Read link/AGENTS.md.
 *
 * THE PERMISSION PROBLEM, before any code: this module needs INTERNET, and the
 * single-phone demo build deliberately does not have it. Android requires INTERNET even
 * for local sockets, so "no network permission" is not a claim multicam can make. If
 * multicam ships it ships as a SEPARATE build flavour and the line said on stage changes
 * with it. Do not add INTERNET to the demo build's manifest — ./gradlew guardPermissions
 * will stop you, and it is right to.
 *
 * R10 is the acceptance test and the infosec juror's first question: a fourth phone on
 * the same network cannot start, stop or receive a segment.
 *
 * The pairing method is still undecided (docs/agents/decisions.md, open question 5).
 * Three options are on the table; the QR-derived session key with signed control
 * messages and encrypted uploads is the cheapest defensible answer and the one that most
 * directly satisfies R10. Ten minutes of decision, valuable even if the code never ships
 * — "we would use X" beats "we would need to decide" in front of a juror.
 */
class LinkSession {

    fun hostPairing(): PairingCode = TODO("Lane H: see link/AGENTS.md")

    fun join(code: PairingCode): Unit = TODO("Lane H")

    data class PairingCode(val qrPayload: String)
}
