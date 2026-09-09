package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases

/**
 * 车型座舱拓扑（IVI-IVAI-DSN-CR-017，REQ-173）。
 *
 * 位置 Alias 合法性受车型座舱拓扑约束：车型不存在三排时，不得把 "3排" 转换为
 * 可执行参数（IVAI-ALIAS-TOPOLOGY-001）。当前首期为「是否具备对应排/区」的布尔
 * 拓扑模型；随车型配置扩展可细化到具体座位布局，不改变 [PositionAliasResolver] 契约。
 */
data class VehicleCabinTopology(
    val vehicleModel: String? = null,
    val hasDriver: Boolean = true,
    val hasPassenger: Boolean = true,
    val hasFront: Boolean = true,
    val hasRear: Boolean = true,
    val hasSecondRow: Boolean = true,
    val hasThirdRow: Boolean = false,
    val hasMiddleLeft: Boolean = true,
    val hasMiddleRight: Boolean = true
) {
    /** 该 canonical zone 是否适用于当前车型。 */
    fun supports(zone: String): Boolean = when (zone) {
        "all" -> true
        "driver" -> hasDriver
        "passenger" -> hasPassenger
        "front" -> hasFront
        "rear" -> hasRear
        "second_row" -> hasSecondRow
        "third_row" -> hasThirdRow
        "middle_left" -> hasMiddleLeft && hasSecondRow
        "middle_right" -> hasMiddleRight && hasSecondRow
        else -> false
    }

    companion object {
        /** 默认拓扑（演示/测试车辆：三排齐全，支持全部 P0 zone）。 */
        val DEFAULT: VehicleCabinTopology = VehicleCabinTopology(vehicleModel = null, hasThirdRow = true)

        /** 两排车型（无三排/无中排区）。 */
        fun twoRow(vehicleModel: String? = null): VehicleCabinTopology = VehicleCabinTopology(
            vehicleModel = vehicleModel,
            hasThirdRow = false,
            hasMiddleLeft = false,
            hasMiddleRight = false
        )

        /**
         * 按车型解析拓扑。当前统一返回默认三排拓扑；按车型细化由车辆配置接入，
         * 不改变 Resolver 调用方。
         */
        fun forVehicle(vehicleModel: String?): VehicleCabinTopology =
            if (vehicleModel == null) DEFAULT else DEFAULT.copy(vehicleModel = vehicleModel)
    }
}
