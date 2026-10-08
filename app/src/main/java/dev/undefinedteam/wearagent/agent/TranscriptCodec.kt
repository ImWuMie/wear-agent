package dev.undefinedteam.wearagent.agent

import dev.undefinedteam.wearagent.session.ApiKind
import org.json.JSONArray
import org.json.JSONObject

/** Keeps tool requests, results, and provider-native reasoning together in a session record. */
object TranscriptCodec {
    fun encode(items: List<TranscriptItem>): JSONArray = JSONArray().apply {
        for (item in items) {
            put(when (item) {
                is TranscriptItem.Text -> JSONObject()
                    .put("type", "text")
                    .put("fromUser", item.fromUser)
                    .put("text", item.text)
                is TranscriptItem.Assistant -> JSONObject()
                    .put("type", "assistant")
                    .put("text", item.text)
                    .put("provider", item.providerKind.name)
                    .put("providerContent", item.providerContent)
                    .put("calls", JSONArray().apply {
                        for (call in item.toolCalls) {
                            put(JSONObject()
                                .put("id", call.id)
                                .put("name", call.name)
                                .put("arguments", call.arguments))
                        }
                    })
                is TranscriptItem.ToolResult -> JSONObject()
                    .put("type", "tool_result")
                    .put("callId", item.callId)
                    .put("content", item.content)
                    .put("isError", item.isError)
            })
        }
    }

    fun decode(items: JSONArray?): List<TranscriptItem> {
        if (items == null) return emptyList()
        return List(items.length()) { index ->
            val item = items.getJSONObject(index)
            when (item.getString("type")) {
                "text" -> TranscriptItem.Text(item.getBoolean("fromUser"), item.getString("text"))
                "assistant" -> {
                    val calls = item.getJSONArray("calls")
                    TranscriptItem.Assistant(
                        text = item.getString("text"),
                        toolCalls = List(calls.length()) { callIndex ->
                            val call = calls.getJSONObject(callIndex)
                            ToolCall(call.getString("id"), call.getString("name"), call.getString("arguments"))
                        },
                        providerKind = ApiKind.fromStored(item.getString("provider")),
                        providerContent = item.optString("providerContent"),
                    )
                }
                "tool_result" -> TranscriptItem.ToolResult(
                    item.getString("callId"), item.getString("content"), item.optBoolean("isError"),
                )
                else -> error("Unknown transcript item type")
            }
        }
    }
}
