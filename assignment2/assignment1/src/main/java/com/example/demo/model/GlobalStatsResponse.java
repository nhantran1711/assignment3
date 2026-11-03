package com.example.demo.model;

public class GlobalStatsResponse {
	
	private final long inputTokens;
	private final long outputTokens;
	
	// Constructor
	public GlobalStatsResponse(long inputTokens, long outputTokens) {
		this.inputTokens = inputTokens;
		this.outputTokens = outputTokens;
	}
	
	// Getters for input and output tokens
	public long getInputTokens() {
		return this.inputTokens;
	}
	
	public long getOutputTokens() {
		return this.outputTokens;
	}
}
