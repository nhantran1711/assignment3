package comp3011;

import java.io.File;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;

public class StageDemo {
    private static final int FRAMES = 100;

    public static void main(String[] args) throws Exception {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(new File(args[0]));
        
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.start();

        FrameDecoder decoder = new FrameDecoder(grabber, "simpsons");
        decoder.start();

        // Two heavy effects in ONE stage, then compare with TWO stages later.
        List<FrameProcessor> processors = List.of(FrameOption.BLEED_FRAMES.create(), FrameOption.VIGNETTE_FRAMES.create());
        
        for (FrameProcessor processor : processors) {
        	// before the stage thread starts
            processor.initialise(decoder.videoInfo());
        }
        
        // Queue size 4
        BlockingQueue<PipelineFrame> stageOutput = new ArrayBlockingQueue<>(4);
        
        PipelineStage stage = new PipelineStage("stage-1", processors, decoder.outputQueue(), stageOutput, decoder::currentEpoch);
        
        stage.start();

        long start = System.nanoTime();
        int received = 0;
        
        
        while (received < FRAMES) {
            PipelineFrame frame = stageOutput.poll();
            if (frame != null && frame.epoch() == decoder.currentEpoch()) {
                received++;
            }
        }
        
        long elapsedNs = System.nanoTime() - start;
        System.out.printf("%d frames in %.0f ms = %.1f ms per frame%n", FRAMES, elapsedNs / 1e6, elapsedNs / 1e6 / FRAMES);

        stage.stop();
        decoder.stop();
        
        
        System.out.println("stopped cleanly lol");
    }
}