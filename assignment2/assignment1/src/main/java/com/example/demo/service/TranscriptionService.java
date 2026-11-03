package com.example.demo.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.annotation.JsonProperty;

@Service
public class TranscriptionService {
	
	// No subtitles for segments with noSpeechProb > 0.6 & aveLogProb < -1.0
	private static final double NO_SPEECH_LIMIT = 0.6;
	private static final double LOG_PROB_LIMIT = -1.0;
	
	private final String apiKey;
	private final RestClient restClient;
	private final StatsService statsService;
	private static final Logger log = LoggerFactory.getLogger(TranscriptionService.class);

	// Constructor
	public TranscriptionService(@Value("${openai.api-key}") String apiKey, @Value("${openai.base-url}") String baseUrl, StatsService statsService) {
		this.apiKey = apiKey;
		HttpClient httpclient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpclient); 
		factory.setReadTimeout(Duration.ofSeconds(15));
		
		this.restClient = RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(factory)
				.build();
		this.statsService = statsService;
	}
	
	// Transcribe audio
	public String transcribe(MultipartFile audio) {
		
		// Log transcription
		log.info("Received transcription request. Audio size: " + audio.getSize() + " bytes");
		
		try {
			MultiValueMap<String, Object> map = new LinkedMultiValueMap<>();
			
			map.add("model", "gpt-4o-mini-transcribe");
			map.add("file", audio.getResource());
			
			// Response from OpenAI object
			OpenAITranscriptionResponse res = restClient.post()
					.uri("/v1/audio/transcriptions")
					.header("Authorization", "Bearer " + this.apiKey)
					.contentType(MediaType.MULTIPART_FORM_DATA)
					.body(map)
					.retrieve()
					.body(OpenAITranscriptionResponse.class);
			
			statsService.addTokens(
			        res.usage().inputTokens(),
			        res.usage().outputTokens()
			);
			
			log.info("Transcription succeeded!. Input tokens: " + res.usage().inputTokens() + ". Output tokens: " + res.usage().outputTokens());
			
			return res.text();		
			
		} catch (Exception error) {
			log.error("Transcription request to OpenAI failed", error);
			throw new RuntimeException("Request failed ", error);
		}

	}
	
	private record OpenAITranscriptionResponse(String text, Usage usage) {};
	
	private record Usage(@JsonProperty("input_tokens") long inputTokens, @JsonProperty("output_tokens") long outputTokens) {}

	// POST /api/v1/subtitles sends back to the player
	// Times are seconds from the start of the media
	public record Subtitles(double duration, List<Segment> segments) {}

	public record Segment(double start, double end, String text, List<Word> words) {}

	// The words let the player split a long segment into 42 char lines at the right moment
	public record Word(double start, double end, String word) {}


	// only the fields of whisper's verbose_json we use
	record WhisperResponse(double duration, List<WhisperSegment> segments, List<WhisperWord> words, WhisperUsage usage) {

		// whisper-1 bills by audio seconds. Use the usage block if present, otherwise the audio duration
		double usageSeconds() {
			if (usage != null && usage.seconds() > 0) {
				return usage.seconds();
			};
			return duration;
		}
	}

	record WhisperSegment(double start, double end, String text, @JsonProperty("avg_logprob") double avgLogProb, @JsonProperty("no_speech_prob") double noSpeechProb) {}

	record WhisperWord(double start, double end, String word) {}

	record WhisperUsage(String type, double seconds) {}
}
