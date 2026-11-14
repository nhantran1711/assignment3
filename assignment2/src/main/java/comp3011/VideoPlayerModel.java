/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Simon Tran a3157542
 *   
 *   
 *   Changes (Simon Tran):
 *   - Removed the single-threaded decode, effects and audio-scheduling code
 *   - Decoding and frame effects now run on worker threads inside FramePipeline.
 *   - Playback timing moved into PlaybackClock.
 *   - Seeking now tells the pipeline, which discards frames from before the seek.
 *   - The frame processor list is passed in from the command line instead of being hard-coded.
 *   - Audio is decoded on the decoder thread, queued with its epoch, and written by the JavaFX thread when the clock says it is due.
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;

import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;

import javafx.scene.image.Image;


public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    // how long to wait between tries at the subtitle server
    private static final long SUBTITLE_RETRY_MS = 5000;
    // longest we hold playback back waiting for the subtitles; they are added mid-play if they come later
    private static final long SUBTITLE_MAX_WAIT_MS = 15000;

    private final List<FrameProcessor> frameProcessors;

    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;
    

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(System.nanoTime());
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on screen.
    
    
   
    private boolean playbackOpen; // True when playback is happening.
    private boolean pauseRequested;
    private boolean audioOutputEnabled;

    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;

    
    // Things that I added
    private final PlaybackClock clock = new PlaybackClock();
    private FramePipeline pipeline;
    private PipelineFrame readyFrame;
    private long readyFrameArrivedTime;


    private long relativeSeekBaseUs = NO_SEEK_REQUEST;
    
    
    private static final long AUDIO_LEAD_NS = 30_000_000L;   // write audio slightly ahead of the clock, as the original did

    private AudioPlayer audioPlayer; // the sound line

    // draws the subtitles. Always in the pipeline, even without -S, so the button can switch it on later
    private final FrameSubtitler subtitler = new FrameSubtitler(false);
    // True while subtitles are being fetched. Only used on the JavaFX thread -> no volatile needed
    private boolean fetchingSubtitles;
    private Audio pendingChunk;
    private int pendingOffset;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameProcessor> frameProcessors,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;

        // Subtitler is always in the pipeline, even if the user hasn't requested subtitles yet, so that the button can switch it on later. The subtitler will just do nothing until the subtitles arrive from the server.
        List<FrameProcessor> all = new ArrayList<>(frameProcessors);
        all.add(subtitler);
        this.frameProcessors = List.copyOf(all);

    }

    // Subtitles on/off. Only flips a volatile flag in the subtitler, so playback is never interrupted
    public void setSubtitlesEnabled(boolean enabled) {
        this.subtitler.setEnabled(enabled);
    }

    public boolean isSubtitlesEnabled() {
        return this.subtitler.isEnabled();
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        fetchSubtitlesThenPlay(file);
    }

    // transcribe the whole video BEFORE playing. Done on a background thread so the window doesn't freeze,
    private void fetchSubtitlesThenPlay(File file) {

        fetchingSubtitles = true;
        notifyStatusChanged("Generating subtitles...");

        // This thread will fetch the subtitles, then start playback. It is a daemon thread so that it doesn't prevent the program from exiting if the user closes the window before it finishes.
        Thread fetcher = new Thread(() -> {

            // A missing file will never work -> don't retry, just let playback report the error
            if (!file.isFile()) {
                startPlaybackLater(null);
                return;
            }

            // Probe the video size early so that the window can be sized correctly before playback starts. This is done on the fetcher thread so that it doesn't block the JavaFX thread, which would freeze the window. The result is passed to the JavaFX thread via Platform.runLater().
            probeVideoSize(file);

            // Set by whichever happens first: the subtitles arriving, a failure, or the wait running out
            AtomicBoolean playbackStarted = new AtomicBoolean(false);

            // A slow server must not keep the user staring at an empty window: after a while, play without subtitles
            Thread waitLimit = new Thread(() -> {
                try {
                    Thread.sleep(SUBTITLE_MAX_WAIT_MS);
                } catch (InterruptedException stop) {
                    return;
                }
                if (playbackStarted.compareAndSet(false, true)) {
                    startPlaybackLater(file.getName() + " (subtitles still generating...)");
                }
            }, "subtitle-wait-limit");
            waitLimit.setDaemon(true);
            waitLimit.start();

            // Keep trying until the subtitles arrive, so they resume once the network comes back
            while (true) {
                try {
                    subtitler.setSubtitles(SubtitleClient.fetchSubtitles(file)); // hand them to the pipeline thread
                    waitLimit.interrupt();

                    if (playbackStarted.compareAndSet(false, true)) {
                        startPlaybackLater(null); // arrived in time: play with subtitles from the start
                    } else {
                        Platform.runLater(() -> notifyStatusChanged(file.getName() + " (subtitles ready)"));
                    }
                    return;

                } catch (Exception e) {
                    System.err.println("Subtitles failed, retrying in 5s: " + e.getMessage());

                    // First failure: don't keep the user waiting, play without subtitles for now
                    if (playbackStarted.compareAndSet(false, true)) {
                        waitLimit.interrupt();
                        startPlaybackLater(file.getName() + " (no subtitles yet, retrying...)");
                    }
                }

                try {
                    Thread.sleep(SUBTITLE_RETRY_MS); // give the network time to come back
                } catch (InterruptedException stop) {
                    return;
                }
            }
        }, "subtitle-fetcher");

        fetcher.setDaemon(true);
        fetcher.start();
    }

    // Called from the JavaFX thread to start playback after the subtitles have been fetched. The status message is optional, and is null if the first fetch worked.
    private void probeVideoSize(File file) {

        try (FFmpegFrameGrabber probe = new FFmpegFrameGrabber(file)) {

            probe.start();

            int width = probe.getImageWidth();
            int height = probe.getImageHeight();

            // If the video size is valid, notify the JavaFX thread to update the window size. This is done on the JavaFX thread via Platform.runLater() to avoid blocking the fetcher thread.
            if (width > 0 && height > 0) {
                Platform.runLater(() -> notifyVideoSizeChanged(width, height));
            }
        } catch (Exception e) {
            // Not fatal: the window is sized again when playback opens the file
            System.err.println("Could not read video size early: " + e.getMessage());
        }
    }

    // Called from the fetcher thread: on the JavaFX thread, end the fetch and start the video (status is optional)
    private void startPlaybackLater(String status) {
        
        Platform.runLater(() -> {
            fetchingSubtitles = false;
            startPlayback(0, false, NO_SEEK_REQUEST);
            if (status != null) {
                notifyStatusChanged(status);
            }
        });
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;
        
        long now = System.nanoTime();
        if (pauseRequested) {
            this.clock.pause(now);
            flushAudio();
        } else {
            this.clock.resume(now);
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        flushAudio();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        this.pipeline.seek(grabTimestampUs);
        this.readyFrame = null;
        this.pendingChunk = null;
        flushAudio(); // make sure that its stop
        
        
        this.clock.reset(logicalTimestampUs, pauseRequested);
        this.currentTimestampUs = logicalTimestampUs;
        this.relativeSeekBaseUs = logicalTimestampUs;
        
        notifyPlaybackStateChanged();
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        // Keys like SPACE or LEFT still work when the buttons are disabled -> block them here, in one place
        if (fetchingSubtitles) {
            return;
        }
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            
            clock.reset(currentTimestampUs, this.pauseRequested);

            playbackOpen = true;
            playbackTimer.start();

            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile);
        try {
            grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
            grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            grabber.start();

            notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

            frameRate = grabber.getFrameRate();
            intFrameRate = (int) Math.round(frameRate);
            videoFrameDurationUs = frameRate > 0
                    ? (int) Math.round(1_000_000.0 / frameRate)
                    : 0;


            long durationUs = grabber.getLengthInTime();
            videoDurationUs = durationUs > 0
                    ? durationUs
                    : NO_SEEK_REQUEST;

            if (startTimestampUs > 0) {
                grabber.setTimestamp(startTimestampUs);
            }
        } catch (Exception e) {
        	try {
        		grabber.close();
        	}
        	
        	catch (Exception ignored) {}
        	throw e;
        }
        
        // From here, pipelien's decoder thread may touch the grabber, possibly  close it
        this.pipeline = new FramePipeline(grabber, mediaName(videoFile), this.frameProcessors);
        this.pipeline.start();
        
        // Check if the pipeline frame has audio chunks
        if (this.pipeline.hasAudio()) {
        	this.audioPlayer = new AudioPlayer();
        	this.audioPlayer.open(this.pipeline.audioSampleRate(), this.pipeline.audioChannelCount());
        }
    }


    

    // This is called on a heart beat by the GUI thread. We want to be co-operative here by (a) not blocking, and (b)
    // getting our required work out of the way quickly so that we can return control flow to the JavaFX event loop for
    // handling user interaction with the GUI and rendering. We have three jobs: (1) keep audio flowing if sound is on,
    // (2) display a frame if it is due and (3) prepare the next frame if we're in the window after the previous frame
    // has gone to the display. We don't buffer frames here, just handling them one at a time. Not a great architecture,
    // living on the edge a bit, but can't do much better on a single thread.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }
        

        // Worker thread dies lieave its error, its make sure to collec tit
        Exception failure = this.pipeline.getFailure();
        if (failure != null) {
        	handlePlaybackError(failure);
        	return;
        }

        // Task (1)
        writeDueAudio(now);

        // Task (2)
        fetchReadyFrame();
        
        if (this.readyFrame == null) {
        	return;
        }
        
        // If its reach the end
        if (this.readyFrame.isEnd() ) {
        	finishPlayback();
        	return;
        }
        
        if (this.pauseRequested && clock.isStarted()) {
        	return;
        }
        
        // First frame after start or seek aanchors the clcok
        if (!clock.isStarted()) {
        	clock.startAt(this.readyFrame.info().timestampUs(), now);
        }

        // Task (3). We'll try and be nice to the GUI event loop here by queueing the prepareNextFrame call rather than
        // hogging the thread and doing it here, hence the tricky callback and use of prepareNextFrameQueued. Feel free
        // to replace this whole block with simple linear control flow logic
        // such as: if (!pauseRequested && preparedFrame == null) prepareNextFrame();
        // to compare.
        
        if (clock.targetTimeNs(this.readyFrame.info().timestampUs()) <= now)  {
        	skipLateFrames(now);
        	displayReadyFrame();
        	fetchReadyFrame();
        }
    }

    // Skip late frames until we find one that is due, or the end of the stream
    private void skipLateFrames(long now) {

    	while (true) {
    		PipelineFrame next = this.pipeline.output().peek();

            // No more frames left
    		if (next == null) {
    			return;
    		}

    		// Left over from before a seek -> throw it away and look again
    		if (next.epoch() != this.pipeline.currentEpoch()) {
    			this.pipeline.output().poll();
    			continue;
    		}
    		if (next.isEnd() || clock.targetTimeNs(next.info().timestampUs()) > now) {
    			return;
    		}

    		// The next frame is due too -> drop the current one
    		this.readyFrame = this.pipeline.output().poll();
    		this.readyFrameArrivedTime = now;
    	}
    }
    
    // Due video method
    private void writeDueAudio(long now) {
    	// Check if avaialbe
    	if (this.audioPlayer == null || this.pauseRequested || !this.clock.isStarted()) {
    		return;
    	}
    	
    	long dueTimeStamp = this.clock.mediaTimestampUsAt(now + AUDIO_LEAD_NS);
    	
    	// Inif while loop
    	while (true) {
    		// No more left
    		if (this.pendingChunk == null) {
    			Audio next = this.pipeline.audioQueue().poll();
    			
    			if (next == null) {
    				return;
    			}
    			
    			if (next.epoch() != this.pipeline.currentEpoch()) {
    				continue;
    			}
    			
    			// Move and reset
    			this.pendingChunk = next;
    			this.pendingOffset = 0;
    		}
    		
    		// Check if its due
    		if (this.pendingChunk.timestampUs() > dueTimeStamp) {
    			return;
    		}
    		
    		// Has audio enable
    		if (this.audioOutputEnabled) {
    			byte[] samples = this.pendingChunk.samples();
    			
    			int writtinInt = this.audioPlayer.write(samples, this.pendingOffset, samples.length - this.pendingOffset);
    			
    			// Sound line is full
    			if (writtinInt <= 0) {
    				return;
    			}
    			
    			this.pendingOffset += writtinInt;
    			
    			if (this.pendingOffset < samples.length) {
    				return;
    			}
    		}
    		
    		// Done audio
    		this.pendingChunk = null;
    	}
    	
    }
    
    
    private void fetchReadyFrame() {
    	while (this.readyFrame == null) {
    		PipelineFrame nextNode = this.pipeline.output().poll();
    		
    		if (nextNode == null) {
    			return;
    		}
    		
    		if (nextNode.epoch() == this.pipeline.currentEpoch()) {
    			this.readyFrame = nextNode;
    			this.readyFrameArrivedTime = System.nanoTime();
    		}
    	}
    }

    private void displayReadyFrame() {
        PipelineFrame frame = this.readyFrame;
        this.readyFrame = null;
        
        long timeStamp = frame.info().timestampUs();
        currentTimestampUs = clock.logicalTimestampUs(timeStamp);
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be monitored.
        if (intFrameRate > 0 && frame.info().frameNumber() % intFrameRate == 0) {
            long remainingUs = (clock.targetTimeNs(timeStamp) - this.readyFrameArrivedTime) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(this.converter.convert(frame.frame()));
    }


    private void finishPlayback() {
        
        // the file is gone -> a clear message
        if (!videoFile.isFile()) {
            mediaLost();
            return;
        }

        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();

        // the file is gone -> a clear message
        if (!videoFile.isFile()) {
            mediaLost();
            return;
        }

        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    // media file lost. Stop the threads but keep the position
    private void mediaLost() {
        closePlaybackResources();
        pauseRequested = true;
        notifyStatusChanged("Media file lost: " + videoFile.getName() + " - reconnect it, then press play");
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;
        readyFrame = null;


        if (this.pipeline != null) {
        	this.pipeline.stop();
        	this.pipeline = null;
        }
        
        // Audio chunk
        this.pendingChunk = null;
        if (this.audioPlayer != null) {
        	this.audioPlayer.close();
        	this.audioPlayer = null;
        }
        
        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }
    
    // Flush audio helper
    private void flushAudio() {
    	if (this.audioPlayer != null) {
    		this.audioPlayer.flush();
    	}
    }



    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }


}
