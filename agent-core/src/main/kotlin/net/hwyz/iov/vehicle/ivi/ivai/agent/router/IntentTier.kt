package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import kotlinx.serialization.Serializable

/**
 * Intent routing tiers (IVI-IVAI-DSN-CR-005):
 *
 *  - L0_DETERMINISTIC_TOOL: unique deterministic command → Tool Call without LLM/RAG
 *  - L1_LOCAL_TOOL_REASONING: implicit tool intent → Tool/Intent RAG + local LLM
 *  - L2_LOCAL_KNOWLEDGE: local knowledge question → Knowledge RAG + local LLM
 *  - L3_CLOUD_AI: complex / open domain → cloud AI
 *  - WORKFLOW_EXECUTION: CR-008 — a registered workflow was deterministically matched and
 *    selected by the WorkflowPlanner; each step still passes the safe execution chain
 *  - REJECT: unsafe or unable to process
 */
@Serializable
enum class IntentTier {
    L0_DETERMINISTIC_TOOL,
    L1_LOCAL_TOOL_REASONING,
    L2_LOCAL_KNOWLEDGE,
    L3_CLOUD_AI,
    WORKFLOW_EXECUTION,
    REJECT;

    companion object {
        /**
         * UI label mapping (CR-005 "响应气泡执行层级展示" + CR-008 workflow tier):
         * L0 · 本地直达 / L1 · 本地模型 / L2 · 本地知识 / L3 · 云端 AI /
         * WF · 场景编排 / 未执行 · 已拒绝
         */
        fun label(tier: IntentTier): String = when (tier) {
            L0_DETERMINISTIC_TOOL -> "L0 · 本地直达"
            L1_LOCAL_TOOL_REASONING -> "L1 · 本地模型"
            L2_LOCAL_KNOWLEDGE -> "L2 · 本地知识"
            L3_CLOUD_AI -> "L3 · 云端 AI"
            WORKFLOW_EXECUTION -> "WF · 场景编排"
            REJECT -> "未执行 · 已拒绝"
        }
    }
}
