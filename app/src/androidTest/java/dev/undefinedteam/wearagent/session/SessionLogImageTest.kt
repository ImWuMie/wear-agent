package dev.undefinedteam.wearagent.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.undefinedteam.wearagent.agent.ImageAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

/**
 * SessionLog owns the image store's lifecycle — it prunes on write and on delete
 * — so this exercises the store through the real log on a device, which the JVM
 * unit tests cannot do.
 */
@RunWith(AndroidJUnit4::class)
class SessionLogImageTest {
    private lateinit var log: SessionLog
    private lateinit var images: File

    @Before fun reset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        images = File(context.filesDir, "images")
        File(context.filesDir, "sessions").listFiles()?.forEach { it.delete() }
        images.listFiles()?.forEach { it.delete() }
        log = SessionLog(context)
    }

    private fun image(bytes: ByteArray) =
        ImageAttachment("image/png", Base64.getEncoder().encodeToString(bytes))

    private fun storedFiles() = images.listFiles()?.map { it.name }?.toSet().orEmpty()

    @Test fun aMessageImageIsWrittenAsAFileAndReadBack() {
        val id = log.create()
        val image = image(byteArrayOf(1, 2, 3, 4))
        log.append(id, dev.undefinedteam.wearagent.session.ChatMessage(1, true, "看这个", images = listOf(image)))

        val line = File(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "sessions"), "$id.jsonl").readText()
        assertTrue("the line must reference a name, not carry the bytes", line.contains("\"name\":\""))
        assertTrue("the line must not carry base64", !line.contains("\"base64\":\""))

        val loaded = log.load(id).single()
        assertEquals(image.base64, loaded.images.single().base64)
        assertEquals(1, storedFiles().size)
    }

    @Test fun twoSessionsCreatedBackToBackGetDistinctIds() {
        val first = log.create()
        val second = log.create()
        assertTrue("create() collided: $first == $second", first != second)
    }

    @Test fun theSameImageInTwoMessagesIsStoredOnce() {
        val id = log.create()
        val image = image(byteArrayOf(5, 5, 5))
        log.append(id, dev.undefinedteam.wearagent.session.ChatMessage(1, true, "a", images = listOf(image)))
        log.append(id, dev.undefinedteam.wearagent.session.ChatMessage(2, true, "b", images = listOf(image)))
        assertEquals(1, storedFiles().size)
        assertEquals(2, log.load(id).size)
    }

    @Test fun deletingTheSessionDeletesItsImages() {
        val id = log.create()
        log.append(id, dev.undefinedteam.wearagent.session.ChatMessage(1, true, "a", images = listOf(image(byteArrayOf(7)))))
        assertEquals(1, storedFiles().size)
        log.deleteSession(id)
        assertEquals(emptySet<String>(), storedFiles())
    }

    @Test fun aLogBuiltBeforeTheImageWasWrittenStillLoadsIt() {
        // Mirrors the real app: AgentService builds its SessionLog at startup, the
        // activity later writes an image, and the service then reads the session to
        // run the turn. A reader that cached the directory listing at construction
        // would silently drop the image.
        val reader = SessionLog(InstrumentationRegistry.getInstrumentation().targetContext)
        val id = log.create()
        val image = image(byteArrayOf(11, 22, 33))
        log.append(id, dev.undefinedteam.wearagent.session.ChatMessage(1, true, "看图", images = listOf(image)))

        val loaded = reader.load(id).single()
        assertEquals("看图", loaded.text)
        assertEquals(image.base64, loaded.images.single().base64)
    }

    @Test fun anImageAnotherSessionStillUsesSurvivesTheDelete() {
        val first = log.create()
        val second = log.create()
        val shared = image(byteArrayOf(9, 9))
        log.append(first, dev.undefinedteam.wearagent.session.ChatMessage(1, true, "a", images = listOf(shared)))
        log.append(second, dev.undefinedteam.wearagent.session.ChatMessage(1, true, "b", images = listOf(shared)))

        log.deleteSession(first)
        assertEquals(1, storedFiles().size)
        assertEquals(shared.base64, log.load(second).single().images.single().base64)
    }

    @Test fun deletingTheMessageReleasesItsImage() {
        val id = log.create()
        log.append(id, dev.undefinedteam.wearagent.session.ChatMessage(1, true, "a", images = listOf(image(byteArrayOf(3)))))
        log.append(id, dev.undefinedteam.wearagent.session.ChatMessage(2, false, "b"))
        assertEquals(1, storedFiles().size)

        log.deleteMessage(id, 1)
        assertEquals(emptySet<String>(), storedFiles())
    }

    @Test fun anImageWhoseFileWasRemovedLoadsAsNoImageRatherThanFailing() {
        val id = log.create()
        log.append(id, dev.undefinedteam.wearagent.session.ChatMessage(1, true, "a", images = listOf(image(byteArrayOf(1)))))
        // Simulate an external wipe of the store.
        images.listFiles()?.forEach { it.delete() }
        SessionLog(InstrumentationRegistry.getInstrumentation().targetContext)

        val loaded = log.load(id).single()
        assertEquals("a", loaded.text)
        assertTrue(loaded.images.isEmpty())
    }

    @Test fun editingTheTextKeepsTheImage() {
        val id = log.create()
        val image = image(byteArrayOf(2, 2))
        log.append(id, dev.undefinedteam.wearagent.session.ChatMessage(1, true, "a", images = listOf(image)))
        log.editMessage(id, 1, "edited")
        val loaded = log.load(id).single()
        assertEquals("edited", loaded.text)
        assertEquals(image.base64, loaded.images.single().base64)
        assertNotNull(storedFiles().firstOrNull())
    }

    @Test fun truncatingTheSessionPrunesOnlyTheDroppedImages() {
        val id = log.create()
        val kept = image(byteArrayOf(1, 1))
        val dropped = image(byteArrayOf(2, 2))
        log.append(id, dev.undefinedteam.wearagent.session.ChatMessage(1, true, "a", images = listOf(kept)))
        log.append(id, dev.undefinedteam.wearagent.session.ChatMessage(2, true, "b", images = listOf(dropped)))
        assertEquals(2, storedFiles().size)

        log.truncateFrom(id, 2)
        assertEquals(1, storedFiles().size)
        assertEquals(kept.base64, log.load(id).single().images.single().base64)
        assertNull(storedFiles().firstOrNull { it != storedFiles().first() })
    }
}
