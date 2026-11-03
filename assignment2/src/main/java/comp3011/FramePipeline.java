package comp3011;

/**
 * NEW FILE.
 *
 * Author: Simon Tran a3157542
 *
 * Purpose: Registry of the effect options (short name, long name, description, factory) used for parsing and help text 
 */

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.bytedeco.javacv.FFmpegFrameGrabber;

public class FramePipeline {
	
	private static final int QUEUE_CAPACITY = 4;
	private static final int  OUTPUT_QUEUE_CAPACITY = 12;
	
	private final FrameDecoder decoder;
    private final List<FrameProcessor> processors;
    private final List<PipelineStage> stages = new ArrayList<>();
    private BlockingQueue<PipelineFrame> output;
	
    // Constructor
	public FramePipeline(FFmpegFrameGrabber grabber, String mediaName, List<FrameProcessor> processors) {
        this.decoder = new FrameDecoder(grabber, mediaName);
        this.processors = List.copyOf(processors);
    }
	
	
	// Decode the first frame on this thread
	public void start() throws Exception {
		try {
			decoder.start();
			
			// Init every processor, before its stage thread
			for (FrameProcessor p : this.processors) {
				p.initialise(this.decoder.videoInfo());
			}
			
			int stageCount = stageCountFor(this.processors.size());
			int next = 0;
			
			
			// Queue
			BlockingQueue<PipelineFrame> input = this.decoder.outputQueue();	
			
			for (int s = 0; s < stageCount && !this.processors.isEmpty(); s++) {
				
				int size = this.processors.size() / stageCount + (s < this.processors.size() % stageCount ? 1 :0);
				List<FrameProcessor> group = this.processors.subList(next, next + size);
				
				next += size;
				
				boolean lastStage = s == stageCount - 1;
				BlockingQueue<PipelineFrame> cur = new ArrayBlockingQueue<>(lastStage ? OUTPUT_QUEUE_CAPACITY : QUEUE_CAPACITY);
	            stages.add(new PipelineStage("effect-stage-" + s, group, input, cur, decoder::currentEpoch));
	            input = cur;
			}
			
			// Decoder own queue
			this.output = input;
			
			for (PipelineStage stage : this.stages) {
				stage.start();
			}
		} catch (Exception e) {
			stop();
			throw e;
		}
	}
	
	
	// Stop every thread and waitrs for each to finish
	public void  stop() {
		
		for (PipelineStage stage : this.stages) {
			stage.stop();
		}
		
		// Stop the decodre
		this.decoder.stop();
	}
	
	private static int stageCountFor(int effectCount) {
		if (effectCount == 0) {
	        return 0;
	    }
	    int wanted = Integer.getInteger("pipeline.stages", Runtime.getRuntime().availableProcessors());
	    return Math.max(1, Math.min(effectCount, wanted));
	}
	
	// Finished frame came out here
	public BlockingQueue<PipelineFrame> output() {
		return this.output;
	}
	
	// Decoder video info here
	public InfoVideo videoInfo() {
		return this.decoder.videoInfo();
	}
	
	// The current epoch value
	public int currentEpoch() {
		return this.decoder.currentEpoch();
	}
	
	// Timestampe
	public void seek(long timeStamp) {
		this.decoder.seek(timeStamp);
	}
	
	public Exception getFailure() {
		
		// Get every stage's report if applicable
		for (PipelineStage stage : this.stages) {
			
			if (stage.getFailure() != null) {
				return stage.getFailure();
			}
		}
		
		// If we dont have, return its decoder's failure report instead
		return this.decoder.getFailure();
	}
	
	// Getters
	public BlockingQueue<Audio> audioQueue() {
		return this.decoder.audioQueue();
	}
	
	public boolean hasAudio() {
		return this.decoder.hasAudio();
	}
	
	public int audioSampleRate() {
		return this.decoder.audioSampleRate();
	}
	
	public int audioChannelCount() {
		return this.decoder.audioChannelCount();
	}
}
