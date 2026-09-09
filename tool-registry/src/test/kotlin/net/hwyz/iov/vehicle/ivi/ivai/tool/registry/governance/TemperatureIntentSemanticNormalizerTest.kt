package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 温度意图语义标准化器（IVI-IVAI-DSN-CR-019 单元测试）。
 *
 * 覆盖设计「温度语义标准化」判定规则：RELATIVE_DELTA / ABSOLUTE_TARGET /
 * BOUND_TARGET / OUT_OF_RANGE / AMBIGUOUS，以及非温度请求不干预。
 */
class TemperatureIntentSemanticNormalizerTest {

    private val normalizer = TemperatureIntentSemanticNormalizer()

    private fun normalize(text: String) = normalizer.normalize(text)

    // ---- RELATIVE_DELTA ----
    @Test
    fun `相对增减带数值判定为 RELATIVE_DELTA`() {
        val ev = normalize("主驾温度再调高1度")
        assertEquals(TemperatureOperationSemantic.RELATIVE_DELTA, ev.operation)
        assertEquals("increase", ev.direction)
        assertEquals(1.0, ev.step)
        assertEquals("driver", ev.zone)
    }

    @Test
    fun `升高降低动词判定为 RELATIVE_DELTA`() {
        val ev = normalize("温度升高2度")
        assertEquals(TemperatureOperationSemantic.RELATIVE_DELTA, ev.operation)
        assertEquals("increase", ev.direction)
        assertEquals(2.0, ev.step)

        val down = normalize("副驾温度降低一点")
        assertEquals(TemperatureOperationSemantic.RELATIVE_DELTA, down.operation)
        assertEquals("decrease", down.direction)
        assertEquals(1.0, down.step)
        assertEquals("passenger", down.zone)
    }

    @Test
    fun `定性幅度映射到 canonical step`() {
        assertEquals(0.5, normalize("温度调高一丢丢").step)
        assertEquals(0.5, normalize("温度调低半度").step)
        assertEquals(1.0, normalize("温度调高一点").step)
        assertEquals(1.0, normalize("温度调低一些").step)
        assertEquals(2.0, normalize("温度调高明显一些").step)
    }

    // ---- ABSOLUTE_TARGET ----
    @Test
    fun `绝对目标判定为 ABSOLUTE_TARGET`() {
        val ev = normalize("温度调到24度")
        assertEquals(TemperatureOperationSemantic.ABSOLUTE_TARGET, ev.operation)
        assertEquals(24.0, ev.temperature)
    }

    @Test
    fun `升到降到属于绝对目标`() {
        assertEquals(TemperatureOperationSemantic.ABSOLUTE_TARGET, normalize("温度升到26度").operation)
        assertEquals(TemperatureOperationSemantic.ABSOLUTE_TARGET, normalize("温度降到26度").operation)
    }

    @Test
    fun `相对动词加到绝对数值判定为绝对目标`() {
        // “温度调高到24度” = 把温度调到 24 度（绝对目标），不是相对增减。
        val ev = normalize("温度调高到24度")
        assertEquals(TemperatureOperationSemantic.ABSOLUTE_TARGET, ev.operation)
        assertEquals(24.0, ev.temperature)
    }

    // ---- BOUND_TARGET ----
    @Test
    fun `边界词判定为 BOUND_TARGET 并转换车型边界`() {
        val max = normalize("温度调到最高")
        assertEquals(TemperatureOperationSemantic.BOUND_TARGET, max.operation)
        assertEquals(TemperatureBound.MAXIMUM, max.bound)
        assertEquals(30.0, max.temperature) // 车型默认上限

        val min = normalize("主驾温度调到最低")
        assertEquals(TemperatureOperationSemantic.BOUND_TARGET, min.operation)
        assertEquals(TemperatureBound.MINIMUM, min.bound)
        assertEquals(16.0, min.temperature) // 车型默认下限
        assertEquals("driver", min.zone)
    }

    // ---- OUT_OF_RANGE ----
    @Test
    fun `越界绝对温度判定为 OUT_OF_RANGE`() {
        assertEquals(TemperatureOperationSemantic.OUT_OF_RANGE, normalize("温度调到35度").operation)
        assertEquals(TemperatureOperationSemantic.OUT_OF_RANGE, normalize("温度调到5度").operation)
        assertEquals(TemperatureOperationSemantic.OUT_OF_RANGE, normalize("温度调到40度").operation)
    }

    @Test
    fun `边界值16与30在范围内`() {
        assertEquals(TemperatureOperationSemantic.ABSOLUTE_TARGET, normalize("温度调到16度").operation)
        assertEquals(TemperatureOperationSemantic.ABSOLUTE_TARGET, normalize("温度调到30度").operation)
    }

    // ---- AMBIGUOUS ----
    @Test
    fun `缺少单位或动作对象判定为 AMBIGUOUS`() {
        assertEquals(TemperatureOperationSemantic.AMBIGUOUS, normalize("温度调到5").operation)
        assertEquals(TemperatureOperationSemantic.AMBIGUOUS, normalize("24度").operation)
        assertEquals(TemperatureOperationSemantic.AMBIGUOUS, normalize("温度调到").operation)
        assertEquals(TemperatureOperationSemantic.AMBIGUOUS, normalize("空调温度").operation)
    }

    // ---- 非温度请求不干预 ----
    @Test
    fun `非温度请求返回空证据`() {
        assertTrue(normalize("风量调到5档").evidence.isEmpty())
        assertTrue(normalize("打开空调").evidence.isEmpty())
        assertTrue(normalize("我有点冷").evidence.isEmpty())
        assertTrue(normalize("太热了").evidence.isEmpty())
        assertTrue(normalize("打开车窗").evidence.isEmpty())
    }
}
