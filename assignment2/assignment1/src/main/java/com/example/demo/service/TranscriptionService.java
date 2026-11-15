package com.example.demo.service;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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
	private static final String PROFILE_SUFFIX = ".loudness";

	// Excerpt matching
	private static final double SECONDS_PER_PROFILE_VALUE = 0.05;
	private static final int MIN_PROFILE_VALUES = 100;     // clips under 5s are too short to identify safely
	private static final double MIN_CORRELATION = 0.9;     // how closely the loudness must follow the cached track
	private static final double MIN_SPREAD_DB = 3.0;       // a nearly flat profile could match anything

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
	public Subtitles transcribeSubtitles(MultipartFile audio, String loudness) {
		log.info("Received subtitle request. Audio size: " + audio.getSize() + " bytes");

		// Seen this audio before
		Path cacheFile = cacheFileFor(audio);
		Subtitles cached = readCache(cacheFile);

		// If we have a cached version, return it
		if (cached != null) {
			log.info("Cache hit: " + cacheFile.getFileName());
			return cached;
		}

		// If the client sent a loudness profile, see if it matches an excerpt of a cached track
		Subtitles excerpt = findExcerpt(loudness);
		if (excerpt != null) {
			writeCache(cacheFile, excerpt, loudness);
			return excerpt;
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
		writeCache(cacheFile, subtitles, loudness); // the loudness lets later excerpts of this audio be recognised
		return subtitles;
	}

	// Parse a loudness profile string into an array of doubles
	private static double[] parseLoudness(String text) {
		
		// If the text is null or blank, return null
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			String[] parts = text.split(",");
			double[] values = new double[parts.length];

			// Parse each part into a double
			for (int i = 0; i < parts.length; i++) {
				values[i] = Double.parseDouble(parts[i].trim());
			}

			return values;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// 1.0 = perfect match, 0 = no correlation, -1 = perfect anti-correlation
	private static double correlationAt(double[] clip, double[] track, int offset) {
		
		double clipMean = Arrays.stream(clip).average().orElse(0);
		double trackMean = Arrays.stream(track, offset, offset + clip.length).average().orElse(0);
		double together = 0, clipSpread = 0, trackSpread = 0;
		
		// Check each value in the clip against the corresponding value in the track
		for (int i = 0; i < clip.length; i++) {
			double c = clip[i] - clipMean;
			double t = track[offset + i] - trackMean;

			together += c * t; // positive when both are above (or both below) their own average
			clipSpread += c * c;
			trackSpread += t * t;
		}

		// A nearly flat clip would "match" almost anything, so it never counts
		if (clipSpread < clip.length * MIN_SPREAD_DB * MIN_SPREAD_DB || trackSpread == 0) {
			return 0;
		}

		// Return the correlation coefficient
		return together / Math.sqrt(clipSpread * trackSpread);
	}

	// Return the offset in the track where the clip best matches, or -1 if it doesn't match well enough
	private static int findOffset(double[] clip, double[] track) {
		
		// If the clip is too short, it can't be identified safely
		if (track == null || track.length < clip.length) {
			return -1;
		}

		int bestOffset = -1;
		double bestScore = MIN_CORRELATION; // anything below this isn't the same audio
		
		// Check each possible offset in the track where the clip could fit
		for (int offset = 0; offset + clip.length <= track.length; offset++) {
			double score = correlationAt(clip, track, offset);
			
			// If this offset is a better match than the previous best, remember it
			if (score > bestScore) {
				bestScore = score;
				bestOffset = offset;
			}
		}
		return bestOffset;
	}

	// Return a copy of the subtitles that only contains the excerpt from fromSeconds to fromSeconds + lengthSeconds
	private static Subtitles cropToExcerpt(Subtitles full, double fromSeconds, double lengthSeconds) {
		
		double endSeconds = fromSeconds + lengthSeconds;
		
		// Build a list of segments that are within the excerpt
		List<Segment> segments = new ArrayList<>();
		
		// For each segment in the full subtitles
		for (Segment s : full.segments()) {

			// If the segment is completely before or after the excerpt, skip it
			if (s.end() <= fromSeconds || s.start() >= endSeconds) {
				continue; 
			}
			// Build a list of words that are within the excerpt
			List<Word> words = new ArrayList<>();
			
			// For each word in the segment, if it is within the excerpt, add it to the list of words
			List<Word> sourceWords = s.words();

			// If the segment has no words, skip it
			if (sourceWords != null) {
				
				// For each word in the segment
				for (Word word : sourceWords) {
					
					// If the word is within the excerpt, add it to the list of words
					if (word.start() >= fromSeconds && word.start() < endSeconds) {
						double start = word.start() - fromSeconds;
						double end = Math.min(lengthSeconds, word.end() - fromSeconds);

						words.add(new Word(start, end, word.word()));
					}
				}
			}
			// If the segment overlaps the excerpt, add it to the list of segments, with its start and end times adjusted to the excerpt
			segments.add(new Segment(Math.max(0, s.start() - fromSeconds), Math.min(lengthSeconds, s.end() - fromSeconds), s.text(), words));
		}
		return new Subtitles(lengthSeconds, segments);
	}

	// Find a cached transcription that matches the given loudness profile, and return it. Returns null if none found.
	private Subtitles findExcerpt(String loudness) {
		double[] clip = parseLoudness(loudness);
		
		// If the clip is null, too short, or the cache directory doesn't exist, return null
		if (clip == null || clip.length < MIN_PROFILE_VALUES || !Files.isDirectory(CACHE_DIR)) {
			return null; 
		}

		try (DirectoryStream<Path> profiles = Files.newDirectoryStream(CACHE_DIR, "*" + PROFILE_SUFFIX)) {
			// For each cached loudness profile, see if it matches the clip
			for (Path profile : profiles) {
				
				int offset = findOffset(clip, parseLoudness(Files.readString(profile)));

				Subtitles full = null;

				// If the clip matches the profile, read the corresponding subtitles from the cache
				if (offset >= 0) {
					Path cachePath = Path.of(profile.toString().replace(PROFILE_SUFFIX, ".json"));
					full = readCache(cachePath);
				}

				// If we found a matching excerpt, return it
				if (full != null) {
					log.info("Excerpt cache hit: " + profile.getFileName() + " from " + offset * SECONDS_PER_PROFILE_VALUE + "s");
					return cropToExcerpt(full, offset * SECONDS_PER_PROFILE_VALUE, clip.length * SECONDS_PER_PROFILE_VALUE);
				}
			}
		} catch (IOException e) {
			log.warn("Could not search the subtitle cache: " + e.getMessage());
		}
		return null;
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

	// Read the cache file for this audio track, or return null if it doesn't exist or is unreadable
	private static Subtitles readCache(Path cacheFile) {
		// If the cache file doesn't exist, return null
		if (!Files.exists(cacheFile)) {
			return null;
		}
		// If the cache file exists, try to read it
		try {
			return JSON.readValue(cacheFile.toFile(), Subtitles.class);
		} catch (Exception e) {
			log.warn("Ignoring unreadable cache file " + cacheFile + ": " + e.getMessage());
			return null;
		}
	}


	// Save the subtitles, plus the audio's loudness profile next to them (<hash>.loudness beside <hash>.json) if sent
	private static void writeCache(Path cacheFile, Subtitles subtitles, String loudness) {
		try {
			Files.createDirectories(CACHE_DIR);
			JSON.writeValue(cacheFile.toFile(), subtitles);
			
			// If the loudness profile was sent, save it too
			if (loudness != null && !loudness.isBlank()) {
				Files.writeString(Path.of(cacheFile.toString().replace(".json", PROFILE_SUFFIX)), loudness);
			}
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
