package com.allhome.colourcoats.chat;

/** A knowledge chunk that was given to the model for one reply. */
public record ChatSource(String chunkId, String url, String section, Double score) {}
