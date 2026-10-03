package dev.undefinedteam.wearagent.agent

/**
 * One turn of provider history.
 *
 * TODO(tools): harness
 * tool execution (formerly `Harness`) and the multi-round tool loop in
 * `AgentService.runTurn`.
 */
sealed class TranscriptItem {
    data class Text(val fromUser: Boolean, val text: String) : TranscriptItem()
}
