package dev.pocketengine.core;
import org.json.JSONObject;
public interface EventSink { void emit(JSONObject event); }
