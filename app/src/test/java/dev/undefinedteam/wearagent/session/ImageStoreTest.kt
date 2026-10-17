package dev.undefinedteam.wearagent.session

import dev.undefinedteam.wearagent.agent.ImageAttachment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class ImageStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store() = ImageStore(folder.newFolder())

    private fun image(bytes: ByteArray, mediaType: String = "image/png") =
        ImageAttachment(mediaType, Base64.getEncoder().encodeToString(bytes))

    @Test fun writesAndReadsBackTheSameBytes() {
        val store = store()
        val png = image(byteArrayOf(1, 2, 3, 4))
        val name = store.put(png)
        val loaded = store.load(name)
        assertEquals(png.mediaType, loaded?.mediaType)
        assertEquals(png.base64, loaded?.base64)
        assertEquals(1, folder.root.walkTopDown().count { it.isFile })
    }

    @Test fun theSameBytesAreStoredOnce() {
        val store = store()
        val first = store.put(image(byteArrayOf(9, 9, 9)))
        val second = store.put(image(byteArrayOf(9, 9, 9)))
        assertEquals(first, second)
        assertEquals(1, folder.root.walkTopDown().count { it.isFile })
    }

    @Test fun differentBytesGetDifferentNames() {
        val store = store()
        assertTrue(store.put(image(byteArrayOf(1))) != store.put(image(byteArrayOf(2))))
    }

    @Test fun theMediaTypeSurvivesThroughTheExtension() {
        val store = store()
        val webp = image(byteArrayOf(7), "image/webp")
        assertEquals("image/webp", store.load(store.put(webp))?.mediaType)
    }

    @Test fun aNameNoLongerReferencedIsDeleted() {
        val store = store()
        val kept = store.put(image(byteArrayOf(1)))
        val dropped = store.put(image(byteArrayOf(2)))
        store.retain(listOf(SessionImages("a", listOf(kept))))
        assertTrue(store.load(kept) != null)
        assertNull(store.load(dropped))
    }

    @Test fun aNameReferencedByAnotherSessionSurvives() {
        val store = store()
        val shared = store.put(image(byteArrayOf(5)))
        store.retain(
            listOf(
                SessionImages("a", listOf(shared)),
                SessionImages("b", listOf(shared)),
            ),
        )
        assertTrue(store.load(shared) != null)
    }

    @Test fun retainingEverythingKeepsEverything() {
        val store = store()
        val a = store.put(image(byteArrayOf(1)))
        val b = store.put(image(byteArrayOf(2)))
        store.retain(listOf(SessionImages("a", listOf(a, b))))
        assertTrue(store.load(a) != null)
        assertTrue(store.load(b) != null)
    }

    @Test fun anUnknownNameReadsAsMissing() {
        assertNull(store().load("does-not-exist.png"))
    }

    @Test fun anInstanceCreatedBeforeTheWriteStillSeesTheImage() {
        // The app holds several SessionLog instances (activity, service, settings).
        // One built before an image was written must still resolve it.
        val directory = folder.newFolder()
        val reader = ImageStore(directory)
        val name = ImageStore(directory).put(image(byteArrayOf(1, 2, 3)))
        assertEquals(image(byteArrayOf(1, 2, 3)), reader.load(name))
    }

    @Test fun anInstanceCreatedBeforeTheWriteStillPrunesCorrectly() {
        val directory = folder.newFolder()
        val reader = ImageStore(directory)
        val dropped = ImageStore(directory).put(image(byteArrayOf(4, 5)))
        reader.retain(emptyList())
        assertNull(reader.load(dropped))
    }

    @Test fun theStoreCodecWritesAReferenceNotTheBytes() {
        val store = store()
        val codec = StoredImages(store)
        val png = image(byteArrayOf(3, 3, 3))
        val encoded = codec.encode(png)
        assertTrue(encoded.has("name"))
        assertFalse(encoded.has("base64"))
        assertEquals(png, codec.decode(encoded))
    }

    @Test fun theStoreCodecStillReadsRecordsWrittenBeforeTheStore() {
        val codec = StoredImages(store())
        val legacy = JSONObject()
            .put("mediaType", "image/png")
            .put("base64", Base64.getEncoder().encodeToString(byteArrayOf(4, 4)))
        assertEquals("image/png", codec.decode(legacy)?.mediaType)
        assertEquals(byteArrayOf(4, 4).toList(), codec.decode(legacy)?.let { Base64.getDecoder().decode(it.base64).toList() })
    }

    @Test fun aReferenceWhoseFileIsGoneDecodesToNothing() {
        val store = store()
        val codec = StoredImages(store)
        val name = store.put(image(byteArrayOf(8)))
        store.retain(emptyList())
        assertNull(codec.decode(JSONObject().put("name", name)))
    }
}
