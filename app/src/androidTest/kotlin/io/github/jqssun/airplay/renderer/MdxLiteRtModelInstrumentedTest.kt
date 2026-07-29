package io.github.jqssun.airplay.renderer

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MdxLiteRtModelInstrumentedTest {
    @Test
    fun modelLoadsAndSeparatesOneWindowOnDevice() {
        assumeTrue(isAiVocalSeparationSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val statuses = mutableListOf<String>()
        MdxLiteRtModel(context, statuses::add).use { model ->
            val started = SystemClock.elapsedRealtime()
            val output = model.run(FloatArray(MdxVocalChunkProcessor.MODEL_FLOATS))
            val inferenceMs = SystemClock.elapsedRealtime() - started
            Log.i(
                TAG,
                "backend=${model.backendLabel}, warmup=${model.warmUpMs}ms, " +
                    "inference=${inferenceMs}ms, statuses=$statuses",
            )
            assertEquals(MdxVocalChunkProcessor.MODEL_FLOATS, output.size)
            assertTrue(output.all(Float::isFinite))
        }
    }

    private companion object {
        const val TAG = "MdxDeviceBenchmark"
    }
}
