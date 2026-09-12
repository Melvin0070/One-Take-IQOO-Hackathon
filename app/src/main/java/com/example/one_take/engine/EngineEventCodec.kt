package com.example.one_take.engine

import com.onetake.engine.*
import org.json.JSONArray
import org.json.JSONObject

/** Versioned storage adapter. Domain events have no JSON or Android dependency. */
internal object EngineEventCodec {
    fun encode(event: Event): String {
        val value = JSONObject().put("version", 1).put("sequence", event.sequence)
            .put("session", event.sessionId).put("sample", event.sample).put("clock", event.clock.name)
        val change = JSONObject()
        when (val input = event.change) {
            is Change.ScriptProgressObserved -> change.put("type", "script-progress")
                .put("current", input.progress.currentIndex).put("reason", input.progress.reason.name)
                .put("chunks", JSONArray().also { array -> input.progress.chunks.forEach {
                    array.put(JSONObject().put("id", it.chunk.id).put("text", it.chunk.text)
                        .put("state", it.state.name).put("coverage", it.coverage).put("attempts", it.attempts))
                } })
            is Change.SignalObserved -> change.put("type", "signal").put("signal", encodeSignal(input.signal))
            is Change.SourceFinalized -> change.put("type", "source-finalized")
                .put("source", input.sourceId).put("duration", input.durationSamples)
                .put("anchorSample", input.anchor.sampleIndex).put("anchorVideoUs", input.anchor.videoTimeUs)
            is Change.CaptionsReplaced -> change.put("type", "captions-replaced").put("captions",
                JSONArray().also { array -> input.captions.forEach {
                    array.put(JSONObject().put("start", it.startSample).put("end", it.endSample).put("text", it.text)
                        .put("words", JSONArray().also { words -> it.words.forEach { word ->
                            words.put(JSONObject().put("start", word.startSample).put("end", word.endSample)
                                .put("text", word.text).put("confidence", word.confidence))
                        } }))
                } })
            is Change.EditsReplaced -> change.put("type", "edits-replaced").put("duration", input.edits.durationSamples)
                .put("cuts", JSONArray().also { array -> input.edits.cuts.forEach {
                    array.put(JSONObject().put("id", it.id).put("start", it.startSample).put("end", it.endSample)
                        .put("reason", it.reason).put("enabled", it.enabled))
                } })
            is Change.CutToggled -> change.put("type", "cut-toggled").put("id", input.id)
            Change.CutsRestored -> change.put("type", "cuts-restored")
            Change.Cancelled -> change.put("type", "cancelled")
            Change.MediaMissing -> change.put("type", "media-missing")
            is Change.CaptureRequested -> change.put("type", "capture-requested").put("name", input.sourceName)
                .put("mode", input.mode.name).putOpt("script", input.script).putOpt("startedAt", input.startedAtEpochMs)
            Change.CaptureStarted -> change.put("type", "capture-started")
            is Change.StopRequested -> change.put("type", "stop-requested").put("reason", input.reason)
            is Change.CaptureFailed -> change.put("type", "capture-failed").put("reason", input.reason)
            is Change.CaptureInterrupted -> change.put("type", "capture-interrupted").put("reason", input.reason)
            is Change.PauseCandidateObserved -> change.put("type", "pause-candidate")
                .put("id", input.candidate.id).put("start", input.candidate.startSample).put("end", input.candidate.endSample)
            is Change.ProvisionalTranscript -> change.put("type", "provisional-transcript").put("text", input.text)
            is Change.VisionObserved -> change.put("type", "vision-observed")
                .put("face", input.observation.faceInFrame).put("processor", input.observation.processor)
                .put("x", input.observation.centerX ?: JSONObject.NULL)
                .put("y", input.observation.centerY ?: JSONObject.NULL).put("offAxis", input.observation.offAxis)
        }
        return value.put("change", change).toString()
    }

