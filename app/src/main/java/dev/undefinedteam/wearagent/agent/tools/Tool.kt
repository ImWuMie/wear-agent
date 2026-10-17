package dev.undefinedteam.wearagent.agent.tools

import dev.undefinedteam.wearagent.agent.ToolCall
import dev.undefinedteam.wearagent.agent.ToolDefinition
import dev.undefinedteam.wearagent.agent.TranscriptItem
import dev.undefinedteam.wearagent.agent.tools.web.FetchTool
import dev.undefinedteam.wearagent.agent.tools.web.WebSearchTool
import dev.undefinedteam.wearagent.session.AgentSettings
import dev.undefinedteam.wearagent.session.ToolSetting

/**
 * A model-callable tool. The loop dispatches by [definition]'s name, offers only
 * tools whose [enabledBy] returns true, and turns a thrown failure into a
 * model-facing error result while cancellation stays an exception it can act on.
 */
interface Tool {
    val definition: ToolDefinition

    /** Null when the tool is always available. */
    val setting: ToolSetting? get() = null

    fun enabledBy(settings: AgentSettings): Boolean = true

    /** Cancellation stays an exception; see the failure rule above. */
    fun execute(call: ToolCall): TranscriptItem.ToolResult

    /** Sticky: later executions in this turn stay canceled. */
    fun cancel()

    /** Header of the tool block. The tool knows its own argument shape; the default is the raw arguments. */
    fun summary(call: ToolCall): String = call.arguments.ifBlank { call.name }
}

/** One shared set: tools are stateless for summarising. */
private val summaryTools: List<Tool> by lazy { defaultTools() }

/** Summary of a call replayed from a stored transcript, dispatched by name. */
fun toolSummary(call: ToolCall): String =
    summaryTools.firstOrNull { it.definition.name == call.name }?.summary(call)
        ?: call.arguments.ifBlank { call.name }

/** Every tool this app ships; Settings renders a toggle for each one that declares a [ToolSetting]. */
fun defaultTools(): List<Tool> = listOf(WebSearchTool(), FetchTool())
