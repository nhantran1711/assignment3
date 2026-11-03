package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

public class TestTimer {
	private static final int WARM_UP_FRAMES = 10;
	private static final int TIMED_FRAMES = 30;
	
	public static void main(String[] args) throws Exception {
		// FIle and grabber
		File file = new File(args[0]);
		FFmpegFrameGrabber  grabber = new FFmpegFrameGrabber(file);
		
		// Set image then start
		grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
		grabber.start();
		
		// Frame
		List<Frame> frames = new ArrayList<>();
		
		// Video info
		InfoVideo info = null;
		
		
		while (frames.size() < WARM_UP_FRAMES + TIMED_FRAMES) {
			Frame curFrame = grabber.grabImage();
			
			// IF EMPTY
			if (curFrame == null) {
				break;
			}
			
			// IF frame info is empty
			if (info == null) {
				info = new InfoVideo(
						file.getName(),
						grabber.getLengthInVideoFrames(),
						curFrame.imageWidth,
						curFrame.imageHeight,
						curFrame.imageDepth,
						curFrame.imageChannels,
						curFrame.imageStride,	
						grabber.getFrameRate(),
						(int) Math.round(grabber.getFrameRate()),
						(int) Math.round(1_000_000.0 / grabber.getFrameRate()),
						grabber.getPixelFormat());
			}
			
			frames.add(curFrame.clone());
		}
		
		grabber.stop();
		grabber.close();
		
		// TIME EACH EFFECT
		System.out.printf("Frame budget at %.2f fps: %.1f ms%n%n",
                info.frameRate(), 1000.0 / info.frameRate());

        for (FrameOption option : FrameOption.values()) {
            FrameProcessor processor = option.create();
            processor.initialise(info);
            
            long total = 0;
            for (int i = 0; i < frames.size(); i++) {
                Frame copy = frames.get(i).clone();   // fresh pixels for every run, not timed
                long start = System.nanoTime();
                
                processor.process(copy, new InfoFrame(i, i * 41_667L));
                
                long elapsedTime = System.nanoTime() - start;
                
                if (i >= WARM_UP_FRAMES) {
                    total += elapsedTime;
                }
            }
            System.out.printf("%-16s %6.2f ms per frame%n", option.name(), total / 1e6 / TIMED_FRAMES);
        }
	}
}
