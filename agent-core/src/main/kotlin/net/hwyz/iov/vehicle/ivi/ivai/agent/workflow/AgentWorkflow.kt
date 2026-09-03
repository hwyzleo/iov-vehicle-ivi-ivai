package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState
import net.hwyz.iov.vehicle.ivi.ivai.agent.error.ErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentOutput
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentRoute
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.Intent
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.jsonArgsToValues
import net.hwyz.iov.vehicle.ivi.ivai.agent.policy.AgentPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.agent.policy.PolicyOutcome
import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptBuilder
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.RouteDecision
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.Router
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelClientException
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelErrorKind
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelResponse
import net.hwyz.iov.vehicle.ivi.ivai.observability.RequestTelemetry
import net.hwyz.iov.vehicle.ivi.ivai.observability.TelemetryRecord
import net.hwyz.iov.vehicle.ivi.ivai.observability.TelemetryRecorder
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionContext
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolExecutionResult
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolExecutor
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolLifecycleEvent
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolLifecycleListener
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolLifecyclePhase
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolValidationIssue
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolValidator
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ValidationIssueKind
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateProvider
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateSnapshot

data class AgentInput(
    val requestId: String,
    val text: String,
    val source: String = "unknown"
)

/**
 * Result of processing one user turn.
 *
 * @param state final state machine state
 * @param rawModelContent raw message.content from the model (for debugging display)
 * @param replayed true when an idempotent duplicate request returned a cached result
 */
data class AgentResult(
    val requestId: String,
    val sessionId: String,
    val state: AgentState,
    val route: AgentRoute? = null,
    val parsedOutput: AgentOutput? = null,
    val validationIssues: List<ToolValidationIssue> = emptyList(),
    val executionResult: ToolExecutionResult? = null,
    val errorCode: String? = null,
    val responseText: String,
    val rawModelContent: String? = null,
    val replayed: Boolean = false,
    val telemetry: RequestTelemetry
)

/**
 * Agent orchestration engine: drives the state machine from RECEIVED to a terminal
 * state, enforcing the validation order (IVI-IVAI-DSN-CR-001). Any critical
 * validation failure blocks execution.
 */
