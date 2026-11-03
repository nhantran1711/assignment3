# COMP3011 Assignment 2 - Java Local Project

For more information on this code and how to get started, see https://learn.adelaide.edu.au/courses/30747/assignments/94505

## 1. Command line interface

Effects are described in one place, the `FrameOption` enum. Each constant holds the short letter, the long name, the help description and a constructor reference. `CommandLineController` parses stacked (`-jfd`), long and mixed options into an ordered `List<FrameOption>`, and `createFrameProcessors()` builds one new processor per entry, so  that repeated options (`-ss`) are independent instances. `VideoPlayerModel` owns the resulting list.

To add a new effect: write the `FrameProcessor` subclass, then add one constant to `FrameOption`. Parsing and help text
need no changes (help lines are generated from the enum).

Tests: `CommandLineControllerTest` (run with `mvn test` / `mvn verify`) covers stacked/long/mixed equivalence, default
(no effects), and the exact help text.

## 2. Baseline: why the single-threaded player runs out of time

Machine: 6 cores / 12 logical processors (AMD Ryzen 5 7500F). Clip: The Simpsons Movie trailer, 1920x800, 23.98 fps, so the per-frame budget is 41.7 ms.

### 2.1 Cost of each effect alone

Measured with `EffectTimer` (src/test): the first 40 frames of the clip, 10 warm-up frames discarded, mean of the remaining 30, each effect run alone on a fresh copy of each frame.

| Effect | ms per frame |
|---|---|
| Bleed | 7.83 |
| Vignette | 7.44 |
| Black and white | 4.98 |
| Mottle | 4.88 |
| Yellow | 2.76 |
| Flicker | 1.46 |
| Jitter | 0.30 |
| Pepper | 0.15 |
| Dust | 0.07 |
| Scratch | 0.04 |
| Number | 0.01 |


Observations that drive the design:

- One of every effect costs about 30 ms in total, so no single effect comes close to the 41.7 ms budget.
- The five heavy effects make up about 28 ms; the six light ones together about 2 ms.
- So there is no single slow stage that would limit a pipeline; the limit is the number of cores.
- For the very cheap effects a thread hand-off costs about as much as the work, so adjacent cheap effects should share a stage.

### 2.2 Behaviour of the supplied single-threaded player

Run through `mvn javafx:run` with the console "Frame headroom" trace (negative = frame late). Sampled once per second.

| Effects | Effect work (from 2.1) | Result |
|---|---|---|
| `-bv` | about 15 ms | 1 late frame in 20 samples; headroom varied from about 5 ms to 25 ms |
| `-bvbv` | about 30 ms | 5 of 10 samples late (down to -18 ms) |
| `-bvbvbv` | about 45 ms | every sample late; lateness grew from 0.3 s to 1.3 s over 12 samples |


### 2.3 Target

With 6 physical cores the pipeline should sustain many more stacked effects before stalling. Rough capacity estimate: about 6 x 41.7 ms of CPU time per frame, less decode, GUI, audio and JavaFX threads. This is an estimate to test, not aresult. 

## 3. Threading architecture
The supplied player did everything on the JavaFX thread: an AnimationTimer decoded a frame, ran every effect, scheduled the audio and drew the picture. 
The new player moves decoding and effects onto worker threads and leaves only drawing, audio output and timing on the JavaFX thread.

### 3.1 Stage layout
 
Effects are split into contiguous groups, one group per stage, and there are about as many stages as processors, but never more than the number of effects. Only neighbouring effects are grouped, so the command-line order never changes.
 
My first version used one thread per effect. It worked for short stacks but >= 40 effects it puts dozens of threads on a few cores. Grouping keeps enough stages to use every core while cutting scheduling overhead.
 
Queues between stages hold 4 frames. The last queue holds 12, which gives the JavaFX thread some time if a stage stalls briefly.


### 3.2 Pause, seek, rewind and stop
 
- **Pause / resume:** `PlaybackClock` records when the pause began and shifts its start time forward by the pause length
  on resume. Audio is flushed on pause.
- **Seek (LEFT, RIGHT, HOME, and space after the end):** the model calls `pipeline.seek()`, which increments an epoch
  counter. The decoder jumps to the new position and tags every later frame with the new epoch. Stages and the model
  drop any frame or audio chunk whose epoch is not the current one, so frames from before the seek never appear. The
  clock is reset and re-anchored on the first frame after the seek.
- **Stop / shutdown:** every thread is a daemon and `stop()` interrupts and joins them all. 

## 4. Why the design is race-free

The pipeline is a chain, so the only waiting is on a full or empty queue and it only waits in one direction. No worker thread ever waits for the JavaFX thread (the model only polls queues and never blocks). Every blocking call has a timeout and checks the stop and epoch flags. `stop()` therefore always finishes.


## 5. Testing and results
Done so far:

**CommandLineControllerTest**: see section 1.

