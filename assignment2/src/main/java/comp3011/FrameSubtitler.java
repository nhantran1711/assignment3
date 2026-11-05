package comp3011;

/*
 * NEW FILE (Assignment 3)
 *
 * Author: Simon Tran a3157542
 *
 * Purpose: Frame processor that draws the subtitle being spoken at the frame's timestamp onto the frame
 * Runs as the LAST processor, so effects like jitter or black and white never distort the text.
 */

import org.bytedeco.javacv.Frame;

public class FrameSubtitler extends FrameProcessor {

	// Set from other threads (subtitle-fetcher, JavaFX button) but read on a pipeline thread -> volatile
	private volatile SubtitleClient.Subtitles subtitles;
	private volatile boolean enabled;

	public FrameSubtitler(boolean enabled) {
		this.enabled = enabled;
	}

	public void setSubtitles(SubtitleClient.Subtitles subtitles) {
		this.subtitles = subtitles;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isEnabled() {
		return enabled;
	}

}
