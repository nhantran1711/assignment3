package comp3011;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * One step of the pipeline, on its own thread. 
 * Takes frames from the input queue, runs its processors over each in order, and passes them on. 
 * Its processors are used by this thread only 
 * Frames from before the latest seek are dropped.
 */

public class PipelineStage implements Runnable{
	
	private static final long POLL_TIMEOUT_MS = 20;
	
	private final String name;
    private final List<FrameProcessor> processors;
    private final BlockingQueue<PipelineFrame> input;
    private final BlockingQueue<PipelineFrame> output;
    private final IntSupplier currentEpoch;
    
    
    private Thread thread;
    private volatile boolean stopRequested;
    private volatile Exception failure;
	
	public PipelineStage(String name, List<FrameProcessor> processors, BlockingQueue<PipelineFrame> input, BlockingQueue<PipelineFrame> output, IntSupplier currentEpoch) {
		this.name = name;
		this.processors = List.copyOf(processors);
        this.input = input;
        this.output = output;
        this.currentEpoch = currentEpoch;
	}
	
	// Start the pipeline
	public void start() {
		this.thread = new Thread(this, name);
		thread.setDaemon(true);
		thread.start();
	}
	
	// Stop the thread
	public void stop() {
		this.stopRequested = true;
		
		if (this.thread != null) {
			thread.interrupt();
			
			try {
				thread.join();
			}
			
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		
	}
	
	private boolean isStale(PipelineFrame frame) {
		if (frame.epoch() != this.currentEpoch.getAsInt()) {
			return true;
		}
		return false;
	}
	
	// Waits for downstram room, gives up if frame goes stale or user request quit
	private void publish(PipelineFrame frame) throws InterruptedException {
		
		while (!this.stopRequested && !isStale(frame)) {
			if (this.output.offer(frame, POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				return;
			}
		}
	}
	
	
	@Override
	public void run() {
		
		try {
			while (!this.stopRequested) {
				PipelineFrame frame = input.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			
				// nothing waiting or frame got stale
				if (frame == null || isStale(frame)) {
					continue;
				}
				
				// If the frame is not end of stream yet
				if (!frame.isEnd()) {
					for (FrameProcessor p : this.processors) {
						p.process(frame.frame(), frame.info());
					}
				}
				publish(frame);
			}
		}
		
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		catch (Exception e) {
			this.failure = e;
		}
	}
	
	// Getters
	public Exception getFailure() {
		return this.failure;
	}
}
