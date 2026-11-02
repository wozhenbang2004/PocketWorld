package dev.pocketengine.features.operations;

import dev.pocketengine.core.RunControl;
import org.json.JSONObject;

/** Native owner hook for fixed shell/Python diagnostics, not a model command entry. */
public interface OperationsRuntime { JSONObject runOwnerCompatibilityProbe(RunControl original)throws Exception; }
