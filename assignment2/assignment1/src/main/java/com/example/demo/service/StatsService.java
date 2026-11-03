package com.example.demo.service;

import org.springframework.stereotype.Service;

import com.example.demo.model.GlobalStatsResponse;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

@Service
public class StatsService {
	
	private final AtomicLong inputTokens = new AtomicLong(0);
	private final AtomicLong outputTokens = new AtomicLong(0);
	// whisper bills in seconds, not tokens ->thread safe running total of doubles
	private final DoubleAdder usageSeconds = new DoubleAdder();

	// Add whisper usage in seconds
	public void addUsageSeconds(double seconds) {
		usageSeconds.add(seconds);
	}

	public double getUsageSeconds() {
		return usageSeconds.sum();
	}

	// Add tokens
	public void addTokens(long input, long output) {
		inputTokens.addAndGet(input);
		outputTokens.addAndGet(output);
	}
	
	public GlobalStatsResponse getStats() {
		return new GlobalStatsResponse(inputTokens.get(), outputTokens.get());
	}
}
