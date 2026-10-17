package dev.undefinedteam.wearagent.agent.tools

import dev.undefinedteam.wearagent.agent.ToolCall
import dev.undefinedteam.wearagent.agent.ToolDefinition
import dev.undefinedteam.wearagent.session.ToolSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InterruptedIOException

class ToolTest {
    private class ThrowingTool(private val failure: Throwable) : HttpTool() {
        override val definition = ToolDefinition("boom", "always fails", """{"type":"object"}""")

        override val setting: ToolSetting? = null

        override fun invoke(call: ToolCall): String = throw failure
    }

    private fun call(name: String = "boom") = ToolCall("call-1", name, "{}")

    @Test fun errorsBecomeModelFacingFailuresNotCrashes() {
        for (failure in listOf(StackOverflowError("deep payload"), OutOfMemoryError("huge payload"))) {
            val result = ThrowingTool(failure).execute(call())
            assertEquals("call-1", result.callId)
            assertTrue(result.isError)
            assertTrue(result.content.startsWith("Error: "))
        }
    }

    @Test fun unknownToolNameIsReportedAsFailure() {
        val result = ThrowingTool(IllegalStateException()).execute(call("other_tool"))
        assertTrue(result.isError)
        assertTrue(result.content.contains("Unknown tool"))
    }

    @Test fun cancellationStaysAnException() {
        val tool = ThrowingTool(IllegalStateException())
        tool.cancel()
        try {
            tool.execute(call())
            fail("Expected InterruptedIOException")
        } catch (_: InterruptedIOException) {
            // Cancellation must reach the loop, never a model-facing result.
        }
    }
}
