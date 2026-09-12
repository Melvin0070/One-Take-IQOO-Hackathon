# :media — playback and export

Media3. Playback from the edit list, Transformer export, caption rendering.

## Read `docs/agents/landmines.md` L10 and L11 first

Several of the obvious answers are deprecated or disqualified, and one of them would break
a promise we make on stage.

## Playback

`ConcatenatingMediaSource` is **deprecated**. `ConcatenatingMediaSource2` merges everything
into one `Timeline.Window`, which is not what you want. The current idiom is the playlist
API plus `ClippingConfiguration`:

```kotlin
MediaItem.Builder().setUri(videoUri)
  .setClippingConfiguration(
    MediaItem.ClippingConfiguration.Builder()
      .setStartPositionMs(startMs).setEndPositionMs(endMs).build())
  .build()
```

then `player.setMediaItems(items, resetPosition)`. Transitions between playlist items are
documented as seamless.

**The keyframe risk is real and it is what threatens "no render bar".** From Media3's own
guide: an unaligned start position makes the player *decode and discard data from the
previous keyframe*, and this applies at **every segment boundary in the cut**, not just the
first. With CameraX's default encoder the GOP length is unknown to you. In order: snap
in-points to keyframes where the pause allows, pre-warm the player before the Wrap tap, and
**measure `stop-to-first-frame` on the loaner** rather than assuming 300 ms.

**Gate playback on `VideoRecordEvent.Finalize`.** Building the media source before the MP4
is finalized gives a black frame — a known critical gap. Show "preparing" until then.

`CompositionPlayer` would be natively right for the fallback architecture but is
`@ExperimentalApi` and early preview. **Do not bet the demo on it.**

## Export

`Transformer`, `Composition`, `EditedMediaItem`, `EditedMediaItemSequence`, `Effects`.
Captions burn in via `OverlayEffect` + `TextOverlay` from `androidx.media3.effect` (FIFO,
last on top). `forceAudioTrack`/`forceVideoTrack` are deprecated in favour of
`trackTypes.contains(C.TRACK_TYPE_AUDIO)`.

**`experimentalSetMp4EditListTrimEnabled` is banned.** It trims via the MP4 edit list with
no re-transcode — and **the trimmed data is still in the file.** That makes The Vanish a lie
and the delete-everything privacy promise false, and the infosec juror can open the exported
file and check. The other speedup, `experimentalSetTrimOptimizationEnabled`, is disqualified
by burned-in captions anyway.

**Foreground-only, with visible progress.** Do not promise background export in the UI —
`mediaProcessing` can be killed, and its 6-hour daily cap calls `Service.onTimeout()` and
gives you seconds to `stopSelf()` before a `RemoteServiceException`. You will never approach
6 hours here, but the missing handler is an ANR waiting to happen. It is four lines.

## Measure it

**No published numbers exist** for Transformer at 1080p with overlays. Google's benchmark is
720p with no overlays and is not your number. Measure export time per filmed minute, cold
and after ten minutes of recording. If it is slow, the honest fix is to say so on the card —
the no-render promise is about *playback on stop*, and export was never part of it.
