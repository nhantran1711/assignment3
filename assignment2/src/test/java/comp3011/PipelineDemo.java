package comp3011;

import java.io.File;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;

public class PipelineDemo  {
    private static final int FRAMES = 150;

    public static void main(String[] args) throws Exception {
    	
    	CommandLineController options = new CommandLineController(args);
    	File video = options.getVideoFile();
    	
    	
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video);
        
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.start();

        FramePipeline pipeline = new FramePipeline(grabber, "simpsons", options.createFrameProcessors());
        pipeline.start();

        long start = System.nanoTime();
        int received = 0;
        
        // Same the the stage demo, but without the decoder itself
        while (received < FRAMES) {
            PipelineFrame frame = pipeline.output().poll();
            
            // Count
            if (frame != null && frame.epoch() == pipeline.currentEpoch()) {
                received++;
            }
        }
        
        long elapsedNs = System.nanoTime() - start;
        System.out.printf("%d effects: %.1f ms per frame%n", options.getFrameOptions().size(), elapsedNs / 1e6 / FRAMES);

        pipeline.stop();
        
        
        System.out.println("stopped cleanly lol");
    }
}