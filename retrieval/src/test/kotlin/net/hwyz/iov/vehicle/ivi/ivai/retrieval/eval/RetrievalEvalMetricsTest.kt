package net.hwyz.iov.vehicle.ivi.ivai.retrieval.eval

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-011 验证设计 · 离线评测指标计算：oracle 检索器应得满分、空检索器应得零分、
 * 无效候选率与无证据拒答率正确。
 */
class RetrievalEvalMetricsTest {

    private val queries = listOf(
        RetrievalEvalQuery("打开空调", setOf("t1")),
        RetrievalEvalQuery("播放音乐", setOf("t2")),
        RetrievalEvalQuery("无关问题", setOf(), expectEmpty = true)
    )

    @Test
    fun `oracle 检索器各项指标满分`() = runTest {
        val result = RetrievalEvalRunner.evaluate(
            queries = queries,
            eligible = setOf("t1", "t2"),
            topK = 5,
            retrieve = { q, _ ->
                if (q.expectEmpty) emptyList() else listOf(q.expectedIds.first(), "other")
            }
        )
        // 非空查询 2 条满分，expectEmpty 查询期望为空故 recall/mrr 记 0 → 2/3。
        assertEquals(2.0 / 3.0, result.recallAt1)
        assertEquals(2.0 / 3.0, result.recallAtK)
        assertEquals(2.0 / 3.0, result.mrr)
        assertEquals(2.0 / 3.0, result.top1Accuracy)
        assertEquals(2.0 / 15.0, result.invalidCandidateRate) // 每个非空查询返回 1 个无效候选 “other”
        assertEquals(1.0, result.noEvidenceRefusalRate)
    }

    @Test
    fun `空检索器各项指标为零且拒答率满分`() = runTest {
        val result = RetrievalEvalRunner.evaluate(
            queries = queries,
            eligible = setOf("t1", "t2"),
            topK = 5,
            retrieve = { _, _ -> emptyList() }
        )
        assertEquals(0.0, result.recallAt1)
        assertEquals(0.0, result.mrr)
        assertEquals(0.0, result.top1Accuracy)
        assertEquals(0.0, result.invalidCandidateRate)
        assertEquals(1.0, result.noEvidenceRefusalRate)
    }

    @Test
    fun `版本适用率按指定版本查询统计`() = runTest {
        val q = listOf(
            RetrievalEvalQuery("胎压报警", setOf("t1"), softwareVersion = "0.2.0"),
            RetrievalEvalQuery("胎压报警", setOf("t1"), softwareVersion = "0.0.5")
        )
        val result = RetrievalEvalRunner.evaluate(
            queries = q,
            eligible = setOf("t1"),
            topK = 5,
            retrieve = { _, _ -> listOf("t1") }
        )
        assertEquals(1.0, result.recallAt1)
        assertTrue(result.summary().isNotBlank())
    }

    @Test
    fun `指标均在 0 到 1 之间`() = runTest {
        val result = RetrievalEvalRunner.evaluate(
            queries = queries,
            eligible = setOf("t1", "t2"),
            topK = 3,
            retrieve = { _, _ -> listOf("t1", "t2") }
        )
        listOf(result.recallAt1, result.recallAt3, result.recallAtK, result.mrr, result.top1Accuracy, result.invalidCandidateRate)
            .forEach { assertTrue(it in 0.0..1.0, "指标越界: $it") }
    }
}
