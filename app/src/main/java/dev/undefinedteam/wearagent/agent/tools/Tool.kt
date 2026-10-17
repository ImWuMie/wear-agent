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
 *
 * [setting] is null for a tool that is always available; otherwise the tool gets
 * a toggle in Settings carrying that key, label and note.
 */
interface Tool {
    /** Name, description and JSON schema advertised to the model. */
    val definition: ToolDefinition

    /** Settings entry for this tool, or null when it needs no toggle. */
    val setting: ToolSetting? get() = null

    /** True when settings allow this tool to be offered in the current turn. */
    fun enabledBy(settings: AgentSettings): Boolean = true

    /** Runs one call, returning a model-facing result; see the failure rule above. */
    fun execute(call: ToolCall): TranscriptItem.ToolResult

    /** Cancels this turn's in-flight work; sticky for later executions. */
    fun cancel()

    /**
     * One-line human summary of a call, shown as the tool block's header. The
     * tool knows its own argument shape, so it decides what to surface; the
     * default falls back to the raw arguments.
     */
    fun summary(call: ToolCall): String = call.arguments.ifBlank { call.name }
}

/** Tool instances are stateless for summarising; one shared set avoids rebuilding them. */
private val summaryTools: List<Tool> by lazy { defaultTools() }

/**
 * Summary of a call replayed outside the tool loop (e.g. from a stored
 * transcript), dispatched to the owning tool by name.
 */
fun toolSummary(call: ToolCall): String =
    summaryTools.firstOrNull { it.definition.name == call.name }?.summary(call)
        ?: call.arguments.ifBlank { call.name }

/**
 * Every tool this app ships. Only these are offered to the model, and Settings
 * renders one toggle per tool that declares a [ToolSetting].
 */
fun defaultTools(): List<Tool> = listOf(WebSearchTool(), FetchTool())
