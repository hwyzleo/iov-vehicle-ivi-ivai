package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.TemperatureBound

/**
 * 温度边界词典与车型温度拓扑（IVI-IVAI-DSN-CR-019，temperature_bound_v1）。
 *
 * “最高/最大/最热”与“最低/最小/最冷”先解析为 [TemperatureBound] 边界枚举，
 * 再读取当前车型温度 Schema 上下限（[VehicleTemperatureTopology]）转换为
 * 绝对目标值；禁止在 Prompt、Retriever 或评分器中散落硬编码 16/30。
 *
 * 车型上下限默认 16..30（与 IVAI Tool Catalog v1 的
 * climate.temperature.set 参数 Schema 一致）；按车型细化由车辆配置接入，
 * 不改变调用方契约。
 */
data class VehicleTemperatureTopology(
    val vehicleModel: String? = null,
    val minTemperature: Double = 16.0,
    val maxTemperature: Double = 30.0
) {
    /** 该绝对温度是否在当前车型允许范围内。 */
    fun inRange(temperature: Double): Boolean =
        temperature >= minTemperature && temperature <= maxTemperature

    /** 边界枚举 → 车型边界值。 */
    fun boundValue(bound: TemperatureBound): Double = when (bound) {
        TemperatureBound.MAXIMUM -> maxTemperature
        TemperatureBound.MINIMUM -> minTemperature
    }

    companion object {
        /** 默认拓扑（演示/测试车辆：16..30℃）。 */
        val DEFAULT: VehicleTemperatureTopology = VehicleTemperatureTopology(vehicleModel = null)

        /** 按车型解析拓扑。当前统一返回默认范围；按车型细化由车辆配置接入。 */
        fun forVehicle(vehicleModel: String?): VehicleTemperatureTopology =
            if (vehicleModel == null) DEFAULT else DEFAULT.copy(vehicleModel = vehicleModel)
    }
}

/**
 * temperature_bound_v1 词典（IVI-IVAI-DSN-CR-019，单一事实源）。
 *
 * 词 → 边界枚举映射；命中后由 [VehicleTemperatureTopology.boundValue] 转换为
 * 当前车型上下限。禁止未解析边界枚举而直接猜测 16/30 等数值。
 */
object TemperatureBoundLexicon {

    const val LEXICON_ID = "temperature_bound_v1"
    const val GOVERNANCE_VERSION = "ivai-temperature-bound-v1"

    /** word → 边界枚举（词长降序供最长优先匹配）。 */
    val WORDS: Map<String, TemperatureBound> = mapOf(
        "最高" to TemperatureBound.MAXIMUM,
        "最大" to TemperatureBound.MAXIMUM,
        "最热" to TemperatureBound.MAXIMUM,
        "最低" to TemperatureBound.MINIMUM,
        "最小" to TemperatureBound.MINIMUM,
        "最冷" to TemperatureBound.MINIMUM
    )

    /** 归一化文本中命中的边界（最长优先；无命中返回 null）。 */
    fun findIn(text: String): TemperatureBound? =
        WORDS.entries.sortedByDescending { it.key.length }
            .firstOrNull { text.contains(it.key) }
            ?.value

    /** 边界枚举 → 车型边界值（版本化拓扑，禁止硬编码）。 */
    fun boundValue(bound: TemperatureBound, topology: VehicleTemperatureTopology): Double =
        topology.boundValue(bound)
}
