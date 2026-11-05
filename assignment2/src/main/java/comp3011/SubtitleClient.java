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
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

public class SubtitleClient {

	// Whisper works at 16kHz mono anyway. At 32 kbps, 25MB (OpenAI's upload limit) holds ~1.7 hours
	private static final int MONO = 1;
	private static final int SAMPLE_RATE = 16000;
	private static final int BITRATE = 32000;

	// Marks where the file starts and ends inside the request body
	private static final String BOUNDARY = "boundary";

	private static final URI SUBTITLES_URL = URI.create("http://localhost:8080/api/v1/subtitles");

	// The HTTP client is reused for all requests, and has a 5s connection timeout
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	// Read only the audio from the video file and return it as a small mono mp3 (kept in memory)
	static byte[] extractAudio(File videoFile) throws Exception {
		ByteArrayOutputStream mp3Bytes = new ByteArrayOutputStream();

		// try-with-resources: both get closed automatically at the end, even if something throws
		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile);
			
			FFmpegFrameRecorder recorder = createMp3Recorder(mp3Bytes)) {

			grabber.start();
			recorder.start();

			// Read the audio frames from the video and write them to the mp3 recorder
			Frame audioFrame = grabber.grabSamples();

			while (audioFrame != null) {
				recorder.record(audioFrame);
				audioFrame = grabber.grabSamples();
			}
		}

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



	// Create a multipart/form-data request body containing the given mp3 bytes
	private static byte[] multipartBody(byte[] mp3) {
		// The request body is just a header, the mp3 bytes, and a footer
		String header = "--" + BOUNDARY + "\r\n"
				+ "Content-Disposition: form-data; name=\"audio\"; filename=\"audio.mp3\"\r\n"
				+ "Content-Type: audio/mpeg\r\n"
				+ "\r\n";
		String footer = "\r\n--" + BOUNDARY + "--\r\n";

		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.writeBytes(header.getBytes(StandardCharsets.UTF_8));
		body.writeBytes(mp3);
		body.writeBytes(footer.getBytes(StandardCharsets.UTF_8));
		
		return body.toByteArray();
	}

	// Send the mp3 to the web server and return its JSON reply as text
	static String postAudio(byte[] mp3) throws IOException, InterruptedException {

		HttpRequest req = HttpRequest.newBuilder(SUBTITLES_URL)
				.header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
				.timeout(Duration.ofSeconds(200))
				.POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(mp3)))
				.build();

		HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

		// If the server didn't reply with 200 OK, throw an exception with the status code and body
		if (res.statusCode() != 200) {
			throw new IOException("Subtitle server replied " + res.statusCode() + ": " + res.body());
		}

		return res.body();
	}
}
