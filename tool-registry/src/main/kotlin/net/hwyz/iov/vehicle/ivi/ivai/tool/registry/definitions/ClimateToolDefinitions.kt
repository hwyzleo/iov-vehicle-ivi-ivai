package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.AliasSourceType
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.ToolAlias
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.schemas.ClimateSchemas

/**
 * First-batch climate tool definitions (IVI-IVAI-DSN-CR-001 + CR-005 + CR-008).
 *
 * CR-005 adds L0 deterministic routing rules: uniquely identifiable commands
 * ("打开空调", "主驾调到24度") route through L0 without LLM/RAG; implicit
 * expressions ("我有点冷", "太热了") intentionally have NO L0 rule and fall to
 * L1 Tool/Intent RAG + local LLM.
 *
 * CR-008 attaches governance metadata: suggested business domain (CABIN_COMFORT /
 * BD01), owning capability pack (cabin.climate), operation types and legacy /
 * expression aliases. The 6 tools form the P0 verification set; the parameterized
 * `climate.temperature.adjust` suggestion is expressed at the governance layer
 * (see ClimateGovernedCapabilities) and will be registered as a runtime tool in a
 * later CR — the migration period keeps the legacy Tool IDs + Function-IDs via
 * [ToolDefinition.aliases].
 */
object ClimateToolDefinitions {

    const val CABIN_CLIMATE_PACK = "cabin.climate"

    fun registerAll(registry: ToolRegistry): ToolRegistry = registry
        .register(powerOn())
        .register(powerOff())
        .register(temperatureIncrease())
        .register(temperatureDecrease())
        .register(temperatureSet())
        .register(statusQuery())

    private fun availability() = ToolAvailability(
        enabled = true,
        vehicleModels = listOf("*"),
        softwareRange = ">=0.1.0"
    )

    private fun positionSlot(required: Boolean = false) = SlotPattern(
        name = "position",
        type = SlotType.POSITION,
        required = required,
        aliases = mapOf(
            "主驾" to "driver", "驾驶位" to "driver", "司机位" to "driver", "主驾驶" to "driver",
            "副驾" to "passenger", "副驾驶" to "passenger", "乘客位" to "passenger",
            "前排" to "front", "后排" to "rear"
        )
    )

    /** 归并表达 Alias：如「主驾升温」→ position=driver（CR-008 参数预置）。 */
    private fun expressionAlias(aliasId: String, phrase: String, position: String) = ToolAlias(
        aliasId = aliasId,
        sourceType = AliasSourceType.EXPRESSION,
        sourceValue = phrase,
        originalDomain = "空调与舒适",
        mappedArguments = mapOf("position" to position),
        status = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus.APPROVED
    )

