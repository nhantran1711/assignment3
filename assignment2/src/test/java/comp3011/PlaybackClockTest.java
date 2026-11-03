package comp3011;


/**
 * Name: Simon Tran
 * 
 * Description: Test for  playback clock
 */

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class PlaybackClockTest {

	
	// The first frame to reach the display is due immediately, 
	// Every later frame is due exactly as far after it
    // One frame at 23.98 fps lasts 41,708 us = 41,708,000 ns.
	@Test
	void firstFrameIsDueAndLaterFrameIsSimilar() {
		
		PlaybackClock clock = new PlaybackClock();
		
		clock.reset(0, false);
		clock.startAt(1_000_000L, 5_000_000_000L);
		
		assertEquals(5_000_000_000L, clock.targetTimeNs(1_000_000L));
        assertEquals(5_000_000_000L + 41_708_000L, clock.targetTimeNs(1_041_708L));
	}
	
	
	// Pausing must not make playback race to catch up when it resumes. 
	// After a pause, every frame is due later by the length of the pause 
	// -> playback carries on from where it stopped.
	@Test
	void pausePushesLaterFramesBackByTheSizeOfThePause() {
		
		PlaybackClock clock = new PlaybackClock();
		
		clock.reset(0, false);
		clock.startAt(0, 1_000_000_000L); // Due at 1 nanossec
		
		clock.pause(1_500_000_000L); // paused at 1.5s
		clock.resume(2_500_000_000L); // resumed at 2.5s
		
		// Frame 0 was due at 1s, so it is now due at 1s + 1s of pause = 2s.
		assertEquals(2_000_000_000L, clock.targetTimeNs(0));
	}
	
	
    // Seeking while paused starts a clock that is already paused. 
	// The anchor frame is meant to be due at the start
    @Test
    void aClockStartedWhilePausedWaitsForResume() {
    	
    	PlaybackClock clock = new PlaybackClock();
    	
    	clock.reset(0, true);
    	
    	clock.startAt(0, 1_000_000_000L);
    	clock.resume(4_000_000_000L);
        
    	//(due at 1 s, plus 3 s of pause)
    	assertEquals(4_000_000_000L, clock.targetTimeNs(0));
        
    }
    
    
    // After a seek, the position shown to the user must continue from the seek target, not from the media timestamp
    // FFmpeg often lands a little before the requested position, so the two can differ
    // The logical timeline is anchored to the requested position and then advances by the media
    @Test
    void logicalTimestampStartsAtTheSeekPosition() {
    	
        PlaybackClock clock = new PlaybackClock();
        
        clock.reset(60_000_000L, false);             // user asked for 60s
        clock.startAt(59_500_000L, 0);               // decoder landed at 59.5s, a little early

        // The first frame is treated as being at the requested 60s
        assertEquals(60_000_000L, clock.logicalTimestampUs(59_500_000L));
        
        // The next frame is one frame-duration later on that timeline.
        assertEquals(60_000_000L + 41_708L, clock.logicalTimestampUs(59_541_708L));
    }
    
    
 // Audio is written when its media timestamp is due 
 // So the clock must be able to turn "now" back into a media time.
    @Test
    void mediaTimestampAdvancesWithWallClockTime() {
    	
        PlaybackClock clock = new PlaybackClock();
        
        clock.reset(0, false);
        clock.startAt(1_000_000L, 5_000_000_000L);

        assertEquals(1_000_000L, clock.mediaTimestampUsAt(5_000_000_000L));
        assertEquals(3_000_000L, clock.mediaTimestampUsAt(7_000_000_000L));   // two seconds later
    }
}
