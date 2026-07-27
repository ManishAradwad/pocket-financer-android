package com.pocketfinancer.setup

/**
 * Pure policy for progressively widening historical discovery.
 *
 * A coordinator supplies provider/filter evidence for each requested window.
 * The policy never inspects an in-memory processing queue, so an empty queue is
 * not mistaken for completed coverage.
 */
class AdaptiveHistoryScanPolicy(
    private val automaticWindowsDays: List<Int> = listOf(7, 30, 90),
    val allAvailableHistoryDays: Int = 36_500
) {
    init {
        require(automaticWindowsDays.isNotEmpty())
        require(automaticWindowsDays.all { it > 0 })
        require(automaticWindowsDays.zipWithNext().all { (a, b) -> a < b })
        require(allAvailableHistoryDays > automaticWindowsDays.last())
    }

    val firstWindowDays: Int
        get() = automaticWindowsDays.first()

    val widestAutomaticWindowDays: Int
        get() = automaticWindowsDays.last()

    fun firstWindowAfter(coveredWindowDays: Int?): Int =
        automaticWindowsDays.firstOrNull {
            coveredWindowDays == null || it > coveredWindowDays
        } ?: allAvailableHistoryDays

    fun decide(
        windowDays: Int,
        providerMessageCount: Int,
        eligibleCandidateCount: Int,
        /**
         * Supplied only at the widest automatic boundary when the selected
         * range itself is empty. `null` means the coordinator did not perform
         * the cheap all-history existence probe.
         */
        inboxHasAnyMessage: Boolean? = null
    ): HistoryScanDecision {
        require(providerMessageCount >= 0)
        require(eligibleCandidateCount >= 0)
        require(eligibleCandidateCount <= providerMessageCount)

        if (eligibleCandidateCount > 0) {
            return HistoryScanDecision.Process(windowDays)
        }

        val nextAutomatic = automaticWindowsDays.firstOrNull { it > windowDays }
        return if (nextAutomatic != null) {
            HistoryScanDecision.Widen(nextAutomatic)
        } else {
            HistoryScanDecision.NoEligibleHistory(
                windowDays = windowDays,
                reason = when {
                    providerMessageCount > 0 ->
                        SetupEmptyReason.FILTERED_OUT
                    inboxHasAnyMessage == false ->
                        SetupEmptyReason.EMPTY_INBOX
                    else ->
                        SetupEmptyReason.NO_ELIGIBLE_WITHIN_90_DAYS
                }
            )
        }
    }
}

sealed interface HistoryScanDecision {
    data class Widen(val nextWindowDays: Int) : HistoryScanDecision
    data class Process(val windowDays: Int) : HistoryScanDecision
    data class NoEligibleHistory(
        val windowDays: Int,
        val reason: SetupEmptyReason
    ) : HistoryScanDecision
}