    private fun functionIdAlias(aliasId: String, functionId: String) = ToolAlias(
        aliasId = aliasId,
        sourceType = AliasSourceType.FUNCTION_ID,
        sourceValue = functionId,
        status = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus.APPROVED
    )

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
        execution = ToolExecutionBinding(adapterId = "mock-vehicle", methodId = "powerOn"),
        deterministicRules = listOf(
            DeterministicIntentRule(
                ruleId = "L0.power_on.1",
                toolId = "climate.power_on",
                exactPhrases = listOf("打开空调", "开启空调", "把空调打开", "开空调"),
                slotPatterns = listOf(positionSlot()),
                priority = 10
            )
        ),
        availability = availability(),
        domainId = BusinessDomainId.CABIN_COMFORT,
        capabilityPackId = CABIN_CLIMATE_PACK,
        supportedOperations = setOf(OperationType.CONTROL),
        aliases = listOf(functionIdAlias("alias.power_on.fn", "AC_Control_1")),
        governanceVersion = "1.0"
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
        execution = ToolExecutionBinding(adapterId = "mock-vehicle", methodId = "powerOff"),
        deterministicRules = listOf(
            DeterministicIntentRule(
                ruleId = "L0.power_off.1",
                toolId = "climate.power_off",
                exactPhrases = listOf("关闭空调", "关空调", "把空调关掉", "关掉空调"),
                slotPatterns = listOf(positionSlot()),
                priority = 10
            )
        ),
        availability = availability(),
        domainId = BusinessDomainId.CABIN_COMFORT,
        capabilityPackId = CABIN_CLIMATE_PACK,
        supportedOperations = setOf(OperationType.CONTROL),
        aliases = listOf(functionIdAlias("alias.power_off.fn", "AC_Control_2")),
        governanceVersion = "1.0"
    )

    private fun temperatureIncrease() = ToolDefinition(
        toolId = "climate.temperature_increase",
        functionId = "AC_Temperature_2",
        name = "升温",
        description = "升高空调温度，适用于冷/凉等隐式表达或明确升温指令。",
        positiveExamples = listOf("我有点冷", "太冷了", "温度调高一点"),
        negativeExamples = listOf("打开空调", "太热了", "温度调到24度"),
        selectionPriority = 30,
        parameterSchema = ClimateSchemas.stepSchema,
        policy = ToolPolicy(riskLevel = RiskLevel.MEDIUM),
        execution = ToolExecutionBinding(adapterId = "mock-vehicle", methodId = "increaseTemperature"),
        // 只有显式方向指令走 L0；"我有点冷/太热了" 隐式表达不在此列（→ L1）。
        deterministicRules = listOf(
            DeterministicIntentRule(
                ruleId = "L0.temp_up.1",
                toolId = "climate.temperature_increase",
                exactPhrases = listOf("温度调高一点", "温度调高", "调高温度", "调高点", "升温"),
                synonymPatterns = listOf("调高\\d*档", "温度调高\\d*"),
                slotPatterns = listOf(positionSlot()),
                priority = 5
            )
        ),
        availability = availability(),
        domainId = BusinessDomainId.CABIN_COMFORT,
        capabilityPackId = CABIN_CLIMATE_PACK,
        supportedOperations = setOf(OperationType.CONTROL),
        aliases = listOf(
            functionIdAlias("alias.temp_inc.fn", "AC_Temperature_2"),
            expressionAlias("alias.temp_inc.driver_up", "主驾升温", "driver"),
            expressionAlias("alias.temp_inc.passenger_up", "副驾升温", "passenger")
        ),
        governanceVersion = "1.0"
    )

    private fun temperatureDecrease() = ToolDefinition(
        toolId = "climate.temperature_decrease",
        functionId = "AC_Temperature_3",
        name = "降温",
        description = "降低空调温度，适用于热等隐式表达或明确降温指令。",
        positiveExamples = listOf("太热了", "有点热", "温度调低一点"),
        negativeExamples = listOf("打开空调", "太冷了", "温度调到26度"),
        selectionPriority = 30,
        parameterSchema = ClimateSchemas.stepSchema,
        policy = ToolPolicy(riskLevel = RiskLevel.MEDIUM),
        execution = ToolExecutionBinding(adapterId = "mock-vehicle", methodId = "decreaseTemperature"),
        deterministicRules = listOf(
            DeterministicIntentRule(
                ruleId = "L0.temp_down.1",
                toolId = "climate.temperature_decrease",
                exactPhrases = listOf("温度调低一点", "温度调低", "调低温度", "调低点", "降温"),
                synonymPatterns = listOf("调低\\d*档", "温度调低\\d*"),
                slotPatterns = listOf(positionSlot()),
                priority = 5
            )
        ),
        availability = availability(),
        domainId = BusinessDomainId.CABIN_COMFORT,
        capabilityPackId = CABIN_CLIMATE_PACK,
        supportedOperations = setOf(OperationType.CONTROL),
        aliases = listOf(
            functionIdAlias("alias.temp_dec.fn", "AC_Temperature_3"),
            expressionAlias("alias.temp_dec.driver_down", "主驾降温", "driver"),
            expressionAlias("alias.temp_dec.passenger_down", "副驾降温", "passenger")
        ),
        governanceVersion = "1.0"
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
        execution = ToolExecutionBinding(adapterId = "mock-vehicle", methodId = "setTemperature"),
        deterministicRules = listOf(
            DeterministicIntentRule(
                ruleId = "L0.temp_set.1",
                toolId = "climate.temperature_set",
                exactPhrases = listOf("温度调到", "调到", "设为", "设成", "空调设成", "调成"),
                slotPatterns = listOf(
                    SlotPattern(name = "temperature", type = SlotType.TEMPERATURE, required = true),
                    positionSlot()
                ),
                priority = 10
            )
        ),
        availability = availability(),
        domainId = BusinessDomainId.CABIN_COMFORT,
        capabilityPackId = CABIN_CLIMATE_PACK,
        supportedOperations = setOf(OperationType.CONTROL),
        aliases = listOf(functionIdAlias("alias.temp_set.fn", "AC_Temperature_1")),
        governanceVersion = "1.0"
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
        execution = ToolExecutionBinding(adapterId = "mock-vehicle", methodId = "queryStatus"),
        deterministicRules = listOf(
            DeterministicIntentRule(
                ruleId = "L0.status.1",
                toolId = "climate.status_query",
                exactPhrases = listOf("空调开了吗", "空调关了吗", "现在多少度", "空调状态", "查一下空调"),
                priority = 5
            )
        ),
        availability = availability(),
        domainId = BusinessDomainId.CABIN_COMFORT,
        capabilityPackId = CABIN_CLIMATE_PACK,
        supportedOperations = setOf(OperationType.QUERY),
        aliases = emptyList(),
        governanceVersion = "1.0"
    )
}
