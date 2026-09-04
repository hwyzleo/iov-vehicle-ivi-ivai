package net.hwyz.iov.vehicle.ivi.ivai.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Performance metric invariants (IVI-IVAI-DSN-CR-004): unattributed is the
 * non-overlapping top-level remainder (never named network time, never < 0),
 * and phase timestamps must be monotonic (else IVAI-METRICS-001).
 */
class PerformanceMetricsValidatorTest {

    @Test
    fun `unattributed is endToEnd minus all top-level phases`() {
        val result = PerformanceMetricsValidator.unattributedMs(
            endToEndMs = 1000,
            queueMs = 10,
            contextAndPromptMs = 20,
            modelCallTotalMs = 500,
            parseAndSchemaMs = 40,
            routeAndPolicyMs = 30,
            toolExecutionMs = 100,
            eventDispatchMs = 5
        )
        assertEquals(295, result)
    }

    @Test
    fun `unattributed treats unknown phases as zero and never goes negative`() {
        // Unknown (null) phases contribute nothing; the remainder is clamped to 0.
        val result = PerformanceMetricsValidator.unattributedMs(
            endToEndMs = 100,
            queueMs = null,
            contextAndPromptMs = 10,
            modelCallTotalMs = null,
            parseAndSchemaMs = null,
            routeAndPolicyMs = null,
            toolExecutionMs = 200,
            eventDispatchMs = null
        )
        assertEquals(0, result)
    }

    @Test
    fun `monotonic phase timestamps are legal`() {
        assertNull(
            PerformanceMetricsValidator.validatePhaseOrder(
                listOf(1_000L, 2_000L, 3_000L)
            )
        )
    }

    @Test
    fun `out of order or missing timestamps yield IVAI-METRICS-001`() {
        assertEquals(
            PerformanceMetricsValidator.METRICS_INVALID,
            PerformanceMetricsValidator.validatePhaseOrder(listOf(3_000L, 2_000L, 4_000L))
        )
    }
}
