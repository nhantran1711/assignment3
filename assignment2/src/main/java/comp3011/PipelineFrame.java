package comp3011;

import org.bytedeco.javacv.Frame;

/** A frame travelling through the pipeline
 * Also, what stages need to know about it. 
 * */

public record PipelineFrame(Frame frame, InfoFrame info, int epoch) {
	
	
	// Marker: the video has ended
	public static PipelineFrame endOfStream(int epoch) {
		return new PipelineFrame(null, null, epoch);
	}
	
	public boolean isEnd() {
		if (frame == null) {
			return  true;
		}
		return false;
	}
}