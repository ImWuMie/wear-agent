package dev.undefinedteam.wearagent.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * The JSON shape of one image inside a persisted record. Swapping the codec swaps
 * where the bytes live — inline base64, or a name pointing at a stored file —
 * without any other persistence code changing.
 */
interface ImageCodec {
    fun encode(image: ImageAttachment): JSONObject

    /** Null when the image cannot be resolved, e.g. its backing file is gone. */
    fun decode(json: JSONObject): ImageAttachment?
}

/** Self-contained base64: the default where no store is available. */
object InlineImages : ImageCodec {
    override fun encode(image: ImageAttachment): JSONObject = JSONObject()
        .put("mediaType", image.mediaType)
        .put("base64", image.base64)

    override fun decode(json: JSONObject): ImageAttachment? {
        val base64 = json.optString("base64")
        if (base64.isEmpty()) return null
        return ImageAttachment(json.optString("mediaType"), base64)
    }
}

/** Keeps tool requests, results, and provider-native reasoning together in a session record. */
object TranscriptCodec {
    fun encode(
        items: List<TranscriptItem>,
        images: ImageCodec = InlineImages,
    ): JSONArray = JSONArray().apply {
        for (item in items) {
            put(
                when (item) {
                    is TranscriptItem.Text -> JSONObject()
                        .put("type", "text")
                        .put("fromUser", item.userMessage)
                        .put("text", item.text)
                        .apply {
                            if (item.images.isNotEmpty()) {
                                put("images", encodeImages(item.images, images))
                            }
                        }

                    is TranscriptItem.Assistant -> JSONObject()
                        .put("type", "assistant")
                        .put("text", item.text)
                        .put("provider", item.endpointKind.name)
                        .put("providerContent", item.providerContent)
                        .put("calls", JSONArray().apply {
                            for (call in item.toolCalls) {
                                put(
                                    JSONObject()
                                        .put("id", call.id)
                                        .put("name", call.name)
                                        .put("arguments", call.arguments)
                                )
                            }
                        })

                    is TranscriptItem.ToolResult -> JSONObject()
                        .put("type", "tool_result")
                        .put("callId", item.callId)
                        .put("content", item.content)
                        .put("isError", item.isError)
                }
            )
        }
    }

    fun decode(
        items: JSONArray?,
        images: ImageCodec = InlineImages,
    ): List<TranscriptItem> {
        if (items == null) return emptyList()
        return List(items.length()) { index ->
            val item = items.getJSONObject(index)
            when (item.getString("type")) {
                "text" -> TranscriptItem.Text(
                    item.getBoolean("fromUser"),
                    item.getString("text"),
                    decodeImages(item.optJSONArray("images"), images),
                )
                "assistant" -> {
                    val calls = item.getJSONArray("calls")
                    TranscriptItem.Assistant(
                        text = item.getString("text"),
                        toolCalls = List(calls.length()) { callIndex ->
                            val call = calls.getJSONObject(callIndex)
                            ToolCall(
                                call.getString("id"),
                                call.getString("name"),
                                call.getString("arguments")
                            )
                        },
                        endpointKind = EndpointKind.from(item.getString("provider")),
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

    fun encodeImages(images: List<ImageAttachment>, codec: ImageCodec = InlineImages): JSONArray =
        JSONArray().apply {
            for (image in images) put(codec.encode(image))
        }

    fun decodeImages(
        items: JSONArray?,
        codec: ImageCodec = InlineImages,
    ): List<ImageAttachment> {
        if (items == null) return emptyList()
        return List(items.length()) { index ->
            codec.decode(items.getJSONObject(index))
        }.filterNotNull()
    }
}
