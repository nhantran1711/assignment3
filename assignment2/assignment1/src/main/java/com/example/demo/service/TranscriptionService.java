package com.example.demo.service;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
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

import tools.jackson.databind.json.JsonMapper;

@Service
public class TranscriptionService {
	
	// No subtitles for segments with noSpeechProb > 0.6 & aveLogProb < -1.0
	private static final double NO_SPEECH_LIMIT = 0.6;
	private static final double LOG_PROB_LIMIT = -1.0;

	// Disk cache: one JSON file per audio track
	private static final Path CACHE_DIR = Path.of("subtitle-cache");
	private static final JsonMapper JSON = JsonMapper.builder().build();
	
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
		// Whisper on a whole movie's audio track can take >= 1 min. Use 3 minutes to be safe as a buffer
		factory.setReadTimeout(Duration.ofSeconds(180));
		
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
	
	// transcribe the whole audio track of a video with whisper-1, with word and segment timings
	public Subtitles transcribeSubtitles(MultipartFile audio) {
		log.info("Received subtitle request. Audio size: " + audio.getSize() + " bytes");

		// Seen this audio before
		Path cacheFile = cacheFileFor(audio);
		Subtitles cached = readCache(cacheFile);

		// If we have a cached version, return it
		if (cached != null) {
			log.info("Cache hit: " + cacheFile.getFileName());
			return cached;
		}

		MultiValueMap<String, Object> map = new LinkedMultiValueMap<>();
		map.add("model", "whisper-1");
		map.add("file", audio.getResource());
		map.add("response_format", "verbose_json");
		map.add("timestamp_granularities[]", "word");
		map.add("timestamp_granularities[]", "segment");

		// Response from OpenAI object
		WhisperResponse res = restClient.post()
				.uri("/v1/audio/transcriptions")
				.header("Authorization", "Bearer " + this.apiKey)
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(map)
				.retrieve()
				.body(WhisperResponse.class);

				
		// If the response is null, throw an exception
		if (res == null) {
			throw new IllegalStateException("Whisper returned an empty body");
		}
		// If the response has no segments, throw an exception
		if (res.segments() == null) {
			throw new IllegalStateException("Whisper returned no segments");
		}

		statsService.addUsageSeconds(res.usageSeconds());

		// Build segments from the response
		List<Segment> segments = buildSegments(res);
		log.info("Subtitle request succeeded. " + segments.size() + " segments, " + res.usageSeconds() + "s billed");

		Subtitles subtitles = new Subtitles(res.duration(), segments);
		writeCache(cacheFile, subtitles);
		return subtitles;
	}

	
	// Return a path to the cache file for this audio track
	private static Path cacheFileFor(MultipartFile audio) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(audio.getBytes());

			return CACHE_DIR.resolve(HexFormat.of().formatHex(hash) + ".json");
		} catch (IOException | NoSuchAlgorithmException e) {
			throw new IllegalStateException("Could not read the uploaded audio", e);
		}
	}


	// Return a path to the cache file for this audio track
	private static void writeCache(Path cacheFile, Subtitles subtitles) {
		try {
			Files.createDirectories(CACHE_DIR);
			JSON.writeValue(cacheFile.toFile(), subtitles);
		} catch (Exception e) {
			log.warn("Could not write cache file " + cacheFile + ": " + e.getMessage());
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

	static boolean isSpeech(WhisperSegment seg) {
		// Within the spec
		boolean probablyNotSpeech = seg.noSpeechProb() > NO_SPEECH_LIMIT && seg.avgLogProb() < LOG_PROB_LIMIT;

		// If the segment is probably not speech, return false
		if (probablyNotSpeech) {
			return false;
		}

		// If the segment has no text, return false
		if (seg.text() == null || seg.text().isBlank()) {
			return false;
		}

		return true;
	}

	// Keep only speech segments, and give each one the words spoken inside it
	static List<Segment> buildSegments(WhisperResponse res) {
		// Build a list of segments with words inside them
		List<Segment> list_of_segments = new ArrayList<>();

		// Whisper omits words/segments on silent audio -> treat as empty
		List<WhisperWord> allWords;
		if (res.words() == null) {
			allWords = List.of();
		} else {
			allWords = res.words();
		};
		
		// If the response has no segments, return an empty list
		List<WhisperSegment> allSegments;
		if (res.segments() == null) {
			allSegments = List.of();
		} else {
			allSegments = res.segments();
		};

		// For each segment
		for (WhisperSegment seg : allSegments) {
			// If the segment is not speech, skip it
			if (!isSpeech(seg)) {
				continue;
			}
			
			// Build a list of words that are inside the segment
			List<Word> words = new ArrayList<>();

			// For each word, if it is inside the segment, add it to the list of words
			for (WhisperWord w : allWords) {
				
				if (w.start() >= seg.start() && w.start() < seg.end()) {
					words.add(new Word(w.start(), w.end(), w.word()));
				}
			}
			list_of_segments.add(new Segment(seg.start(), seg.end(), seg.text().strip(), words));
		}
		return list_of_segments;
	}

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
