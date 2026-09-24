package com.caeamer.beikeschedule

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.caeamer.beikeschedule.data.local.ScheduleBackgroundStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** 每个测试使用独立缓存目录，不接触真实用户背景和设置。 */
class ScheduleBackgroundStoreTest {
    private lateinit var root: File
    private lateinit var store: ScheduleBackgroundStore

    @Before
    fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(app.cacheDir, "background-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        store = ScheduleBackgroundStore(context)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun image(width: Int, height: Int): File {
        val source = File(root, "source-${UUID.randomUUID()}.jpg")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } finally {
            bitmap.recycle()
        }
        return source
    }

    @Test
    fun importDownsizesAndSurvivesDeletingOriginal() = runBlocking<Unit> {
        val original = image(3072, 1536)
        val name = store.importImage(Uri.fromFile(original))
        assertTrue(original.delete())
        val saved = store.load(name)
        assertNotNull(saved)
        try {
            assertEquals(2048, saved!!.width)
            assertEquals(1024, saved.height)
        } finally {
            saved?.recycle()
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun importAppliesExifOrientation() = runBlocking<Unit> {
        val original = image(120, 60)
        ExifInterface(original.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val saved = store.load(store.importImage(Uri.fromFile(original)))
        assertNotNull(saved)
        try {
            assertEquals(60, saved!!.width)
            assertEquals(120, saved.height)
        } finally {
            saved?.recycle()
        }
    }

    @Test
    fun replacementKeepsOldFileUntilExplicitCleanup() = runBlocking<Unit> {
        val first = store.importImage(Uri.fromFile(image(20, 20)))
        val second = store.importImage(Uri.fromFile(image(30, 30)))
        val previous = store.load(first)
        assertNotNull(previous)
        previous?.recycle()
        store.removeUnused(second)
        assertNull(store.load(first))
        val current = store.load(second)
        assertNotNull(current)
        current?.recycle()
    }

    @Test
    fun missingOrBrokenImagesFallBackWithoutThrowing() = runBlocking<Unit> {
        assertNull(store.load("00000000-0000-0000-0000-000000000000.png"))
        val name = store.importImage(Uri.fromFile(image(20, 20)))
        File(root, "schedule_backgrounds/$name").writeText("broken image")
        assertNull(store.load(name))
        assertNull(store.load("../outside.png"))
    }
}