**DecoderDemo** (manual, src/test): decoded 20 frames in epoch 0, requested a seek to 60s, and every frame after the seek was epoch 1 (first was frame 1433, FFmpeg's nearest decodable point before the target) with no stale frame appearing. stop() returned and the program exited.

**StageDemo** (manual, src/test): decoder thread plus one stage running bleed and vignette, 100 frames in 1687ms = 16.9ms per frame. For comparison, the same work in sequence in the supplied player is estimated at about 28ms (about 13ms for decode plus the JavaFX image conversion, plus about 15ms of effects). Both parts are estimates from sections 2.1 and 2.2, and the decode-only cost is lower than 13ms (see PipelineDemo below). The number includes JIT warm-up, as it starts cold, and is one run.


**PipelineDemo** (manual, src/test): the full chain, a decoder thread plus one thread per effect, with no display,
audio or JavaFX conversion. It reads the same command-line options as the player and measures the time to produce 150
frames. "Effect work" is the sum of the single-effect costs from section 2.1, as an estimate of the work per frame.

| Options | Effects | Effect work | Pipeline ms per frame |
|---|---|---|---|
| `-bv` | 2 | about 15 ms | 9.1 |
| `-bvbv` | 4 | about 30 ms | 10.6 |
| `-bvwmy` | 5 | about 28 ms | 10.4 |
| `-bvbvbv` | 6 | about 46 ms | 12.7 |
| `-bvbvbvbv` | 8 | about 61 ms | 13.5 |
| TODO: confirm options | 12 | TODO | 18.4 |
| `-bvwmyfjdpsnbbvwmyfjdpsnb` (every effect, twice) | 24 | about 75 ms | 16.4 |
| `-bvbvbvbvbvbvbvbv` | 16 | about 122 ms | 25.5 |

Findings:

- The per-frame time stays close to the decode cost (9 to 11ms) up to 5 or 6 effects, because the slowest single
  stage sets the pace, not the number of stages.
- After that it rises roughly in proportion to the total work. Dividing CPU work per frame (effects plus about 10ms
  decode) by the time per frame gives about 5 to 5.5 cores busy out of 6 physical cores for the long stacks. This is
  an estimate derived from the table, not a measurement, but it fits the curve and is the expected behaviour of a
  CPU-bound pipeline.
- The supplied single-threaded player falls behind at about 30ms of effect work (section 2.2). This pipeline runs
  16 heavy effects (about 122 ms of work) at 25.5ms per frame, which is about 39fps against the 23.98fps required.
- Limits of this test: one cold run per row, no display, audio or window. The real player's limit will be lower.

**Real player (manual):** the same video, run through `mvn javafx:run` with the console headroom trace.

| Effects | Supplied single-threaded player | Pipelined player |
|---|---|---|
| none | on time | on time (headroom 23 to 39 ms) |
| `-bvbvbv` (6 effects, about 45 ms of work) | every sample late, lateness grew from 0.3 s to 1.3 s | on time (headroom 26 to 39 ms) |
| 24 effects (about 180 ms of work) | not run (collapses far earlier) |

Pause/resume, seek (LEFT/RIGHT/HOME), window resize and stop all worked under the 6-effect load (checked by hand).

### 5.1 Optimising the effects
Once the pipeline was using the cores, the only way to go further was to make the effects cheaper. Every change below
keeps the output pixel-for-pixel identical. I checked this by running the original and the new class on the same
synthetic 1920x1080 frames and comparing a CRC32 of all the output pixels.

| Change | Result |
|---|---|
| **Vignette** and **black and white**: a fast path for 3-channel frames. The supplied loops iterate over the channel count, which is only known at run time, so the JIT cannot unroll them. Spelling the 3-channel case out removes the loop overhead. | about 2.3x (vignette) and 2x (black and white) |
| **Bleed**: skip regions where all four surrounding blurred-mask cells are zero. Their strength is exactly zero, so the original code did rounding just to find that out. | about 35% faster on a dark scene |
 
### 5.2 Things I tried that did not help, and so are not in the final code
 
- Rewriting the vignette over a bulk `byte[]` copy of the frame: no gain, because the copy costs as much as it saves.
- Tiling the vignette loop to keep lookup tables in cache: slower. The simple loops are close to the machine's limit, and my cache-miss theory was wrong.
- A different garbage collector or thread priorities: GC pauses were already under 3ms and priorities made no difference.




## 6. Changes to supplied code


| File | Change |
|---|---|
| `CommandLineController` | Stackable short options, long options, ordered and repeatable effect options, the extended help text, and `createFrameProcessors()`. |
| `VideoPlayerModel` | Removed the single-threaded decode, effect and audio-scheduling code. Decoding and effects run in `FramePipeline`; timing is in `PlaybackClock`; seeking tells the pipeline; the effect list comes from the command line instead of being hard-coded; audio is queued by the decoder and written by the JavaFX thread. |
| `FrameVignetter`, `FrameBlackAndWhiter` | A 3-channel fast path (section 5.4). |
| `FrameBleeder` | Skips regions with no bleed (section 5.4). |
 
New files: `FrameOption`, `FramePipeline`, `FrameDecoder`, `PipelineStage`, `PipelineFrame`, `Audio`, `PlaybackClock`, and in `src/test/java/comp3011`: `CommandLineControllerTest`, `PlaybackClockTest`, and the manual tools `TestTimer`, `DecoderDemo`, `StageDemo`, `PipelineDemo`. `pom.xml` gained JUnit 5 and the Surefire plugin for test purposes.
 
The old single-threaded decode, effect and audio loop is removed, not kept alongside the new code.
