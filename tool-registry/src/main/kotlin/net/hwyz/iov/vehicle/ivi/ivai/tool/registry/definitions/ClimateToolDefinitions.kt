package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.schemas.ClimateSchemas

/**
 * First-batch climate tool definitions (IVI-IVAI-DSN-CR-001).
 */
object ClimateToolDefinitions {

    fun registerAll(registry: ToolRegistry): ToolRegistry = registry
        .register(powerOn())
        .register(powerOff())
        .register(temperatureIncrease())
        .register(temperatureDecrease())
        .register(temperatureSet())
        .register(statusQuery())

    private fun powerOn() = ToolDefinition(
        toolId = "climate.power_on",
        functionId = "AC_Control_1",
        name = "打开空调",
        description = "明确打开空调电源。",
        positiveExamples = listOf("打开空调", "开启空调", "把空调打开"),
        negativeExamples = listOf("我有点冷", "太冷了", "吹一下风"),
        selectionPriority = 10,
        parameterSchema = ClimateSchemas.powerSchema,
        policy = ToolPolicy(riskLevel = RiskLevel.LOW),
        execution = ToolExecutionBinding(adapterId = "mock-vehicle", methodId = "powerOn")
    )

    private fun powerOff() = ToolDefinition(
        toolId = "climate.power_off",
        functionId = "AC_Control_2",
        name = "关闭空调",
        description = "明确关闭空调电源。",
        positiveExamples = listOf("关闭空调", "关空调", "把空调关掉"),
        negativeExamples = listOf("太热了", "太冷了", "风大一点"),
        selectionPriority = 10,
        parameterSchema = ClimateSchemas.powerSchema,
        policy = ToolPolicy(riskLevel = RiskLevel.LOW),
        execution = ToolExecutionBinding(adapterId = "mock-vehicle", methodId = "powerOff")
    )

    private fun temperatureIncrease() = ToolDefinition(
        toolId = "climate.temperature_increase",
        functionId = "AC_Temperature_2",
        name = "升温",
        description = "升高空调温度，适用于冷/凉等隐式表达。",
        positiveExamples = listOf("我有点冷", "太冷了", "温度调高一点"),
        negativeExamples = listOf("打开空调", "太热了", "温度调到24度"),
        selectionPriority = 30,
        parameterSchema = ClimateSchemas.stepSchema,
        policy = ToolPolicy(riskLevel = RiskLevel.MEDIUM),
        execution = ToolExecutionBinding(adapterId = "mock-vehicle", methodId = "increaseTemperature")
    )

    private fun temperatureDecrease() = ToolDefinition(
        toolId = "climate.temperature_decrease",
        functionId = "AC_Temperature_3",
        name = "降温",
        description = "降低空调温度，适用于热等隐式表达。",
        positiveExamples = listOf("太热了", "有点热", "温度调低一点"),
        negativeExamples = listOf("打开空调", "太冷了", "温度调到26度"),
        selectionPriority = 30,
        parameterSchema = ClimateSchemas.stepSchema,
        policy = ToolPolicy(riskLevel = RiskLevel.MEDIUM),
        execution = ToolExecutionBinding(adapterId = "mock-vehicle", methodId = "decreaseTemperature")
    )

    private fun temperatureSet() = ToolDefinition(
        toolId = "climate.temperature_set",
        functionId = "AC_Temperature_1",
        name = "设定温度",
        description = "将空调温度设置为绝对温度值，temperature 为必填。",
        positiveExamples = listOf("温度调到24度", "调到26度", "空调设成25度"),
        negativeExamples = listOf("温度调高一点", "太冷了", "打开空调"),
        selectionPriority = 20,
        parameterSchema = ClimateSchemas.temperatureSetSchema,
        policy = ToolPolicy(riskLevel = RiskLevel.MEDIUM),
        execution = ToolExecutionBinding(adapterId = "mock-vehicle", methodId = "setTemperature")
    )

    private fun statusQuery() = ToolDefinition(
        toolId = "climate.status_query",
        functionId = null,
        name = "查询空调状态",
        description = "查询空调电源与各区域温度状态。",
        positiveExamples = listOf("空调开了吗", "现在多少度", "查一下空调状态"),
        negativeExamples = listOf("打开空调", "把空调关了"),
        selectionPriority = 5,
        parameterSchema = ClimateSchemas.statusQuerySchema,
        policy = ToolPolicy(riskLevel = RiskLevel.LOW),
        execution = ToolExecutionBinding(adapterId = "mock-vehicle", methodId = "queryStatus")
    )
}
