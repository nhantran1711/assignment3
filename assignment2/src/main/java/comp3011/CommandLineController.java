/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Simon Tran. a3157542
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * 
 * CHANGES: stackable options, effect parsing, extended help
 * </p>
 */
public class CommandLineController {
    private final String[] args;

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;
    
    // Store the effects
    private final List<FrameOption> frameProcessorOptions = new ArrayList<>();

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {

        	if (arg.startsWith("--")) {
        		if (!applyOption(arg)) {
        			this.errorMessage = "Unknown option: " + arg;
        		}
        	}
        	
        	// If it starts with "-"
        	else if (arg.startsWith("-"))  {
        		parseShortOptions(arg);
        	}
        	else {
        		videoFiles.add(arg);
        	}
        }

        if (videoFiles.size() > 1) {
            errorMessage = "Usage: VideoPlayer [options] [video-file]";
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile()) {
                errorMessage = "File not found: " + videoFile.getPath();
                videoFile = null;
            }
        } else {
            if (!helpRequested) {
                errorMessage = "No video file specified.";
            }
        }
    }
    
    // Apply option
    private  boolean applyOption(String option) {
    	if (option.equals("-h") || option.equals("--help")) {
    	    helpRequested = true;
    	    
    	} else if (option.equals("-a") || option.equals("--audio")) {
    		
    	    audioRequested = true;
    	} else if (option.equals("-x") || option.equals("--maximise")) {
    		
    	    maximiseRequested = true;
    	} else if (option.equals("-1") || option.equals("--monitor-1")) {
    	
    	    setDisplayId(1);
    	} else if (option.equals("-2") || option.equals("--monitor-2")) {
    		
    	    setDisplayId(2);
    	} else {
    	    return applyFrameProcessorOption(option);
    	}

    	return true;
    }
    
    // Parse short option
    private void parseShortOptions(String arg) {
    	
    	// Check if its a lone "-"
    	if (arg.length() == 1) {
    		this.errorMessage = "Unknown option: " + arg;
    		return;
    	}
    	
    	for (char letter : arg.substring(1).toCharArray()) {
    		if (!applyOption("-" + letter)) {
    			this.errorMessage = "Unknown option: -" + letter; 
    		}
    	}
    }
    
    private boolean applyFrameProcessorOption(String option) {
    	Optional<FrameOption> processor;
    	
    	if (option.startsWith("--")) {
    		processor = FrameOption.fromLongName(option.substring(2));
    	}
    	else {
    		processor = FrameOption.fromShortName(option.charAt(1));
    	}
    	
    	// If not empty
    	if (processor.isPresent()) {
    		this.frameProcessorOptions.add(processor.get());
    		return true;
    	}
    	return false;
    }
    
    // Getters
    public List<FrameOption> getFrameOptions() {
    	return List.copyOf(this.frameProcessorOptions);
    }
    
    // Create frame processors
    public List<FrameProcessor> createFrameProcessors() {
    	List<FrameProcessor> processors = new ArrayList<>();
    	
    	for (FrameOption option: this.frameProcessorOptions) {
    		processors.add(option.create());
    	}
    	
    	return processors;
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        System.out.println();
        
        // New text 
        System.out.println("Frame processors:");
        for (String line: FrameOption.helpLines()) {
        	System.out.println(line);
        }
        System.out.println();
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
    	System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
    	System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
        
        
        
    }
}
