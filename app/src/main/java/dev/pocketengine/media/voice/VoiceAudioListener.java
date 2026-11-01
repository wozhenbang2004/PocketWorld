package dev.pocketengine.media.voice;

/** Called on the audio owner thread. Consumers must return promptly; do not persist raw audio. */
public interface VoiceAudioListener { void onAudioEvent(VoiceAudioEvent event); }
