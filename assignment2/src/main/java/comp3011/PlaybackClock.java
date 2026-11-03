package comp3011;



/**
 * Decides when each frame is due. 
 * Anchored to the first frame shown after a start or seek, and shifted forward by however long playback was paused. \
 * NOT thread-safe on purpose: it is used only by the JavaFX thread 
 * No other thread can ever see it half-updated.
 */
public class PlaybackClock {

	private static final long STARTED = -1;
	
	private long firstTimestamp = STARTED;  // media timestamp of the first frame after reset
    private long logicalBase; // position on the timeline that frame stands for
    private long startNs; // when that frame was due
    private long pausedAtNs; // when the current pause began, 0 if none
    private boolean isPaused;
	
	// Forget the anchor. 
	// The next frame to arrive will start the clock.
	public void reset(long logicalTimeStamp, boolean isPaused) {
		this.firstTimestamp = STARTED;
		this.logicalBase = logicalTimeStamp;
		
		this.startNs = 0;
		this.pausedAtNs = 0;
		
		this.isPaused = isPaused;
		
	}
	
	// Anchors the clock: the frame with this timestamp is due right now
	public void startAt(long firstFrameTimeStamp, long now) {
		
		this.firstTimestamp = firstFrameTimeStamp;
		
		this.startNs = now;
		
		// If is due, set at this time
		if (this.isPaused) {
			this.pausedAtNs = now;
		}
	}
	
	// Check if the thread already started
	public boolean isStarted() {
		return this.firstTimestamp != STARTED;
	}
	
	public void pause(long now) {
		
		// if is pausing, stop
		if (this.isPaused) {
			return;
		}
		// if is starting, put as this current time
		this.isPaused = true;
		if (isStarted()) {
			this.pausedAtNs = now;
		}
	}
	
	public void resume(long now) {
		
		// if its working
		if (!this.isPaused) {
			return;
		}
		
		this.isPaused = false;
		
		// Reset the timer after set the new starting time
		if (isStarted()) {
			this.startNs += now - this.pausedAtNs;
		}
		
		this.pausedAtNs = 0;
	}
	
	
    private long relativeUs(long frameTimestamp) {
        return Math.max(0, frameTimestamp - this.firstTimestamp);
    }
	
	/** The System.nanoTime() value at which a frame with this media timestamp should appear. */
    public long targetTimeNs(long frameTimestamp) {
        return this.startNs + relativeUs(frameTimestamp) * 1_000L;
    }

    /** Where this frame sits on the timeline the user sees (it stays right after a seek). */
    public long logicalTimestampUs(long frameTimestamp) {
        return this.logicalBase + relativeUs(frameTimestamp);
    }
    
    /** The media timestamp that is playing at the given System.nanoTime() value*/
    public long mediaTimestampUsAt(long nowNs) {
        return this.firstTimestamp + (nowNs - startNs) / 1_000L;
    }


}

