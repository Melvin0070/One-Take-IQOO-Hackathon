package com.onetake.npu

import java.io.File

/**
 * The public Qualcomm Whisper Tiny Voice AI release selected for the iQOO 15.
 *
 * The archive and every extracted input are pinned independently.  The archive
 * digest prevents a different compression package from silently replacing the
 * release, while the per-file digests protect the files that the graph runtime
 * opens.
 */
object TinyWhisperBundleSpec {
    const val MODEL_ID = "qualcomm/Whisper-Tiny"
    const val RELEASE = "v0.61.0"
    const val TARGET_DEVICE_MODEL = "I2501"
    const val TARGET_SOC_MODEL = "SM8850"
    const val TARGET_SOC_VARIANT = "V81"

    const val DOWNLOAD_URL =
        "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/" +
            "qai-hub-models/models/whisper_tiny/releases/v0.61.0/" +
            "whisper_tiny-voice_ai-float-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip"

    const val ARCHIVE_SIZE_BYTES = 106_106_008L
    const val ARCHIVE_SHA256 =
        "086017959cd4e208c0711c0297d8d9d2e5031677082a1e2063651519917f9820"

    const val ARCHIVE_ROOT =
        "whisper_tiny-voice_ai-float-qualcomm_snapdragon_8_elite_gen5_for_galaxy/"

    const val ENCODER_NAME = "encoder.bin"
    const val DECODER_NAME = "decoder.bin"
    const val VOCABULARY_NAME = "vocab.bin"
    const val METADATA_NAME = "metadata.json"
    const val CONFIG_NAME = "config.json"

    const val ENCODER_SIZE_BYTES = 20_025_344L
    const val DECODER_SIZE_BYTES = 97_615_872L
    const val VOCABULARY_SIZE_BYTES = 357_313L
    const val METADATA_SIZE_BYTES = 10_652L
    const val CONFIG_SIZE_BYTES = 813L

    const val ENCODER_SHA256 =
        "c5722aebdce1621e9cddf832b134461a385018a12eabe519a68fad0bcc752f25"
    const val DECODER_SHA256 =
        "45418a0c81c8964f2d1448e03f5ce35cd01daa4de19269962fd0414547cccccd"
    const val VOCABULARY_SHA256 =
        "0ba87984671b92e03b56b84ce9b217020663f6a269b5a9800901391430b79c4b"
    const val METADATA_SHA256 =
        "c541446525fef01fdc22cc985260ed701c27e89b0240c608af371c0e4dc91987"
    const val CONFIG_SHA256 =
        "07370a056cd4c78782ee3b96b4b39442b742b31b755f3b229a84289658687b9a"

    const val LICENSE_URL =
        "https://github.com/huggingface/transformers/blob/v4.42.3/LICENSE"
    const val MODEL_CARD_URL = "https://huggingface.co/qualcomm/Whisper-Tiny"

    internal val artifacts = listOf(
        Artifact(ENCODER_NAME, ENCODER_SIZE_BYTES, ENCODER_SHA256),
        Artifact(DECODER_NAME, DECODER_SIZE_BYTES, DECODER_SHA256),
        Artifact(VOCABULARY_NAME, VOCABULARY_SIZE_BYTES, VOCABULARY_SHA256),
        Artifact(METADATA_NAME, METADATA_SIZE_BYTES, METADATA_SHA256),
        Artifact(CONFIG_NAME, CONFIG_SIZE_BYTES, CONFIG_SHA256),
    )

    internal val artifactByArchivePath: Map<String, Artifact> = artifacts.associateBy {
        ARCHIVE_ROOT + it.name
    }

    internal data class Artifact(
        val name: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    internal fun file(directory: File, name: String): File = File(directory, name)
}
