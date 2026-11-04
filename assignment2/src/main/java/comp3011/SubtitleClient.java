package comp3011;

/*
 *
 * Author: Simon Tran a3157542
 *
 * Purpose: Get the subtitles for a video from the web server
 * -> extract the audio track -> send it to POST /api/v1/subtitles and parse the reply. 
 * Run off the JavaFX thread.
 */

import java.io.ByteArrayOutputStream;
import java.io.File;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

public class SubtitleClient {

	// Whisper works at 16kHz mono anyway. At 32 kbps, 25MB (OpenAI's upload limit) holds ~1.7 hours
	private static final int MONO = 1;
	private static final int SAMPLE_RATE = 16000;
	private static final int BITRATE = 32000;

	// Read only the audio from the video file and return it as a small mono mp3 (kept in memory)
	static byte[] extractAudio(File videoFile) throws Exception {
		ByteArrayOutputStream mp3Bytes = new ByteArrayOutputStream();

		// The recorder is closed now, so all of the mp3 has been written out
		return mp3Bytes.toByteArray();
	}

	// An mp3 encoder that writes into the given byte stream: mono, 16kHz, 32 kbps
	private static FFmpegFrameRecorder createMp3Recorder(ByteArrayOutputStream output) {
		
		FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(output, MONO);
		
		recorder.setFormat("mp3");
		recorder.setAudioCodec(avcodec.AV_CODEC_ID_MP3);
		recorder.setSampleRate(SAMPLE_RATE);
		recorder.setAudioBitrate(BITRATE);
		
		return recorder;
	}
}
