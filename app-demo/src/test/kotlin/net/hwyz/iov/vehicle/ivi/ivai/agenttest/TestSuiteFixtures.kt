package net.hwyz.iov.vehicle.ivi.ivai.agenttest

/**
 * CR-015 测试共用 Suite JSON 夹具（内置与导入路径同 Schema）。
 */
object TestSuiteFixtures {

    /** 两条例的合法导入 Suite。 */
    val validSuiteRaw: String = """
        {
          "suiteId": "suite-test-v1",
          "schemaVersion": 1,
          "governanceVersion": "ivai-governance-v1-draft",
          "cases": [
            {
              "caseId": "CASE-001",
              "input": "打开空调",
              "expectedTier": "L0_DETERMINISTIC_TOOL",
              "expectedDomain": "CABIN_COMFORT",
              "expectedCapabilityPack": "cabin.climate",
              "expectedTarget": { "type": "TOOL", "id": "climate.power.set" },
              "expectedArguments": { "enabled": true }
            },
            {
              "caseId": "CASE-002",
              "input": "我有点冷",
              "expectedTier": "L1_LOCAL_TOOL_REASONING",
              "expectedDomain": "CABIN_COMFORT",
              "expectedCapabilityPack": "cabin.climate"
            }
          ]
        }
    """.trimIndent()

    /** 单条例的内置 Suite（与导入 Suite 的 suiteId 不同，用于优先级校验）。 */
    val builtinSuiteRaw: String = """
        {
          "suiteId": "builtin-suite",
          "schemaVersion": 1,
          "governanceVersion": "ivai-governance-v1-draft",
          "cases": [
            {
              "caseId": "B-001",
              "input": "打开空调",
              "expectedTier": "L0_DETERMINISTIC_TOOL",
              "expectedDomain": "CABIN_COMFORT",
              "expectedCapabilityPack": "cabin.climate",
              "expectedTarget": { "type": "TOOL", "id": "climate.power.set" },
              "expectedArguments": { "enabled": true }
            }
          ]
        }
    """.trimIndent()
}
