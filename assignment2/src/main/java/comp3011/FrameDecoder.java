
/** 
 * Name: Simon Tran a3157542
 * */

package comp3011;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

/** 
 * Decodes video on its thread
 */

public class FrameDecoder implements Runnable{
	
	
	private static final int QUEUE_CAPACITY = 4;
	private static final long IDLE_POLL_MS = 50;
	private static final long HAND_OFF_TIMEOUT_MS = 20;
	
	
	private final AtomicInteger latestEpoch = new AtomicInteger();
	private final BlockingQueue<Seek> seekRequests = new LinkedBlockingQueue<>();
	
	
	private final FFmpegFrameGrabber grabber;
	private Thread thread;
	private volatile boolean isStopRequested;
	private volatile Exception failure;
	private final BlockingQueue<PipelineFrame> output = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
	private int currentEpoch;   // touched only by the decoder thread
	
	// This gonna be use to  proof its faster
	private final String mediaName;
	private InfoVideo videoInfo;
	private PipelineFrame firstFrame;
	
	// Fieild for audio
	private final BlockingQueue<Audio> audioOutput = new LinkedBlockingQueue<>();
	
	private final AudioPlayer sampleConverter = new AudioPlayer();
	
	private boolean hasAudio;
	private int audioSampleRate;
	private int audioChannelCount;
	
	private record Seek(long timestampUs, int epoch) {
    }
	
	
	public FrameDecoder(FFmpegFrameGrabber grabber, String mediaName) {
		this.grabber = grabber;
		this.mediaName = mediaName;
	}
	
	// Create new thread and start, Decode the  first frame on the calling thread
	public void start() throws Exception {
		
		try {
			// Get audio
			this.hasAudio = this.grabber.hasAudio();
			this.audioSampleRate  = this.grabber.getSampleRate();
			this.audioChannelCount = this.grabber.getAudioChannels();
			
			// Use the loop instead to check for null
			Frame first;
			while ((first = this.grabber.grab()) != null && first.image == null) {
				queueAudio(first, 0);
			}
			
			
			// Check if null
			if (first == null) {
				throw new IllegalStateException("No video frame!");
			}
			
			double frameRate = this.grabber.getFrameRate();
			
			// New video  info
			int roundedFrameRate = (int) Math.round(frameRate);
			int frameDurationUs = 0;

			if (frameRate > 0) {
			    frameDurationUs = (int) Math.round(1_000_000.0 / frameRate);
			}

			videoInfo = new InfoVideo(
			        mediaName, grabber.getLengthInVideoFrames(),
			        first.imageWidth, first.imageHeight, first.imageDepth,
			        first.imageChannels, first.imageStride,
			        frameRate, roundedFrameRate,
			        frameDurationUs,
			        grabber.getPixelFormat());
			
			firstFrame = new PipelineFrame(first.clone(), new InfoFrame(grabber.getFrameNumber(), grabber.getTimestamp()), 0);
			
			this.thread = new Thread(this, "frame-decoder");
			
			this.thread.setDaemon(true);
			this.thread.start();
		} catch (Exception e) {
			closeGrabber();
			throw e;
		}

	}
	
	// Ask the thread to finish and wait until its actualyl finish, grabber closes
	public void stop() {
		
		this.isStopRequested = true;
		
		// If its running
		if (this.thread != null) {
			this.thread.interrupt();
			
			try {
				this.thread.join();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}
	
	// Call from JAVAFX thread only
	public void seek(long timeStamp) {
		int epoch = this.latestEpoch.incrementAndGet();
		this.seekRequests.add(new Seek(timeStamp, epoch));
	}
	
	// Poll the next frame from the current epoch, null if none
	public  PipelineFrame pollCurrent() {
		
		PipelineFrame cur;
		
		while ((cur = this.output.poll()) != null) {
			
			if (cur.epoch() == this.latestEpoch.get()) {
				return cur;
			}
		}
		
		return null;
	}
	
	@Override
	public void run() {
		
		
		try {
			
			publish(firstFrame);
			boolean isEnd = false;
			
			// While we have ask to stop
			while (!this.isStopRequested) {
				// Return the newest queued tasks
				long waitTime = 0;

				if (isEnd) {
				    waitTime = IDLE_POLL_MS;
				}

				Seek seek = takeLatest(waitTime);
				
				// If we have a task
				if (seek != null) {
				    this.grabber.setTimestamp(seek.timestampUs());
				    this.currentEpoch = seek.epoch();
				    this.output.clear();
				    this.audioOutput.clear();
				    isEnd = false;
				}
				
				// Video fiished, gotta wait here
				if (isEnd) {
					continue;
				}
				
				// If the frame
				Frame frame = this.grabber.grab();
				if (frame == null) {
					isEnd = true;
					publish(PipelineFrame.endOfStream(currentEpoch));
					continue;
				}
				
				// Queue audio here
				queueAudio(frame, this.currentEpoch);
				if (frame.image == null) {
				    continue;   // an audio-only frame
				}
				
				// Get info
				InfoFrame info = new InfoFrame(this.grabber.getFrameNumber(), grabber.getTimestamp());
				publish(new PipelineFrame(frame.clone(), info, currentEpoch));
				
				
			}
		}
		
		// If error, interrupt the current thread
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} 
		
		catch (Exception e)  {
			this.failure = e;
		}
		finally {
			// Close after done
			closeGrabber();
		}
	}
	
	// Blocks while the queue is full, but gives up if a seek or stop arrives
    private void publish(PipelineFrame frame) throws InterruptedException {
    	
        while (!this.isStopRequested && seekRequests.isEmpty()) {
        	
            if (output.offer(frame, HAND_OFF_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return;
            }
            
        }
    }
	
	private Seek takeLatest(long waitMs) throws InterruptedException {
		Seek latest = this.seekRequests.poll(waitMs, TimeUnit.MILLISECONDS);
		
		// If none return, drop the older ones
		if (latest == null) {
			return null;
		}
		
		Seek next;
		while ((next = this.seekRequests.poll()) != null) {
			latest = next;
		}
		return latest;
	}
	
	// Converts a decoded audio from to PCM bytes and queues
	private void queueAudio(Frame frame, int epoch) {
		
		// Check empty
		if (!this.hasAudio || frame.samples == null) {
			return;
		}
		
		// Queue
		byte[] samples = this.sampleConverter.copySamples(frame);
		
		if (samples.length > 0) {
			audioOutput.add(new Audio(this.grabber.getTimestamp(), samples, epoch));
		}
	}
	

	// Stop Grabber then fully closing it
	private void closeGrabber() {
		
		try {
			grabber.stop();
		}
		catch (Exception e) {
			// DO NOTHING 
		}
		
		try {
			grabber.close();
		}
		catch  (Exception e) {
			// DO NOTHING
		}
	}
	
	
	// Get failure 
	public Exception getFailure()  {
		return this.failure;
	}
	
	// Accessors
	public InfoVideo videoInfo() {
		return this.videoInfo;
	}
	
	public BlockingQueue<PipelineFrame> outputQueue() { 
		return this.output; 
	}
	
	public int  currentEpoch() {
		return this.latestEpoch.get();
	}
	
	public BlockingQueue<Audio> audioQueue() {
		return this.audioOutput;
	}
	
	
	public boolean hasAudio() {
		return this.hasAudio;
	}
	
	public int audioSampleRate() {
		return this.audioSampleRate;
	}
	
	public int audioChannelCount() {
		return this.audioChannelCount;
	}
	
}
