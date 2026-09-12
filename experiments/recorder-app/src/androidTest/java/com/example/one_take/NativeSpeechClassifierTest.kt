package com.example.one_take

import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.audio.WebRtcSpeechClassifier
import com.onetake.engine.SpeechActivity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device coverage for the small offline WebRTC speech classifier. */
class NativeSpeechClassifierTest {
    @Test
    fun rejectsInvalidFramesAndClosedInstancesAsUnknown() {
        val classifier = WebRtcSpeechClassifier.create()
        try {
            assertEquals(SpeechActivity.UNKNOWN, classifier.classify(FloatArray(319)))
            assertEquals(
                SpeechActivity.UNKNOWN,
                classifier.classify(FloatArray(WebRtcSpeechClassifier.FRAME_SAMPLES) { index ->
                    if (index == 12) Float.NaN else 0f
                }),
            )
            assertEquals(
                SpeechActivity.UNKNOWN,
                classifier.classify(FloatArray(WebRtcSpeechClassifier.FRAME_SAMPLES) { index ->
                    if (index == 12) 1.01f else 0f
                }),
            )
        } finally {
            classifier.close()
        }
        assertEquals(
            SpeechActivity.UNKNOWN,
            classifier.classify(FloatArray(WebRtcSpeechClassifier.FRAME_SAMPLES)),
        )
        classifier.close()
    }

    @Test
    fun classifiesSilenceAndSpeechFromTheRealHinglishFixture() {
        val frames = readPcmFrames("hinglish-vad-speech.wav")
        require(frames.isNotEmpty())

        WebRtcSpeechClassifier.create().use { classifier ->
            val decisions = frames.map(classifier::classify)
            assertTrue("Fixture must produce at least one speech frame", decisions.contains(SpeechActivity.SPEECH))
            assertTrue("Fixture must produce at least one non-speech frame", decisions.contains(SpeechActivity.NON_SPEECH))

            val firstSpeech = decisions.indexOf(SpeechActivity.SPEECH)
            assertTrue("Fixture must contain non-speech after speech", decisions.drop(firstSpeech + 1).contains(SpeechActivity.NON_SPEECH))
        }
    }

    private fun readPcmFrames(assetName: String): List<FloatArray> {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open(assetName)
            .use { it.readBytes() }
        require(bytes.size >= 44) { "WAV fixture is too short" }
        require(bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()))
        require(bytes.copyOfRange(8, 12).contentEquals("WAVE".toByteArray()))

        val dataOffset = findDataChunk(bytes)
        val dataSize = littleEndianInt(bytes, dataOffset + 4)
        val pcmOffset = dataOffset + 8
        require(dataSize >= WebRtcSpeechClassifier.FRAME_SAMPLES * 2)
        require(pcmOffset + dataSize <= bytes.size)
        val frameBytes = WebRtcSpeechClassifier.FRAME_SAMPLES * 2
        return (pcmOffset until pcmOffset + dataSize - frameBytes + 1 step frameBytes).map { start ->
            val frame = FloatArray(WebRtcSpeechClassifier.FRAME_SAMPLES)
            for (index in frame.indices) {
                val sampleOffset = start + index * 2
                val sample = ByteBuffer.wrap(bytes, sampleOffset, 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .short
                frame[index] = sample / 32768f
            }
            frame
        }
    }

    private fun findDataChunk(bytes: ByteArray): Int {
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkSize = littleEndianInt(bytes, offset + 4)
            if (bytes.copyOfRange(offset, offset + 4).contentEquals("data".toByteArray())) {
                return offset
            }
            require(chunkSize >= 0)
            offset += 8 + chunkSize + (chunkSize and 1)
        }
        error("WAV data chunk is missing")
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
}
