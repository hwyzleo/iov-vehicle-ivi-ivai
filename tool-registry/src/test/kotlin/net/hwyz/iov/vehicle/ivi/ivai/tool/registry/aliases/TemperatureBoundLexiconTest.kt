package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.TemperatureBound
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 温度边界词典与车型温度拓扑（IVI-IVAI-DSN-CR-019 单元测试）。
 *
 * temperature_bound_v1：最高/最大/最热 → MAXIMUM，最低/最小/最冷 → MINIMUM；
 * 边界值由版本化车型拓扑转换（默认 16..30），禁止硬编码 16/30 于 Prompt。
 */
class TemperatureBoundLexiconTest {

    @Test
    fun `边界词解析为枚举`() {
        assertEquals(TemperatureBound.MAXIMUM, TemperatureBoundLexicon.findIn("最高"))
        assertEquals(TemperatureBound.MAXIMUM, TemperatureBoundLexicon.findIn("最大"))
        assertEquals(TemperatureBound.MAXIMUM, TemperatureBoundLexicon.findIn("最热"))
        assertEquals(TemperatureBound.MINIMUM, TemperatureBoundLexicon.findIn("最低"))
        assertEquals(TemperatureBound.MINIMUM, TemperatureBoundLexicon.findIn("最小"))
        assertEquals(TemperatureBound.MINIMUM, TemperatureBoundLexicon.findIn("最冷"))
    }

    @Test
    fun `文本中提取边界词`() {
        assertEquals(TemperatureBound.MAXIMUM, TemperatureBoundLexicon.findIn("温度调到最高"))
        assertEquals(TemperatureBound.MINIMUM, TemperatureBoundLexicon.findIn("主驾温度调到最低"))
        assertNull(TemperatureBoundLexicon.findIn("温度调到24度"))
    }

    @Test
    fun `车型拓扑默认上下限为16到30`() {
        val topology = VehicleTemperatureTopology.DEFAULT
        assertEquals(16.0, topology.minTemperature)
        assertEquals(30.0, topology.maxTemperature)
        assertTrue(topology.inRange(16.0))
        assertTrue(topology.inRange(30.0))
        assertTrue(!topology.inRange(15.9))
        assertTrue(!topology.inRange(30.1))
    }

    @Test
    fun `边界枚举经车型拓扑转换为边界值`() {
        val topology = VehicleTemperatureTopology.forVehicle("demo")
        assertEquals(30.0, TemperatureBoundLexicon.boundValue(TemperatureBound.MAXIMUM, topology))
        assertEquals(16.0, TemperatureBoundLexicon.boundValue(TemperatureBound.MINIMUM, topology))
        // 与 Tool Catalog 温度 Schema 上下限一致（CR-019 收敛 32 → 30）。
        assertEquals(topology.maxTemperature, TemperatureBoundLexicon.boundValue(TemperatureBound.MAXIMUM, topology))
    }
}
