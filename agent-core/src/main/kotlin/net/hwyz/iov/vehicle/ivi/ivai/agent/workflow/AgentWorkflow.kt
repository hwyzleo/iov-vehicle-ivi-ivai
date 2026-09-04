package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.util.UUID
import net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState
import net.hwyz.iov.vehicle.ivi.ivai.agent.error.ErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEventListener
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.ParsedIntentSummary
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.ParsedOutputSummary
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.ToolDebugInfo
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.TurnDebugInfo
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentOutput
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentRoute
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.Intent
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.jsonArgsToValues
import net.hwyz.iov.vehicle.ivi.ivai.agent.policy.AgentPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.agent.policy.PolicyOutcome
import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptBuilder
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.RouteDecision
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.Router
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.PendingTask
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.model.AgentPerformanceMetrics
import net.hwyz.iov.vehicle.ivi.ivai.model.ChatMessage
import net.hwyz.iov.vehicle.ivi.ivai.model.HttpNetworkMetrics
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelClientException
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelErrorKind
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelResponse
import net.hwyz.iov.vehicle.ivi.ivai.model.PerformanceMetricsValidator
import net.hwyz.iov.vehicle.ivi.ivai.model.ProviderComputeMetrics
import net.hwyz.iov.vehicle.ivi.ivai.model.StreamingModelProvider
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
    val source: String = "unknown",
    val turnId: String = requestId,
    /** Monotonic submission instant; [AgentPerformanceMetrics.endToEndMs] starts here. */
    val submittedAtNs: Long = System.nanoTime()
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
    val performance: AgentPerformanceMetrics? = null,
    val telemetry: RequestTelemetry
)

/**
 * Accumulates non-overlapping top-level phase durations for one turn
 * (IVI-IVAI-DSN-CR-004). All values in milliseconds from a monotonic clock.
 * [network] / [providerCompute] are diagnostic sub-metrics inside modelCallTotal
 * and are never re-added.
 */
private class TurnTimings(val submittedAtNs: Long) {
    var queueMs: Long? = null
    var contextAndPromptMs: Long? = null
    var modelCallTotalMs: Long? = null
    var network: HttpNetworkMetrics? = null
    var providerCompute: ProviderComputeMetrics? = null
    var parseAndSchemaMs: Long? = null
    var routeAndPolicyMs: Long? = null
    var toolExecutionMs: Long? = null
    var eventDispatchMs: Long? = null
    var timeToFirstTokenMs: Long? = null
    var streamingUsed: Boolean? = null
    var endToEndMs: Long = 0

