package com.example.one_take

import android.os.Build
import android.util.Half
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onetake.engine.android.QnnGraphSession
import com.onetake.engine.android.QnnDataType
import com.onetake.engine.android.QnnGraphBackend
import com.onetake.engine.inference.BackendKind
import com.onetake.engine.inference.BackendPolicy
import com.onetake.engine.inference.ExecutionReport
import com.onetake.engine.inference.ExecutionStatus
import com.onetake.engine.inference.ExecutionPhase
import com.onetake.engine.inference.InferenceEngine
import com.onetake.engine.inference.ModelSpec
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in execution test. Model binaries are local dependencies, never test APK assets. */
@RunWith(AndroidJUnit4::class)
class QnnWhisperGraphTest {
    @Test fun encoderAndDecoderExecuteOnHtp() {
        assumeTrue("Requires iQOO 15", Build.VERSION.SDK_INT >= 31 &&
            Build.MODEL == "I2501" && Build.SOC_MODEL == "SM8850")
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with requireWhisperGraphs=true", args.getString("requireWhisperGraphs") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bundle = File(context.filesDir, "qnn-whisper-test")
        val encoderFile = File(bundle, "encoder.bin")
        val decoderFile = File(bundle, "decoder.bin")
        assertHash(encoderFile, "c5722aebdce1621e9cddf832b134461a385018a12eabe519a68fad0bcc752f25")
        assertHash(decoderFile, "45418a0c81c8964f2d1448e03f5ce35cd01daa4de19269962fd0414547cccccd")

        // Reopen both native contexts to exercise complete ownership and release twice.
        repeat(2) {
            QnnGraphSession.open(context, encoderFile).use { encoder ->
                assertEquals(1, encoder.inputs.size)
                assertEquals("input_features", encoder.inputs.single().name)
                assertEquals(QnnDataType.FLOAT16, encoder.inputs.single().dataType)
                assertEquals(480_000, encoder.inputs.single().byteCount)
                assertThrows(IllegalArgumentException::class.java) {
                    encoder.execute(listOf(ByteArray(1)))
                }
                val encoded = encoder.execute(listOf(ByteArray(480_000)))
                assertEquals(8, encoded.size)
                val cross = encoder.outputs.mapIndexed { index, spec -> spec.name to encoded[index] }.toMap()
                encoded.forEach(::assertFiniteHalf)
                QnnGraphSession.open(context, decoderFile).use { decoder ->
                    var selfCache = emptyMap<String, ByteArray>()
                    // Two autoregressive steps verify updated self-cache inputs, not just a single graph call.
                    repeat(2) { position ->
                        val inputs = decoder.inputs.map { spec ->
                            when {
                                spec.name == "input_ids" -> intBytes(if (position == 0) 50258 else 50259)
                                spec.name == "position_ids" -> intBytes(position)
                                spec.name == "attention_mask" -> ByteBuffer.allocate(spec.byteCount)
                                    .order(ByteOrder.LITTLE_ENDIAN).apply {
                                        repeat(200) { index -> putShort(Half.toHalf(if (index >= 199 - position) 0f else -100f)) }
                                    }.array()
                                spec.name in cross -> cross.getValue(spec.name)
                                spec.name.endsWith("_in") -> selfCache[spec.name] ?: ByteArray(spec.byteCount)
                                else -> error("Unexpected decoder input ${spec.name}")
                            }
                        }
                        val result = decoder.execute(inputs)
                        assertEquals(decoder.outputs.size, result.size)
                        val byName = decoder.outputs.mapIndexed { index, spec -> spec.name to result[index] }.toMap()
                        val logits = byName.getValue("logits")
                        assertEquals(51_865 * 2, logits.size)
                        assertFiniteHalf(logits)
                        assertTrue("Logits must contain computed nonzero values", logits.any { it != 0.toByte() })
                        selfCache = byName.filterKeys { it.endsWith("_out") }
                            .mapKeys { (key, _) -> key.removeSuffix("_out") + "_in" }
                        assertEquals(8, selfCache.size)
                    }
                }
            }
        }
    }

    @Test fun engineReportsHtpExecutionAndRejectsWrongArtifactHash() {
        assumeTrue("Requires iQOO 15", Build.VERSION.SDK_INT >= 31 &&
            Build.MODEL == "I2501" && Build.SOC_MODEL == "SM8850")
        assumeTrue("Opt in with requireWhisperGraphs=true",
            InstrumentationRegistry.getArguments().getString("requireWhisperGraphs") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val binary = File(context.filesDir, "qnn-whisper-test/encoder.bin")
        val model = ModelSpec("whisper-tiny-v061-encoder-graph-test", setOf(BackendKind.NPU))
        val hash = "c5722aebdce1621e9cddf832b134461a385018a12eabe519a68fad0bcc752f25"
        assertThrows(IllegalArgumentException::class.java) {
            QnnGraphBackend(context, model, binary, "0".repeat(64)).prepare(model)
        }
        val reports = mutableListOf<ExecutionReport>()
        val engine = InferenceEngine(listOf(QnnGraphBackend(context, model, binary, hash)),
            defaultPolicy = BackendPolicy.NPU_REQUIRED, listener = { reports.add(it) })
        engine.open(model).use { session ->
            val output = session.execute(mapOf("input_features" to ByteArray(480_000)))
            assertEquals(8, output.size)
            output.values.forEach(::assertFiniteHalf)
            assertEquals(BackendKind.NPU, session.backend)
        }
        assertTrue(reports.any { it.phase == ExecutionPhase.EXECUTION &&
            it.status == ExecutionStatus.SUCCEEDED && it.actualBackend == BackendKind.NPU })
        assertTrue(reports.all { it.policy == BackendPolicy.NPU_REQUIRED })
    }

    @Test fun graphRejectsCorruptContextAndClosedSession() {
        assumeTrue("Requires iQOO 15", Build.VERSION.SDK_INT >= 31 &&
            Build.MODEL == "I2501" && Build.SOC_MODEL == "SM8850")
        assumeTrue("Opt in with requireWhisperGraphs=true",
            InstrumentationRegistry.getArguments().getString("requireWhisperGraphs") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val invalid = File.createTempFile("invalid-qnn-", ".bin", context.cacheDir)
        try {
            invalid.writeBytes(byteArrayOf(1, 2, 3, 4))
            assertThrows(IllegalStateException::class.java) { QnnGraphSession.open(context, invalid) }
        } finally {
            invalid.delete()
        }
        val file = File(context.filesDir, "qnn-whisper-test/encoder.bin")
        assertHash(file, "c5722aebdce1621e9cddf832b134461a385018a12eabe519a68fad0bcc752f25")
        assertThrows(IllegalStateException::class.java) {
            QnnGraphSession.open(context, file, "missing_graph")
        }
        val session = QnnGraphSession.open(context, file)
        session.close()
        session.close()
        assertThrows(IllegalStateException::class.java) { session.execute(listOf(ByteArray(480_000))) }
    }

    private fun intBytes(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun assertFiniteHalf(bytes: ByteArray) {
        assertEquals(0, bytes.size % 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.hasRemaining()) assertTrue("Nonfinite FP16 output", Half.toFloat(buffer.short).isFinite())
    }

    private fun assertHash(file: File, expected: String) {
        assertTrue("Missing local model ${file.name}", file.isFile)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        assertEquals(expected, digest.digest().joinToString("") { "%02x".format(it) })
    }
}
