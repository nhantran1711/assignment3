package com.example.demo.service;

import org.springframework.stereotype.Service;

import com.example.demo.model.GlobalStatsResponse;

import java.util.concurrent.atomic.AtomicLong;

@Service
public class StatsService {
	
	private final AtomicLong inputTokens = new AtomicLong(0);
	private final AtomicLong outputTokens = new AtomicLong(0);
	
	// Add tokens
	public void addTokens(long input, long output) {
		inputTokens.addAndGet(input);
		outputTokens.addAndGet(output);
	}
	
	public GlobalStatsResponse getStats() {
		return new GlobalStatsResponse(inputTokens.get(), outputTokens.get());
	}
}
