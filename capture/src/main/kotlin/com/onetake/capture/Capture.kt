package com.onetake.capture

import com.onetake.engine.AudioFrame
import com.onetake.engine.VideoAnchor
import kotlinx.coroutines.flow.Flow

/**
 * CameraX, AudioRecord, the foreground service, the WAV writer, and the one VideoAnchor
 * per session. Read capture/AGENTS.md and docs/agents/landmines.md L9, L12, L13 first.
 */

/**
 * Architecture B: CameraX muxes its own audio and the MP4 IS the cut source. Sync
 * tolerance is hundreds of milliseconds, because every cut boundary lands in a speech
 * pause.
 *
 * Write the WAV UNCONDITIONALLY anyway, even though B does not need it. Then if B turns
 * out not to hold on this device, the fallback is a playback-path swap rather than a
 * re-architecture at hour 20. Under B the WAV costs a few MB; under A it is already
 * on disk.
 *
 * This module owns the master sample counter. Count every sample you produce — nothing
 * downstream has another clock.
 */
class CaptureSession {

    /** 16 kHz mono PCM with absolute sample indices. The spine's input. */
    val audio: Flow<AudioFrame>
        get() = TODO("Lane E: AudioRecord(VOICE_RECOGNITION). See capture/AGENTS.md")

    /** Emitted exactly ONCE per session. The single place video time enters the engine. */
    val anchor: Flow<VideoAnchor>
        get() = TODO("Lane E")

    /**
     * Start the foreground service and CameraX.
     *
     * `camera` and `microphone` FGS types CANNOT be started from the background — on
     * Android 14+ that is a SecurityException thrown immediately at startForeground(),
     * not a degradation. Call this while an activity is visible.
     * PermissionChecker.checkSelfPermission() does NOT protect you; it returns
     * PERMISSION_GRANTED in the background too. (landmines L12)
     */
    fun start(sessionId: String): Unit = TODO("Lane E")

    fun stop(): Unit = TODO("Lane E")
}

/**
 * The probe. Thirty minutes, before any feature code, and it decides whether lip sync is
 * free or has to be engineered to 33 ms.
 *
 * Run it in BOTH start orders — the CDD's tiebreak for equal-priority capturers is "most
 * recently started wins", and if OriginOS reuses that path for same-app clients then
 * order decides who gets audio.
 *
 * Do not trust registerAudioRecordingCallback alone: it must be registered BEFORE
 * startRecording(), and it fires "only when the app is receiving audio and a change
 * occurs" — silence from frame zero may never fire it. Check the buffers.
 */
object ConcurrentCaptureProbe {

    data class Result(
        /** CameraX: audioState == AUDIO_STATE_ACTIVE && getAudioAmplitude() > 0 */
        val cameraXAudioActive: Boolean,
        /** AudioRecord buffers are NOT all zero. Checked directly, not inferred. */
        val audioRecordHasSignal: Boolean,
        /** AudioRecordingConfiguration.isClientSilenced() == false */
        val notClientSilenced: Boolean,
        val startOrder: String,
        val notes: String,
    ) {
        /** All three, or Architecture B does not hold on this device. */
        val architectureBHolds: Boolean
            get() = cameraXAudioActive && audioRecordHasSignal && notClientSilenced
    }

    fun run(cameraFirst: Boolean): Result = TODO("Lane E: see capture/AGENTS.md")
}

/**
 * The storage guard. 1080p is roughly 100 MB/min and a full disk currently truncates the
 * session SILENTLY — one of three known critical gaps.
 *
 * Do not estimate the bitrate. CameraX publishes none; the real number is whatever vivo
 * put in this device's CamcorderProfile:
 *
 *     CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_1080P).videoBitRate
 *
 * Then poll RecordingStats.getNumBytesRecorded() on VideoRecordEvent.Status — exact and
 * free — stop at a threshold, and mark the session truncated.
 */
class StorageGuard {
    fun estimatedRecordableMinutes(): Int = TODO("Lane E")
}
