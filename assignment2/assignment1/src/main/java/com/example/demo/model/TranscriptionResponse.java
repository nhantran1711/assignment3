package com.example.demo.model;

public class TranscriptionResponse {
	
	private final String text;
	
	// Constructor
	public TranscriptionResponse(String text) {
		this.text = text;
	}
	
	// Getters
	public String getText() {
		return this.text;
	}
}
