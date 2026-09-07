package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

/**
 * L0 槽位受控别名词表（IVI-IVAI-DSN-CR-013）。
 *
 * Catalog L0 规则的 slotPatterns.aliases 以名称引用这些受控词表，编译时展开为
 * 运行时 SlotPattern 的 word → canonical 映射。词表必须经评审闭合后才允许
 * 标记对应 Tool 为 SUPPORTED；NEEDS_REVIEW 常见原因即为词表未闭合。
 */
object L0AliasVocabularies {

    /** 车内区域/座位位置（zone / position 通用）。 */
    val vehicle_position_v1: Map<String, String> = mapOf(
        "主驾" to "driver", "驾驶位" to "driver", "司机位" to "driver", "主驾驶" to "driver",
        "副驾" to "passenger", "副驾驶" to "passenger", "乘客位" to "passenger",
        "前排" to "front", "后排" to "rear", "全车" to "all"
    )

    /** 车窗位置（body.window.*）。 */
    val window_position_aliases_v1: Map<String, String> = mapOf(
        "左前" to "left_front", "右前" to "right_front",
        "左后" to "left_rear", "右后" to "right_rear",
        "全部" to "all", "全车" to "all", "所有" to "all"
    )

    /** 车门位置（body.door.*）。 */
    val door_position_aliases_v1: Map<String, String> = mapOf(
        "左前" to "left_front", "右前" to "right_front",
        "左后" to "left_rear", "右后" to "right_rear",
        "全部" to "all", "全车" to "all", "所有" to "all"
    )

    /** 按名称解析词表；未知词表名抛错（构建期应被 Validator 拦截）。 */
    fun resolve(name: String): Map<String, String> = when (name) {
        "vehicle_position_v1" -> vehicle_position_v1
        "window_position_aliases_v1" -> window_position_aliases_v1
        "door_position_aliases_v1" -> door_position_aliases_v1
        else -> throw IllegalArgumentException("未知 L0 受控别名词表: $name")
    }

    /** 已注册词表名（校验引用闭合）。 */
    val names: Set<String> = setOf(
        "vehicle_position_v1",
        "window_position_aliases_v1",
        "door_position_aliases_v1"
    )
}
