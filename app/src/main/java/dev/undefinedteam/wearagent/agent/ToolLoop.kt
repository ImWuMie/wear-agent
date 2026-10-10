package dev.undefinedteam.wearagent.agent

import dev.undefinedteam.wearagent.agent.tools.WebSearchTool
import dev.undefinedteam.wearagent.session.AgentSettings
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/** One cancellable turn. Each assistant tool request is paired before another model round. */
class ToolLoop(
    /** The endpoint kind picks the protocol implementation; resolved per turn. */
    private val providerFactory: (EndpointKind) -> ChatProvider =
        { kind -> ChatProvider(kind) },
    private val search: WebSearchTool = WebSearchTool(),
    private val maxToolRounds: Int = 6,
) {
    private val canceled = AtomicBoolean(false)
    private var provider: ChatProvider? = null

    init {
        require(maxToolRounds > 0)
    }

    fun cancel() {
        canceled.set(true)
        provider?.cancel()
        search.cancel()
    }

    data class Outcome(
        val state: TurnState,
        val transcript: List<TranscriptItem>,
        val tokens: Tokens?,
        val elapsedMs: Long,
        val canceled: Boolean,
    )

    fun run(
        settings: AgentSettings,
        history: List<TranscriptItem>,
        onUpdate: (TurnState) -> Unit = {},
    ): Outcome {
        val client = providerFactory(settings.endpointKind)
        provider = client
        val providerHistory = ArrayList<TranscriptItem>(history)
        val transcript = ArrayList<TranscriptItem>()
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var activities: List<ToolActivity> = emptyList()
        var prompt = 0
        var cached = 0
        var completion = 0
        var hasUsage = false
        var elapsedMs = 0L
        var toolRounds = 0
        var executions = 0

        fun publish() {
            onUpdate(
                TurnState(
                    running = true,
                    text = text.toString(),
                    reasoning = reasoning.toString(),
                    tools = activities,
                )
            )
        }

        fun finish(error: String? = null): Outcome = Outcome(
            state = TurnState(
                text = text.toString(),
                reasoning = reasoning.toString(),
                error = error,
                tools = activities,
            ),
            transcript = transcript.toList(),
            tokens = if (hasUsage) Tokens(prompt, cached, completion) else null,
            elapsedMs = elapsedMs,
            canceled = canceled.get(),
        )

        val definitions =
            if (settings.webSearchEnabled) listOf(WebSearchTool.DEFINITION) else emptyList()

        while (!canceled.get()) {
            val roundText = StringBuilder()
            val availableTools = if (toolRounds < maxToolRounds && executions < MAX_TOOL_CALLS) {
                definitions
            } else emptyList()
            val outcome = try {
                client.stream(
                    ChatRequest(
                        endpoint = settings.endpoint,
                        model = settings.model,
                        apiKey = settings.apiKey,
                    ),
                    providerHistory,
                    tools = availableTools,
                    onDelta = { delta: MessageDelta ->
                        if (delta.text.isNotEmpty()) {
                            if (roundText.isEmpty() && text.isNotEmpty()) text.append("\n\n")
                            roundText.append(delta.text)
                            text.append(delta.text)
                        }
                        if (delta.reasoning.isNotEmpty()) reasoning.append(delta.reasoning)
                        publish()
                    },
                )
            } catch (error: Exception) {
                if (roundText.isNotBlank()) {
                    transcript.add(TranscriptItem.Text(false, roundText.toString()))
                }
                return finish(
                    if (canceled.get()) null else error.message ?: error.javaClass.simpleName
                )
            }
            outcome.tokens?.let {
                hasUsage = true
                prompt += it.prompt
                cached += it.cached
                completion += it.completion
            }
            elapsedMs += outcome.elapsedMs
            if (outcome.toolCalls.isEmpty() && roundText.isEmpty() &&
                (outcome.providerContent.isEmpty() || outcome.providerContent == "{}" ||
                        outcome.providerContent == "[]")
            ) {
                return finish()
            }
            val assistant = TranscriptItem.Assistant(
                text = roundText.toString(),
                toolCalls = outcome.toolCalls,
                endpointKind = settings.endpointKind,
                providerContent = outcome.providerContent,
            )
            transcript.add(assistant)
            providerHistory.add(assistant)
            if (outcome.toolCalls.isEmpty()) return finish()

            val exhausted = toolRounds >= maxToolRounds || executions >= MAX_TOOL_CALLS
            val firstActivity = activities.size
            activities = activities + outcome.toolCalls.map { ToolActivity(it) }
            publish()
            for ((index, call) in outcome.toolCalls.withIndex()) {
                val result = when {
                    canceled.get() -> TranscriptItem.ToolResult(call.id, CANCELED_RESULT, true)
                    exhausted || executions >= MAX_TOOL_CALLS ->
                        TranscriptItem.ToolResult(call.id, "Error: Tool call limit reached.", true)

                    !settings.webSearchEnabled ->
                        TranscriptItem.ToolResult(
                            call.id,
                            "Error: Web search is disabled in settings.",
                            true
                        )

                    else -> {
                        executions++
                        try {
                            search.execute(call)
                        } catch (error: InterruptedIOException) {
                            if (canceled.get()) {
                                TranscriptItem.ToolResult(call.id, CANCELED_RESULT, true)
                            } else {
                                TranscriptItem.ToolResult(
                                    call.id,
                                    "Error: ${error.message ?: "Search timed out."}",
                                    true
                                )
                            }
                        }
                    }
                }
                transcript.add(result)
                providerHistory.add(result)
                activities = activities.toMutableList().also {
                    it[firstActivity + index] = ToolActivity(call, result.content, result.isError)
                }
                publish()
            }
            if (canceled.get()) return finish()
            if (exhausted) return finish("Tool call limit reached; the model did not finish its answer.")
            toolRounds++
        }
        return finish()
    }

    companion object {
        private const val MAX_TOOL_CALLS = 12
        private const val CANCELED_RESULT = "Error: Tool execution canceled by the user."
    }
}
