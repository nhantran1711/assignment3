package comp3011;

import java.io.File;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;

public class DecoderDemo {
    public static void main(String[] args) throws Exception {
    	
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(new File(args[0]));
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        
        grabber.start();

        FrameDecoder decoder = new FrameDecoder(grabber, "simpsons");
        decoder.start();

        for (int i = 0; i < 20; ) {
            PipelineFrame frame = decoder.pollCurrent();
            if (frame != null) {
                System.out.println("frame " + frame.info().frameNumber() + " epoch " + frame.epoch());
                i++;
            }
        }

        System.out.println("--- seeking to 60 s ---");
        decoder.seek(60_000_000L);

        for (int i = 0; i < 5; ) {
            PipelineFrame frame = decoder.pollCurrent();
            if (frame != null) {
                System.out.println("frame " + frame.info().frameNumber() + " epoch " + frame.epoch());
                i++;
            }
        }

        decoder.stop();
        System.out.println("STOPPED");
    }
}