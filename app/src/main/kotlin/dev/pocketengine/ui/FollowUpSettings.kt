package dev.pocketengine.ui

import org.json.JSONObject

internal fun latestFollowUpPolicy(state: PocketUiState): JSONObject? = listOfNotNull(state.moduleResults["automation.getFollowUpPolicy"]?.optJSONObject("policy"), state.moduleResults["owner.setFollowUpPolicy"]?.optJSONObject("policy")).maxByOrNull { it.optLong("revision") }

internal fun validFollowUpClock(value: String): Boolean = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(value)
