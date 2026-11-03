package comp3011;


/*
 * NEW FILE.
 *
 * Author: Simon Tran a3157542
 *
 * Purpose: Registry of the effect options (short name, long name, description, factory) used for parsing and help text 
 */

import java.util.Optional;
import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public enum FrameOption {
	/**
	 * Every effect that can be discovered by the CLI. 
	 * To add a new Frame, write a subclass, add one constant with an unused letter, long name, constructor
	 * Parsing needs ZERO change
	 */
	
	NUMBER_FRAMES('n', "number-frames", "Render the frame number onto each frame", FrameNumberer::new),
	SCRATCH_FRAMES('s', "scratch-frames", "Render vertical film scratches", FrameScratcher::new),
	FLICKER_FRAMES('f', "flicker-frames", "Randomly dim frames", FrameFlickerer::new),
	BLACK_AND_WHITE('w', "black-and-white", "Convert frames to black and white", FrameBlackAndWhiter::new),
	YELLOW_FRAMES('y', "yellow-frames", "Apply a warmer colour temperature", FrameYellower::new),
	VIGNETTE_FRAMES('v', "vignette-frames", "Darken the frame edges", FrameVignetter::new),
	DUST_FRAMES('d', "dust-frames", "Render dust and hair marks", FrameDuster::new),
	JITTER_FRAMES('j', "jitter-frames", "Randomly displace frames by a few pixels", FrameJitterer::new),
	MOTTLE_FRAMES('m', "mottle-frames", "Add cloudy emulsion mottling", FrameMottler::new),
	BLEED_FRAMES('b', "bleed-frames", "Bleed light into frames", FrameBleeder::new),
	PEPPER_FRAMES('p', "pepper-frames", "Pepper frames with dark spots/blotches", FramePepperer::new);
	
	private final char shortName;
	private final String longName;
	private final String description;
	private final Supplier<FrameProcessor> factory;
	
	FrameOption(char shortName, String longName, String description, Supplier<FrameProcessor> factory) {
		this.shortName = shortName;
		this.longName = longName;
		this.description = description;
		this.factory = factory;
	}
	
	// Create frame processor
	public FrameProcessor create() {
		return this.factory.get();
	}
	
	// Check if the effect is short anme
	public static Optional<FrameOption> fromShortName(char shortName) {
		
		// check the options values and compare it with the enum values
		for (FrameOption option: values()) {
			if (option.shortName == shortName)  {
				return Optional.of(option);
			}
		}
		// No null pointer error here hahaha (hopefully...)
		return Optional.empty();
	}
	
	// Check if the effect is long name
	public static Optional<FrameOption> fromLongName(String longName) {
		
		// check the options values and compare it with the enum values
		for (FrameOption option: values()) {
			if (option.longName.equals(longName))  {
				return Optional.of(option);
			}
		}
		// No null pointer error here hahaha (hopefully...)
		return Optional.empty();
	}
	
	// Creating flags by combine short name and long name, returns a String
	public String flags() {
		return "-" + this.shortName + ", --" + this.longName;
	}
	
	public static List<String> helpLines() {
		
		int width =  0;
		
		// Finding the longest width in each options
		for (FrameOption option: values()) {
			width = Math.max(width, option.flags().length());
		}
		
		// Two spaces between flags and description
		width += 2;
		
		List<String> lines = new ArrayList<>();
		
		for (FrameOption option: values()) {
			lines.add("  " +  String.format("%-" + width + "s", option.flags()) + option.description);
		}
		
		return lines;
	}
}
