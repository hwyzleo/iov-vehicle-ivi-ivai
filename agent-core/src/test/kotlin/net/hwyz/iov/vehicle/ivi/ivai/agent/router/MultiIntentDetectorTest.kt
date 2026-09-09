package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 多意图检测器（IVI-IVAI-DSN-CR-019 单元测试）。
 *
 * “再”在单一谓词前是 continuation/discourse marker，不得形成 MULTI_INTENT；
 * 多意图必须以独立谓词、独立对象与并列结构为依据。
 */
class MultiIntentDetectorTest {

    @Test
    fun `再加单一温度谓词是 continuation 非多意图`() {
        assertFalse(MultiIntentDetector.isMultiIntent("主驾温度再调高1度"))
        assertFalse(MultiIntentDetector.isMultiIntent("温度再调高一点"))
        assertFalse(MultiIntentDetector.isMultiIntent("副驾温度再调低一点"))
        assertFalse(MultiIntentDetector.isMultiIntent("温度再降低1度"))
    }

    @Test
    fun `逗号分隔独立谓词与对象是多意图`() {
        assertTrue(MultiIntentDetector.isMultiIntent("打开空调，再把副驾温度调到24度"))
        assertTrue(MultiIntentDetector.isMultiIntent("打开空调，再关闭车窗"))
        assertTrue(MultiIntentDetector.isMultiIntent("打开车窗，打开天窗"))
    }

    @Test
    fun `并列连接词加独立对象是多意图`() {
        assertTrue(MultiIntentDetector.isMultiIntent("打开空调然后把温度调到26度"))
        assertTrue(MultiIntentDetector.isMultiIntent("调高温度然后降低风量"))
        assertTrue(MultiIntentDetector.isMultiIntent("打开空调并且关闭车窗"))
    }

    @Test
    fun `单动作单对象不是多意图`() {
        assertFalse(MultiIntentDetector.isMultiIntent("打开车窗"))
        assertFalse(MultiIntentDetector.isMultiIntent("温度调高一点"))
        assertFalse(MultiIntentDetector.isMultiIntent("打开空调"))
        assertFalse(MultiIntentDetector.isMultiIntent(""))
    }
}
