package comp3011;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandLineControllerTest {
	
	@TempDir
	Path tempPath;
	
	private String video;
	
	@BeforeEach
	void createVideo() throws IOException {
		this.video = Files.createFile(tempPath.resolve("movie.mp4")).toString();
	}
	
	private CommandLineController parse(String... options) {
		String[] args = new String[options.length + 1];
		
		for (int i = 0; i < options.length; i++) {
			args[i] = options[i];
		}
		
		args[options.length] = video;
		
		return new CommandLineController(args);
	}
	
	
	@Test
	void noEffectsDefault() {
		
		CommandLineController controller = parse();
		assertTrue(controller.shouldLaunchApplication());
		assertTrue(controller.getFrameOptions().isEmpty());
	}
	
	@Test
	void isStackedAndLongFormTextTheSame() {
		List<FrameOption> expectedList = List.of(FrameOption.JITTER_FRAMES, 
				FrameOption.FLICKER_FRAMES,
				FrameOption.DUST_FRAMES,
				FrameOption.DUST_FRAMES,
				FrameOption.NUMBER_FRAMES);
		
		assertEquals(expectedList, parse("-jfddn").getFrameOptions());
		assertEquals(expectedList, parse("-jf", "--dust-frames", "-d", "--number-frames").getFrameOptions());
		assertEquals(expectedList, parse("-j", "-f", "-d", "-d", "-n").getFrameOptions());
	}
	
	@Test
	void helpTextMatchesSpec() {
		// Map the system output
		PrintStream oriOut = System.out;
		ByteArrayOutputStream capture = new ByteArrayOutputStream();
		
		System.setOut(new PrintStream(capture));
		
		
		try {
			new CommandLineController(new String[] { "--help" } );
		} finally  {
			// Always use the real console output
			System.setOut(oriOut);
		}
		
		
		// EXPECTED OUTPUT: PLEASE MATCH THIS FOR TEST
		String expectedOutput = """
	            Usage: VideoPlayer [options] [video-file]

	            Options:
	              -h, --help         Show this help message
	              -a, --audio        Play audio
	              -x, --maximise     Open the player maximised
	              -1, --monitor-1    Open the player on display 1
	              -2, --monitor-2    Open the player on display 2

	            Frame processors:
	              -n, --number-frames    Render the frame number onto each frame
	              -s, --scratch-frames   Render vertical film scratches
	              -f, --flicker-frames   Randomly dim frames
	              -w, --black-and-white  Convert frames to black and white
	              -y, --yellow-frames    Apply a warmer colour temperature
	              -v, --vignette-frames  Darken the frame edges
	              -d, --dust-frames      Render dust and hair marks
	              -j, --jitter-frames    Randomly displace frames by a few pixels
	              -m, --mottle-frames    Add cloudy emulsion mottling
	              -b, --bleed-frames     Bleed light into frames
	              -p, --pepper-frames    Pepper frames with dark spots/blotches
	              
	            Frame processors are applied in command-line order and may be repeated.
	            Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,
	            converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.
	            """;
		
		assertEquals(expectedOutput, capture.toString().replace("\r\n", "\n"));
	}
	
}