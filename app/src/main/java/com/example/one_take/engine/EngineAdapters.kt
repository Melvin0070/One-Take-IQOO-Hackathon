package com.example.one_take.engine

import com.example.one_take.captions.CaptionSegment
import com.example.one_take.captions.CaptionWord
import com.onetake.engine.TranscriptWord
import com.example.one_take.editing.EditCut
import com.example.one_take.editing.EditDecision
import com.onetake.engine.Caption
import com.onetake.engine.Cut
import com.onetake.engine.EditPlan
import com.onetake.engine.Timeline

internal val recordingTimeline = Timeline(16_000)

internal fun CaptionSegment.toEngine() = Caption(
    recordingTimeline.samplesFromMillis(startMs), recordingTimeline.samplesFromMillis(endMs), text, words.map {
        TranscriptWord(recordingTimeline.samplesFromMillis(it.startMs), recordingTimeline.samplesFromMillis(it.endMs), it.text, it.confidence)
    })

internal fun Caption.toApp() = CaptionSegment(
    recordingTimeline.msFromSamples(startSample), recordingTimeline.msFromSamples(endSample), text, words.map {
        CaptionWord(recordingTimeline.msFromSamples(it.startSample), recordingTimeline.msFromSamples(it.endSample), it.text, it.confidence)
    })

internal fun EditDecision.toEngine() = EditPlan(recordingTimeline.samplesFromMillis(durationMs), cuts.map {
    Cut(it.id, recordingTimeline.samplesFromMillis(it.startMs), recordingTimeline.samplesFromMillis(it.endMs),
        it.reason, it.enabled)
})

internal fun EditPlan.toApp() = EditDecision(recordingTimeline.msFromSamples(durationSamples), cuts.map {
    EditCut(it.id, recordingTimeline.msFromSamples(it.startSample), recordingTimeline.msFromSamples(it.endSample),
        it.reason, it.enabled)
})