    fun addRouteAndPolicy(durationMs: Long) {
        routeAndPolicyMs = (routeAndPolicyMs ?: 0L) + durationMs
    }
}

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
    private val idempotencyGuard: IdempotencyGuard = IdempotencyGuard(),
    private val eventListener: AgentEventListener? = null
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun process(input: AgentInput, session: Session): AgentResult {
        val turnStartNs = System.nanoTime()
        val timings = TurnTimings(input.submittedAtNs)
        timings.queueMs = msBetween(input.submittedAtNs, turnStartNs)

        record(input, session, AgentState.RECEIVED)
        session.appendUser(input.text)
        emit(AgentEvent.UserSubmitted(session.sessionId, input.turnId, input.requestId, input.text))
        emit(AgentEvent.ProcessingStarted(session.sessionId, input.turnId, input.requestId))

        // --- confirmation approval path (skip the model) ---
        val pending = session.pendingTask
        if (pending != null && pending.needConfirmation && input.text.trim() == config.confirmationKeyword) {
            session.consumePendingTask()
            return executeAuthorized(input, session, listOf(pending.intent), parsedOutput = null, timings)
        }

        // --- model call ---
        record(input, session, AgentState.ROUTED)
        record(input, session, AgentState.MODEL_REQUESTED)
        val contextStartNs = System.nanoTime()
        val composedMessages = promptBuilder.build(session, input, vehicleState())
        timings.contextAndPromptMs = msSince(contextStartNs)

        val modelStartNs = System.nanoTime()
        val modelResponse: ModelResponse = try {
            callModel(input, session, composedMessages, timings)
        } catch (e: ModelClientException) {
            timings.modelCallTotalMs = msSince(modelStartNs)
            val code = mapModelError(e.kind)
            emit(
                AgentEvent.TurnFailed(
                    session.sessionId, input.turnId, input.requestId,
                    message = "模型服务暂不可用，请稍后重试", errorCode = code.code, retryable = true
                )
            )
            return finish(
                input, session, AgentState.FAILED, null, null, emptyList(), null,
                code.code, "模型服务暂不可用，请稍后重试", null, false, timings,
                validJson = false, schemaPassed = false, toolExecuted = false,
                errorDetail = "模型调用失败：${e.message}"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            timings.modelCallTotalMs = msSince(modelStartNs)
            emit(
                AgentEvent.TurnFailed(
                    session.sessionId, input.turnId, input.requestId,
                    message = "模型服务暂不可用，请稍后重试",
                    errorCode = ErrorCode.MODEL_UNAVAILABLE.code, retryable = true
                )
            )
            return finish(
                input, session, AgentState.FAILED, null, null, emptyList(), null,
                ErrorCode.MODEL_UNAVAILABLE.code, "模型服务暂不可用，请稍后重试", null, false, timings,
                validJson = false, schemaPassed = false, toolExecuted = false,
                errorDetail = "模型调用异常：${e.message}"
            )
        }
        timings.modelCallTotalMs = msSince(modelStartNs)
        timings.network = modelResponse.network
        timings.providerCompute = modelResponse.providerCompute
        timings.timeToFirstTokenMs = modelResponse.timeToFirstTokenMs
        record(input, session, AgentState.MODEL_RESPONDED, latencyMs = modelResponse.latencyMs)

        // --- second-level JSON parse ---
        val parseStartNs = System.nanoTime()
        val contentJson = modelResponse.contentJson
        if (contentJson == null) {
            timings.parseAndSchemaMs = msSince(parseStartNs)
            emit(
                AgentEvent.TurnFailed(
                    session.sessionId, input.turnId, input.requestId,
                    message = "暂时无法理解你的请求，请稍后再试",
                    errorCode = ErrorCode.MODEL_RESPONSE_PARSE.code, retryable = true
                )
            )
            return finish(
                input, session, AgentState.FAILED, null, null, emptyList(), null,
                ErrorCode.MODEL_RESPONSE_PARSE.code, "模型响应无法解析为 JSON", modelResponse.content,
                false, timings, validJson = false, schemaPassed = false, toolExecuted = false
            )
        }
        record(input, session, AgentState.PARSED)

        // --- top-level output schema check ---
        val output: AgentOutput = try {
            json.decodeFromJsonElement(AgentOutput.serializer(), contentJson)
        } catch (e: Exception) {
            timings.parseAndSchemaMs = msSince(parseStartNs)
            emit(
                AgentEvent.TurnFailed(
                    session.sessionId, input.turnId, input.requestId,
                    message = "暂时无法安全理解该请求，请换一种说法",
                    errorCode = ErrorCode.OUTPUT_SCHEMA.code, retryable = false
                )
            )
            return finish(
                input, session, AgentState.FAILED, null, null, emptyList(), null,
                ErrorCode.OUTPUT_SCHEMA.code, "结构化输出不符合 Schema：${e.message}", modelResponse.content,
                false, timings, validJson = true, schemaPassed = false, toolExecuted = false
            )
        }
        timings.parseAndSchemaMs = msSince(parseStartNs)

        // --- route decision ---
        val routeStartNs = System.nanoTime()
        val routeDecision = router.resolve(output)
        if (routeDecision is RouteDecision.Unsafe) {
            timings.addRouteAndPolicy(msSince(routeStartNs))
            emit(
                AgentEvent.TurnFailed(
                    session.sessionId, input.turnId, input.requestId,
                    message = "暂时无法安全处理该请求",
                    errorCode = ErrorCode.ROUTE_UNSAFE.code, retryable = false
                )
            )
            return finish(
                input, session, AgentState.REJECTED, null, output, emptyList(), null,
                ErrorCode.ROUTE_UNSAFE.code, routeDecision.message, modelResponse.content,
                false, timings, validJson = true, schemaPassed = true, toolExecuted = false
            )
        }
        val route = (routeDecision as RouteDecision.Safe).route
        session.setRoute(route)

        return when (route) {
            AgentRoute.LOCAL_DIALOGUE -> handleDialogue(input, session, output, timings, modelResponse)
            AgentRoute.CLOUD_AI -> {
                timings.addRouteAndPolicy(msSince(routeStartNs))
                emit(
                    AgentEvent.Reply(
                        session.sessionId, input.turnId, input.requestId,
                        text = "该请求需要云端 AI 处理（预留功能，暂不执行）。"
                    )
                )
                finish(
                    input, session, AgentState.CLOUD_REQUIRED, route, output, emptyList(), null, null,
                    "该请求需要云端 AI 处理（预留功能，暂不执行）。", modelResponse.content, false, timings,
                    validJson = true, schemaPassed = true, toolExecuted = false
                )
            }
            AgentRoute.REJECT -> {
                timings.addRouteAndPolicy(msSince(routeStartNs))
                emit(
                    AgentEvent.Reply(
                        session.sessionId, input.turnId, input.requestId,
                        text = "已拒绝该请求。"
                    )
                )
                finish(
                    input, session, AgentState.REJECTED, route, output, emptyList(), null, null,
                    "已拒绝该请求。", modelResponse.content, false, timings,
                    validJson = true, schemaPassed = true, toolExecuted = false
                )
            }
            AgentRoute.LOCAL_TOOL -> handleLocalTool(input, session, output, timings, modelResponse, routeStartNs)
        }
    }

    /**
     * Calls the model, streaming when the provider supports it and
     * [AgentConfig.streamingEnabled] is on: forwards each accumulated delta as a
     * [AgentEvent.StreamingDelta] (rendered into the processing bubble) while
     * still returning the fully accumulated response for parse / validate.
     */
    private suspend fun callModel(
        input: AgentInput,
        session: Session,
        composedMessages: List<ChatMessage>,
        timings: TurnTimings
    ): ModelResponse {
        val request = ModelRequest(
            requestId = input.requestId,
            model = config.model,
            messages = composedMessages,
            timeoutMs = config.requestTimeoutMs
        )
        if (!config.streamingEnabled) {
            timings.streamingUsed = false
            return modelProvider.generate(request)
        }
        val streaming = modelProvider as? StreamingModelProvider
        if (streaming == null) {
            timings.streamingUsed = false
            return modelProvider.generate(request)
        }
        timings.streamingUsed = true
        val sb = StringBuilder()
        return streaming.generateStreaming(request) { delta ->
            sb.append(delta)
            emit(AgentEvent.StreamingDelta(session.sessionId, input.turnId, input.requestId, sb.toString()))
        }
    }

    // ------------------------------------------------------------------ confirmation / cancellation

    /**
     * Approves a pending confirmation (IVI-IVAI-DSN-CR-002). Idempotent: only the
     * pending confirmation whose id matches is executed; a missing, stale or
     * mismatched confirmationId is rejected WITHOUT executing the tool.
     */
    suspend fun confirm(confirmationId: String, session: Session): AgentResult {
        val pending = session.pendingTask
        if (pending == null || !pending.needConfirmation || pending.confirmationId != confirmationId) {
            return rejectConfirmation(session, "确认已失效或已被处理")
        }
        val input = pendingInput(pending, confirmationId)
        session.consumePendingTask()
        return executeAuthorized(
            input, session, listOf(pending.intent), parsedOutput = null,
            timings = TurnTimings(input.submittedAtNs)
        )
    }

    /**
     * Cancels a pending confirmation (IVI-IVAI-DSN-CR-002). No tool is ever executed;
     * stale / mismatched ids are rejected without side effects.
     */
    suspend fun cancel(confirmationId: String, session: Session): AgentResult {
        val pending = session.pendingTask
        if (pending == null || !pending.needConfirmation || pending.confirmationId != confirmationId) {
            return rejectConfirmation(session, "确认已失效或已被处理")
        }
        val input = pendingInput(pending, confirmationId)
        val text = "已取消「${pending.toolName ?: pending.intent.toolId}」。"
        session.consumePendingTask()
        session.appendAssistant(text)
        emit(AgentEvent.TurnCancelled(session.sessionId, input.turnId, input.requestId, text))
        record(input, session, AgentState.REJECTED, route = AgentRoute.LOCAL_TOOL.name)
        val timings = TurnTimings(input.submittedAtNs)
        timings.endToEndMs = msSince(input.submittedAtNs)
        return AgentResult(
            requestId = input.requestId,
            sessionId = session.sessionId,
            state = AgentState.REJECTED,
            route = AgentRoute.LOCAL_TOOL,
            responseText = text,
            telemetry = RequestTelemetry(requestId = input.requestId, route = AgentRoute.LOCAL_TOOL.name)
        )
    }

    private fun pendingInput(pending: PendingTask, confirmationId: String): AgentInput {
        val requestId = pending.requestId ?: confirmationId
        return AgentInput(
            requestId = requestId,
            text = config.confirmationKeyword,
            source = "confirmation",
            turnId = pending.turnId ?: requestId
        )
    }

    private fun rejectConfirmation(session: Session, message: String): AgentResult {
        val requestId = UUID.randomUUID().toString()
        return AgentResult(
            requestId = requestId,
            sessionId = session.sessionId,
            state = AgentState.REJECTED,
            responseText = message,
            telemetry = RequestTelemetry(requestId = requestId)
        )
    }

    private fun emit(event: AgentEvent) {
        eventListener?.onAgentEvent(event)
    }

    // ------------------------------------------------------------------ branches

    private suspend fun handleDialogue(
        input: AgentInput,
        session: Session,
        output: AgentOutput,
        timings: TurnTimings,
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
        emit(AgentEvent.Reply(session.sessionId, input.turnId, input.requestId, text))
        return finish(
            input, session, AgentState.WAITING_USER, AgentRoute.LOCAL_DIALOGUE, output, emptyList(), null, null,
            text, modelResponse.content, false, timings,
            validJson = true, schemaPassed = true, toolExecuted = false
        )
    }

    private suspend fun handleLocalTool(
        input: AgentInput,
        session: Session,
        output: AgentOutput,
        timings: TurnTimings,
        modelResponse: ModelResponse,
        routeStartNs: Long
    ): AgentResult {
        // Restore / merge a pending missing-argument task with the same tool.
        val merged = mergePending(session, output)
        val policyStartNs = System.nanoTime()

        // Whitelist + parameter schema validation.
        val issues = validateIntents(input, session, merged)
        record(input, session, AgentState.VALIDATED)
        if (issues.isNotEmpty()) {
            timings.addRouteAndPolicy(msSince(policyStartNs))
            val unknownTool = issues.any { it.kind == ValidationIssueKind.UNKNOWN_TOOL }
            val errorCode = if (unknownTool) ErrorCode.UNKNOWN_TOOL else ErrorCode.INVALID_ARGUMENT
            emitLifecycle(ToolLifecyclePhase.FAILED, input.requestId, issues.first().toolId.orEmpty(), issues.first().message)
            emit(
                AgentEvent.TurnFailed(
                    session.sessionId, input.turnId, input.requestId,
                    message = "暂时无法安全理解该请求，请换一种说法",
                    errorCode = errorCode.code, retryable = false
                )
            )
            return finish(
                input, session, AgentState.REJECTED, AgentRoute.LOCAL_TOOL, merged, issues, null,
                errorCode.code, issues.first().message, modelResponse.content, false, timings,
                validJson = true, schemaPassed = true, toolExecuted = false
            )
        }

        // Policy (risk / precondition / confirmation).
        val outcome = agentPolicy.evaluate(merged.intents, output, vehicleState(), confirmationGranted = false)
        timings.addRouteAndPolicy(msSince(policyStartNs))
        when (outcome) {
            is PolicyOutcome.NeedsConfirmation -> {
                val intent = merged.intents.first()
                val confirmationId = UUID.randomUUID().toString()
                val name = registry.get(intent.toolId)?.name ?: intent.toolId
                session.storePendingTask(
                    intent, needConfirmation = true, missingArguments = emptyList(),
                    confirmationId = confirmationId, toolName = name,
                    requestId = input.requestId, turnId = input.turnId
                )
                record(input, session, AgentState.NEED_DIALOGUE)
                record(input, session, AgentState.WAITING_USER)
                emit(
                    AgentEvent.ConfirmationRequired(
                        session.sessionId, input.turnId, input.requestId,
                        confirmationId = confirmationId, toolId = intent.toolId, toolName = name,
                        text = "确认执行「$name」？"
                    )
                )
                return finish(
                    input, session, AgentState.WAITING_USER, AgentRoute.LOCAL_TOOL, merged, emptyList(), null, null,
                    "确认执行「$name」？回复「${config.confirmationKeyword}」以继续。", modelResponse.content, false,
                    timings, validJson = true, schemaPassed = true, toolExecuted = false
                )
            }
            is PolicyOutcome.Denied -> {
                emitLifecycle(ToolLifecyclePhase.FAILED, input.requestId, merged.intents.first().toolId, outcome.message)
                emit(
                    AgentEvent.TurnFailed(
                        session.sessionId, input.turnId, input.requestId,
                        message = "该操作未获授权，无法执行",
                        errorCode = outcome.errorCode.code, retryable = false
                    )
                )
                return finish(
                    input, session, AgentState.REJECTED, AgentRoute.LOCAL_TOOL, merged, emptyList(), null,
                    outcome.errorCode.code, outcome.message, modelResponse.content, false, timings,
                    validJson = true, schemaPassed = true, toolExecuted = false
                )
            }
            PolicyOutcome.Authorized -> {
                session.clearPendingTask()
                return executeAuthorized(
                    input, session, merged.intents, merged, timings,
                    modelLatencyMs = modelResponse.latencyMs, rawModelContent = modelResponse.content
                )
            }
        }
    }

    private suspend fun executeAuthorized(
        input: AgentInput,
        session: Session,
        intents: List<Intent>,
        parsedOutput: AgentOutput?,
        timings: TurnTimings,
        modelLatencyMs: Long = -1,
        rawModelContent: String? = null
    ): AgentResult {
        val intent = intents.first()
        val toolId = intent.toolId
        val tool = registry.get(toolId)
        if (tool == null) {
            emit(
                AgentEvent.TurnFailed(
                    session.sessionId, input.turnId, input.requestId,
                    message = "无法执行：未知工具 $toolId",
                    errorCode = ErrorCode.UNKNOWN_TOOL.code, retryable = false
                )
            )
            return finish(
                input, session, AgentState.REJECTED, AgentRoute.LOCAL_TOOL, parsedOutput, emptyList(), null,
                ErrorCode.UNKNOWN_TOOL.code, "未知工具：$toolId", rawModelContent, false, timings,
                validJson = false, schemaPassed = false, toolExecuted = false
            )
        }
        val values = validator.applyDefaultsAndNormalize(tool, jsonArgsToValues(intent.arguments))
        val toolName = tool.name ?: toolId

        // Idempotency check: same (requestId, toolId, args) is never re-executed.
        val key = idempotencyGuard.keyFor(input.requestId, toolId, values)
        val cached = idempotencyGuard.find(key)
        if (cached != null) {
            emitLifecycle(ToolLifecyclePhase.REPORTED, input.requestId, toolId, "replayed")
            emit(
                AgentEvent.ToolExecutionFinished(
                    session.sessionId, input.turnId, input.requestId, toolId,
                    status = cached.status,
                    message = "${cached.message}（重复请求，已返回上次执行结果）",
                    errorCode = cached.errorCode,
                    retryable = cached.status != ExecutionStatus.SUCCEEDED
                )
            )
            return finish(
                input, session, AgentState.SUCCEEDED, AgentRoute.LOCAL_TOOL, parsedOutput, emptyList(), cached,
                null, "${cached.message}（重复请求，已返回上次执行结果）", rawModelContent, replayed = true, timings,
                validJson = false, schemaPassed = true, toolExecuted = false
            )
        }

        emitLifecycle(ToolLifecyclePhase.AUTHORIZED, input.requestId, toolId)
        record(input, session, AgentState.AUTHORIZED)
        record(input, session, AgentState.EXECUTING)
        emit(
            AgentEvent.ToolExecutionStarted(
                session.sessionId, input.turnId, input.requestId, toolId,
                text = "正在执行「$toolName」…"
            )
        )
        val execStartNs = System.nanoTime()
        val exec = toolExecutor.execute(
            toolId = toolId,
            arguments = values,
            context = ExecutionContext(input.requestId, session.sessionId, input.source)
        )
        val toolLatencyMs = msSince(execStartNs)
        timings.toolExecutionMs = toolLatencyMs
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
        emit(
            AgentEvent.ToolExecutionFinished(
                session.sessionId, input.turnId, input.requestId, toolId,
                status = exec.status,
                message = exec.message,
                errorCode = errorCode,
                retryable = exec.status != ExecutionStatus.SUCCEEDED
            )
        )
        return finish(
            input, session, state, AgentRoute.LOCAL_TOOL, parsedOutput, emptyList(), exec,
            errorCode, exec.message, rawModelContent, false, timings,
            validJson = false, schemaPassed = true, toolExecuted = true, toolLatencyMs = toolLatencyMs
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
        toolStatus: String? = null,
        detail: String? = null
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
                toolStatus = toolStatus,
                detail = detail
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
        timings: TurnTimings,
        validJson: Boolean,
        schemaPassed: Boolean,
        toolExecuted: Boolean,
        toolLatencyMs: Long? = null,
        errorDetail: String? = null
    ): AgentResult {
        if (responseText.isNotBlank()) {
            session.appendAssistant(responseText)
        }
        record(
            input, session, state,
            route = route?.name,
            errorCode = errorCode,
            toolId = executionResult?.toolId,
            toolStatus = executionResult?.status?.name,
            detail = errorDetail
        )

        timings.endToEndMs = msSince(input.submittedAtNs)
        val performance = AgentPerformanceMetrics(
            requestId = input.requestId,
            queueMs = timings.queueMs,
            contextAndPromptMs = timings.contextAndPromptMs,
            modelCallTotalMs = timings.modelCallTotalMs,
            network = timings.network,
            providerCompute = timings.providerCompute,
            timeToFirstTokenMs = timings.timeToFirstTokenMs,
            streamingUsed = timings.streamingUsed,
            parseAndSchemaMs = timings.parseAndSchemaMs,
            routeAndPolicyMs = timings.routeAndPolicyMs,
            toolExecutionMs = timings.toolExecutionMs,
            eventDispatchMs = timings.eventDispatchMs,
            endToEndMs = timings.endToEndMs,
            unattributedMs = PerformanceMetricsValidator.unattributedMs(
                timings.endToEndMs, timings.queueMs, timings.contextAndPromptMs,
                timings.modelCallTotalMs, timings.parseAndSchemaMs, timings.routeAndPolicyMs,
                timings.toolExecutionMs, timings.eventDispatchMs
            )
        )

        val dispatchStartNs = System.nanoTime()
        emitDebugInfo(input, session, state, route, parsedOutput, executionResult, errorCode,
            rawModelContent, replayed, performance, toolLatencyMs)
        timings.eventDispatchMs = msSince(dispatchStartNs)
        // The emitted DebugInfo already carries the pre-dispatch performance; fold the
        // measured dispatch time into the returned result (event dispatch is trivial in-process).
        val performanceFinal = performance.copy(eventDispatchMs = timings.eventDispatchMs)

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
            performance = performanceFinal,
            telemetry = RequestTelemetry(
                requestId = input.requestId,
                modelLatencyMs = timings.modelCallTotalMs ?: -1,
                totalLatencyMs = timings.endToEndMs,
                route = route?.name,
                toolExecuted = toolExecuted,
                validJson = validJson,
                schemaPassed = schemaPassed
            )
        )
    }

    private fun emitDebugInfo(
        input: AgentInput,
        session: Session,
        state: AgentState,
        route: AgentRoute?,
        parsedOutput: AgentOutput?,
        executionResult: ToolExecutionResult?,
        errorCode: String?,
        rawModelContent: String?,
        replayed: Boolean,
        performance: AgentPerformanceMetrics?,
        toolLatencyMs: Long?
    ) {
        emit(
            AgentEvent.DebugInfo(
                session.sessionId, input.turnId, input.requestId,
                debug = TurnDebugInfo(
                    turnId = input.turnId,
                    requestId = input.requestId,
                    performance = performance,
                    parsed = parsedOutput?.let {
                        ParsedOutputSummary(
                            route = it.route,
                            confidence = it.modelConfidence,
                            riskLevel = it.riskLevel,
                            needConfirmation = it.needConfirmation,
                            missingArguments = it.missingArguments,
                            reasonCode = it.reasonCode,
                            intents = it.intents.map { intent ->
                                ParsedIntentSummary(
                                    toolId = intent.toolId,
                                    functionId = intent.functionId,
                                    arguments = intent.arguments.mapValues { (_, value) -> value.toString() }
                                )
                            }
                        )
                    },
                    tool = executionResult?.let {
                        ToolDebugInfo(
                            toolId = it.toolId,
                            status = it.status.name,
                            message = it.message,
                            errorCode = it.errorCode,
                            latencyMs = toolLatencyMs
                        )
                    },
                    state = state.name,
                    route = route?.name,
                    errorCode = errorCode,
                    replayed = replayed
                )
            )
        )
    }

    private fun msSince(startNs: Long): Long = (System.nanoTime() - startNs) / NANOS_PER_MILLI

    private fun msBetween(startNs: Long, endNs: Long): Long = (endNs - startNs) / NANOS_PER_MILLI

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