    fun decode(payload: String): Event {
        val value = JSONObject(payload)
        require(value.getInt("version") == 1) { "Unsupported engine ledger version" }
        val data = value.getJSONObject("change")
        val change = when (data.getString("type")) {
            "script-progress" -> Change.ScriptProgressObserved(ScriptProgress(data.getJSONArray("chunks").objects().map {
                ChunkCoverage(ScriptChunk(it.getString("id"), it.getString("text")),
                    ScriptChunkState.valueOf(it.getString("state")), it.getDouble("coverage"), it.getInt("attempts"))
            }, data.getInt("current"), ScriptProgressReason.valueOf(data.getString("reason"))).frozen())
            "signal" -> Change.SignalObserved(decodeSignal(data.getJSONObject("signal")))
            // Written by the script matcher before takes became session signals; span is the segment end.
            "take-attempt" -> Change.SignalObserved(TakeAttempt("${data.getString("chunk")}#${data.getInt("attempt")}",
                data.getString("chunk"), data.getInt("attempt"), value.getLong("sample"), value.getLong("sample")))
            "source-finalized" -> Change.SourceFinalized(data.getString("source"), data.getLong("duration"),
                VideoAnchor(data.getLong("anchorSample"), data.getLong("anchorVideoUs")))
            "captions-replaced" -> Change.CaptionsReplaced(data.getJSONArray("captions").objects().map {
                Caption(it.getLong("start"), it.getLong("end"), it.getString("text"),
                    it.optJSONArray("words")?.objects()?.map { word ->
                        TranscriptWord(word.getLong("start"), word.getLong("end"), word.getString("text"),
                            word.getDouble("confidence").toFloat())
                    }.orEmpty())
            })
            "edits-replaced" -> Change.EditsReplaced(EditPlan(data.getLong("duration"),
                data.getJSONArray("cuts").objects().map {
                    Cut(it.getString("id"), it.getLong("start"), it.getLong("end"),
                        it.getString("reason"), it.getBoolean("enabled"))
                }))
            "cut-toggled" -> Change.CutToggled(data.getString("id"))
            "cuts-restored" -> Change.CutsRestored
            "cancelled" -> Change.Cancelled
            "media-missing" -> Change.MediaMissing
            "capture-requested" -> Change.CaptureRequested(data.getString("name"),
                SessionMode.valueOf(data.optString("mode", SessionMode.ASSISTED.name)),
                if (data.isNull("script")) null else data.getString("script"),
                if (data.isNull("startedAt")) null else data.getLong("startedAt"))
            "capture-started" -> Change.CaptureStarted
            "stop-requested" -> Change.StopRequested(data.getString("reason"))
            "capture-failed" -> Change.CaptureFailed(data.getString("reason"))
            "capture-interrupted" -> Change.CaptureInterrupted(data.getString("reason"))
            "pause-candidate" -> Change.PauseCandidateObserved(PauseCandidate(data.getString("id"), data.getLong("start"), data.getLong("end")))
            "provisional-transcript" -> Change.ProvisionalTranscript(data.getString("text"))
            "vision-observed" -> Change.VisionObserved(VisionObservation(data.getBoolean("face"), data.getString("processor"),
                if (data.isNull("x")) null else data.getDouble("x").toFloat(),
                if (data.isNull("y")) null else data.getDouble("y").toFloat(), data.getBoolean("offAxis")))
            else -> error("Unsupported engine event")
        }
        return Event(value.getLong("sequence"), value.getString("session"), value.getLong("sample"), change,
            ClockDomain.valueOf(value.optString("clock", ClockDomain.MEDIA.name)))
    }

    private fun encodeSignal(signal: SessionSignal): JSONObject {
        val value = JSONObject().put("id", signal.id).put("start", signal.startSample).put("end", signal.endSample)
        return when (signal) {
            is TranscriptSegment -> value.put("kind", "transcript-segment").put("text", signal.text)
                .put("words", JSONArray().also { words -> signal.words.forEach {
                    words.put(JSONObject().put("start", it.startSample).put("end", it.endSample)
                        .put("text", it.text).put("confidence", it.confidence))
                } })
            is Silence -> value.put("kind", "silence").put("source", signal.source.name)
            is Filler -> value.put("kind", "filler").put("text", signal.text)
            is GazeSample -> value.put("kind", "gaze").put("face", signal.faceInFrame).putOpt("onCamera", signal.onCamera)
            is TakeAttempt -> value.put("kind", "take-attempt").put("group", signal.groupId).put("attempt", signal.attemptIndex)
        }
    }

    private fun decodeSignal(data: JSONObject): SessionSignal {
        val id = data.getString("id")
        val start = data.getLong("start")
        val end = data.getLong("end")
        return when (data.getString("kind")) {
            "transcript-segment" -> TranscriptSegment(id, start, end, data.getString("text"),
                data.getJSONArray("words").objects().map {
                    TranscriptWord(it.getLong("start"), it.getLong("end"), it.getString("text"), it.getDouble("confidence").toFloat())
                })
            "silence" -> Silence(id, start, end, VoiceActivitySource.valueOf(data.getString("source")))
            "filler" -> Filler(id, start, end, data.getString("text"))
            "gaze" -> GazeSample(id, start, data.getBoolean("face"), if (data.isNull("onCamera")) null else data.getBoolean("onCamera"))
            "take-attempt" -> TakeAttempt(id, data.getString("group"), data.getInt("attempt"), start, end)
            else -> error("Unsupported session signal")
        }
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}