class AgentWorkflow(
    private val modelProvider: ModelProvider,
    private val registry: ToolRegistry,
    private val router: Router,
    private val promptBuilder: PromptBuilder,
    private val validator: ToolValidator,
    private val agentPolicy: AgentPolicyEngine,
    private val toolExecutor: ToolExecutor,
    private val config: AgentConfig,
    private val vehicleStateProvider: VehicleStateProvider? = null,
    private val telemetryRecorder: TelemetryRecorder? = null,
    private val lifecycleListener: ToolLifecycleListener? = null,
    private val idempotencyGuard: IdempotencyGuard = IdempotencyGuard()
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun process(input: AgentInput, session: Session): AgentResult {
        val startMs = System.currentTimeMillis()
        record(input, session, AgentState.RECEIVED)
        session.appendUser(input.text)

        // --- confirmation approval path (skip the model) ---
        val pending = session.pendingTask
        if (pending != null && pending.needConfirmation && input.text.trim() == config.confirmationKeyword) {
            session.consumePendingTask()
            return executeAuthorized(input, session, listOf(pending.intent), parsedOutput = null, startMs)
        }

        // --- model call ---
        record(input, session, AgentState.ROUTED)
        record(input, session, AgentState.MODEL_REQUESTED)
        val modelResponse: ModelResponse = try {
            modelProvider.generate(
                ModelRequest(
                    requestId = input.requestId,
                    model = config.model,
                    messages = promptBuilder.build(session, input, vehicleState()),
                    timeoutMs = config.requestTimeoutMs
                )
            )
        } catch (e: ModelClientException) {
            val code = mapModelError(e.kind)
            return finish(
                input, session, AgentState.FAILED, null, null, emptyList(), null,
                code.code, "模型调用失败：${e.message}", null, false, startMs, -1,
                validJson = false, schemaPassed = false, toolExecuted = false
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return finish(
                input, session, AgentState.FAILED, null, null, emptyList(), null,
                ErrorCode.MODEL_UNAVAILABLE.code, "模型调用异常：${e.message}", null, false, startMs, -1,
                validJson = false, schemaPassed = false, toolExecuted = false
            )
        }
        record(input, session, AgentState.MODEL_RESPONDED, latencyMs = modelResponse.latencyMs)

        // --- second-level JSON parse ---
        val contentJson = modelResponse.contentJson
        if (contentJson == null) {
            return finish(
                input, session, AgentState.FAILED, null, null, emptyList(), null,
                ErrorCode.MODEL_RESPONSE_PARSE.code, "模型响应无法解析为 JSON", modelResponse.content,
                false, startMs, modelResponse.latencyMs, validJson = false, schemaPassed = false, toolExecuted = false
            )
        }
        record(input, session, AgentState.PARSED)

        // --- top-level output schema check ---
        val output: AgentOutput = try {
            json.decodeFromJsonElement(AgentOutput.serializer(), contentJson)
        } catch (e: Exception) {
            return finish(
                input, session, AgentState.FAILED, null, null, emptyList(), null,
                ErrorCode.OUTPUT_SCHEMA.code, "结构化输出不符合 Schema：${e.message}", modelResponse.content,
                false, startMs, modelResponse.latencyMs, validJson = true, schemaPassed = false, toolExecuted = false
            )
        }

        // --- route decision ---
        val routeDecision = router.resolve(output)
        if (routeDecision is RouteDecision.Unsafe) {
            return finish(
                input, session, AgentState.REJECTED, null, output, emptyList(), null,
                ErrorCode.ROUTE_UNSAFE.code, routeDecision.message, modelResponse.content,
                false, startMs, modelResponse.latencyMs, validJson = true, schemaPassed = true, toolExecuted = false
            )
        }
        val route = (routeDecision as RouteDecision.Safe).route
        session.setRoute(route)

        return when (route) {
            AgentRoute.LOCAL_DIALOGUE -> handleDialogue(input, session, output, startMs, modelResponse)
            AgentRoute.CLOUD_AI -> finish(
                input, session, AgentState.CLOUD_REQUIRED, route, output, emptyList(), null, null,
                "该请求需要云端 AI 处理（预留功能，暂不执行）。", modelResponse.content, false, startMs,
                modelResponse.latencyMs, validJson = true, schemaPassed = true, toolExecuted = false
            )
            AgentRoute.REJECT -> finish(
                input, session, AgentState.REJECTED, route, output, emptyList(), null, null,
                "已拒绝该请求。", modelResponse.content, false, startMs,
                modelResponse.latencyMs, validJson = true, schemaPassed = true, toolExecuted = false
            )
            AgentRoute.LOCAL_TOOL -> handleLocalTool(input, session, output, startMs, modelResponse)
        }
    }

    // ------------------------------------------------------------------ branches

    private fun handleDialogue(
        input: AgentInput,
        session: Session,
        output: AgentOutput,
        startMs: Long,
        modelResponse: ModelResponse
    ): AgentResult {
        val missing = output.missingArguments
        output.intents.firstOrNull()?.let { intent ->
            session.storePendingTask(intent, needConfirmation = false, missingArguments = missing)
        }
        record(input, session, AgentState.NEED_DIALOGUE)
        record(input, session, AgentState.WAITING_USER)
        val text = if (missing.isEmpty()) {
            "需要更多信息才能继续处理该请求。"
        } else {
            "请问需要补充：${missing.joinToString("、")}。"
        }
        return finish(
            input, session, AgentState.WAITING_USER, AgentRoute.LOCAL_DIALOGUE, output, emptyList(), null, null,
            text, modelResponse.content, false, startMs, modelResponse.latencyMs,
            validJson = true, schemaPassed = true, toolExecuted = false
        )
    }

    private suspend fun handleLocalTool(
        input: AgentInput,
        session: Session,
        output: AgentOutput,
        startMs: Long,
        modelResponse: ModelResponse
    ): AgentResult {
        // Restore / merge a pending missing-argument task with the same tool.
        val merged = mergePending(session, output)

        // Whitelist + parameter schema validation.
        val issues = validateIntents(input, session, merged)
        record(input, session, AgentState.VALIDATED)
        if (issues.isNotEmpty()) {
            val unknownTool = issues.any { it.kind == ValidationIssueKind.UNKNOWN_TOOL }
            val errorCode = if (unknownTool) ErrorCode.UNKNOWN_TOOL else ErrorCode.INVALID_ARGUMENT
            emitLifecycle(ToolLifecyclePhase.FAILED, input.requestId, issues.first().toolId.orEmpty(), issues.first().message)
            return finish(
                input, session, AgentState.REJECTED, AgentRoute.LOCAL_TOOL, merged, issues, null,
                errorCode.code, issues.first().message, modelResponse.content, false, startMs,
                modelResponse.latencyMs, validJson = true, schemaPassed = true, toolExecuted = false
            )
        }

        // Policy (risk / precondition / confirmation).
        val outcome = agentPolicy.evaluate(merged.intents, output, vehicleState(), confirmationGranted = false)
        when (outcome) {
            is PolicyOutcome.NeedsConfirmation -> {
                val intent = merged.intents.first()
                session.storePendingTask(intent, needConfirmation = true, missingArguments = emptyList())
                record(input, session, AgentState.NEED_DIALOGUE)
                record(input, session, AgentState.WAITING_USER)
                val name = registry.get(intent.toolId)?.name ?: intent.toolId
                return finish(
                    input, session, AgentState.WAITING_USER, AgentRoute.LOCAL_TOOL, merged, emptyList(), null, null,
                    "确认执行「$name」？回复「${config.confirmationKeyword}」以继续。", modelResponse.content, false,
                    startMs, modelResponse.latencyMs, validJson = true, schemaPassed = true, toolExecuted = false
                )
            }
            is PolicyOutcome.Denied -> {
                emitLifecycle(ToolLifecyclePhase.FAILED, input.requestId, merged.intents.first().toolId, outcome.message)
                return finish(
                    input, session, AgentState.REJECTED, AgentRoute.LOCAL_TOOL, merged, emptyList(), null,
                    outcome.errorCode.code, outcome.message, modelResponse.content, false, startMs,
                    modelResponse.latencyMs, validJson = true, schemaPassed = true, toolExecuted = false
                )
            }
            PolicyOutcome.Authorized -> {
                session.clearPendingTask()
                return executeAuthorized(input, session, merged.intents, merged, startMs)
            }
        }
    }

    private suspend fun executeAuthorized(
        input: AgentInput,
        session: Session,
        intents: List<Intent>,
        parsedOutput: AgentOutput?,
        startMs: Long
    ): AgentResult {
        val intent = intents.first()
        val toolId = intent.toolId
        val tool = registry.get(toolId)
        if (tool == null) {
            return finish(
                input, session, AgentState.REJECTED, AgentRoute.LOCAL_TOOL, parsedOutput, emptyList(), null,
                ErrorCode.UNKNOWN_TOOL.code, "未知工具：$toolId", null, false, startMs, -1,
                validJson = false, schemaPassed = false, toolExecuted = false
            )
        }
        val values = validator.applyDefaultsAndNormalize(tool, jsonArgsToValues(intent.arguments))

        // Idempotency check: same (requestId, toolId, args) is never re-executed.
        val key = idempotencyGuard.keyFor(input.requestId, toolId, values)
        val cached = idempotencyGuard.find(key)
        if (cached != null) {
            emitLifecycle(ToolLifecyclePhase.REPORTED, input.requestId, toolId, "replayed")
            return finish(
                input, session, AgentState.SUCCEEDED, AgentRoute.LOCAL_TOOL, parsedOutput, emptyList(), cached,
                null, "${cached.message}（重复请求，已返回上次执行结果）", null, replayed = true, startMs,
                modelLatencyMs = -1, validJson = false, schemaPassed = true, toolExecuted = false
            )
        }

        emitLifecycle(ToolLifecyclePhase.AUTHORIZED, input.requestId, toolId)
        record(input, session, AgentState.AUTHORIZED)
        record(input, session, AgentState.EXECUTING)
        val exec = toolExecutor.execute(
            toolId = toolId,
            arguments = values,
            context = ExecutionContext(input.requestId, session.sessionId, input.source)
        )
        idempotencyGuard.record(key, exec)
        session.clearPendingTask()

        val state = when (exec.status) {
            ExecutionStatus.SUCCEEDED -> AgentState.SUCCEEDED
            ExecutionStatus.FAILED -> AgentState.FAILED
            ExecutionStatus.TIMEOUT -> AgentState.TIMEOUT
        }
        val errorCode = if (exec.status == ExecutionStatus.SUCCEEDED) {
            null
        } else {
            exec.errorCode ?: ErrorCode.EXECUTION_FAILED.code
        }
        return finish(
            input, session, state, AgentRoute.LOCAL_TOOL, parsedOutput, emptyList(), exec,
            errorCode, exec.message, null, false, startMs, -1,
            validJson = false, schemaPassed = true, toolExecuted = true
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun mergePending(session: Session, output: AgentOutput): AgentOutput {
        val pending = session.pendingTask ?: return output
        val intent = output.intents.firstOrNull() ?: return output
        if (pending.needConfirmation) return output
        if (intent.toolId == pending.intent.toolId) {
            val mergedArgs = LinkedHashMap(pending.intent.arguments)
            mergedArgs.putAll(intent.arguments)
            return output.copy(intents = listOf(intent.copy(arguments = mergedArgs)))
        }
        // User changed target: drop the stale pending task.
        session.clearPendingTask()
        return output
    }

    private fun validateIntents(
        input: AgentInput,
        session: Session,
        output: AgentOutput
    ): List<ToolValidationIssue> {
        val issues = mutableListOf<ToolValidationIssue>()
        for (intent in output.intents) {
            emitLifecycle(ToolLifecyclePhase.RETRIEVED, input.requestId, intent.toolId)
            emitLifecycle(ToolLifecyclePhase.SELECTED, input.requestId, intent.toolId)
            validator.validateToolId(intent.toolId)?.let { issues += it }
            val tool = registry.get(intent.toolId) ?: continue
            issues += validator.validateArguments(tool, jsonArgsToValues(intent.arguments))
            if (issues.none { it.toolId == intent.toolId }) {
                emitLifecycle(ToolLifecyclePhase.VALIDATED, input.requestId, intent.toolId)
            }
        }
        return issues
    }

    private fun vehicleState(): VehicleStateSnapshot? = vehicleStateProvider?.snapshot()

    private fun mapModelError(kind: ModelErrorKind): ErrorCode = when (kind) {
        ModelErrorKind.RESPONSE_PARSE_ERROR -> ErrorCode.MODEL_RESPONSE_PARSE
        else -> ErrorCode.MODEL_UNAVAILABLE
    }

    private fun record(
        input: AgentInput,
        session: Session,
        state: AgentState,
        route: String? = null,
        latencyMs: Long = -1,
        errorCode: String? = null,
        toolId: String? = null,
        toolStatus: String? = null
    ) {
        telemetryRecorder?.record(
            TelemetryRecord(
                requestId = input.requestId,
                sessionId = session.sessionId,
                state = state.name,
                route = route,
                latencyMs = latencyMs,
                errorCode = errorCode,
                toolId = toolId,
                toolStatus = toolStatus
            )
        )
    }

    private fun emitLifecycle(
        phase: ToolLifecyclePhase,
        requestId: String,
        toolId: String,
        message: String? = null
    ) {
        lifecycleListener?.onToolLifecycle(
            ToolLifecycleEvent(phase, requestId, toolId, message)
        )
    }

    private fun finish(
        input: AgentInput,
        session: Session,
        state: AgentState,
        route: AgentRoute?,
        parsedOutput: AgentOutput?,
        validationIssues: List<ToolValidationIssue>,
        executionResult: ToolExecutionResult?,
        errorCode: String?,
        responseText: String,
        rawModelContent: String?,
        replayed: Boolean,
        startMs: Long,
        modelLatencyMs: Long,
        validJson: Boolean,
        schemaPassed: Boolean,
        toolExecuted: Boolean
    ): AgentResult {
        if (responseText.isNotBlank()) {
            session.appendAssistant(responseText)
        }
        record(
            input, session, state,
            route = route?.name,
            errorCode = errorCode,
            toolId = executionResult?.toolId,
            toolStatus = executionResult?.status?.name
        )
        return AgentResult(
            requestId = input.requestId,
            sessionId = session.sessionId,
            state = state,
            route = route,
            parsedOutput = parsedOutput,
            validationIssues = validationIssues,
            executionResult = executionResult,
            errorCode = errorCode,
            responseText = responseText,
            rawModelContent = rawModelContent,
            replayed = replayed,
            telemetry = RequestTelemetry(
                requestId = input.requestId,
                modelLatencyMs = modelLatencyMs,
                totalLatencyMs = System.currentTimeMillis() - startMs,
                route = route?.name,
                toolExecuted = toolExecuted,
                validJson = validJson,
                schemaPassed = schemaPassed
            )
        )
    }
}
