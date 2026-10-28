package com.gandalondon.remote.tv

/** The three things the remote can switch to. */
enum class Target(val label: String) {
    LIVE_TV("Live TV"),
    CAST("Google OTT"),
    PS5("PS5"),
}

/** One of the TV's own inputs, as reported by tv/getExternalInputList. */
data class ExternalInput(
    val id: String,        // e.g. HDMI_2
    val label: String,     // the TV's name for it, e.g. "PS5" or "HDMI 2"
    val appId: String,     // e.g. com.webos.app.hdmi2, used to detect what is on screen
    val connected: Boolean,
    val icon: String = "",
)

/** What a target currently points at. */
data class Choice(
    val inputId: String,
    val appId: String,
    val tvLabel: String,
    val connected: Boolean,
    val automatic: Boolean,
)

const val LIVE_TV_ID = "LIVE_TV"
const val LIVE_TV_APP = "com.webos.app.livetv"

val LiveTvInput = ExternalInput(LIVE_TV_ID, "Live TV (tuner)", LIVE_TV_APP, connected = true)

private val patterns = mapOf(
    Target.PS5 to Regex("""ps\s?5|playstation""", RegexOption.IGNORE_CASE),
    Target.CAST to Regex("""google|chromecast|\bott\b|android\s?tv""", RegexOption.IGNORE_CASE),
)

/**
 * Resolves a target to a real input. A manual choice in Settings wins;
 * otherwise the TV's input names (which follow HDMI-CEC device names, or
 * whatever the inputs were renamed to on the TV) are matched by keyword.
 */
fun resolve(target: Target, inputs: List<ExternalInput>?, override: String?): Choice? {
    if (override != null) {
        if (override == LIVE_TV_ID) return LiveTvInput.toChoice(automatic = false)
        return inputs?.firstOrNull { it.id == override }?.toChoice(automatic = false)
            // Inputs not loaded yet: trust the saved id so the button still works.
            ?: if (inputs == null) Choice(override, hdmiAppId(override), override, true, false) else null
    }
    if (target == Target.LIVE_TV) return LiveTvInput.toChoice(automatic = true)
    val pattern = patterns.getValue(target)
    return inputs
        ?.firstOrNull { pattern.containsMatchIn("${it.label} ${it.icon}") }
        ?.toChoice(automatic = true)
}

private fun ExternalInput.toChoice(automatic: Boolean) =
    Choice(id, appId, label, connected, automatic)

/** HDMI_2 -> com.webos.app.hdmi2, the TV's usual naming. */
private fun hdmiAppId(inputId: String) =
    "com.webos.app." + inputId.lowercase().replace("_", "")

/** "HDMI_2" -> "HDMI 2" for display when the TV gives no nicer name. */
fun portName(inputId: String) = inputId.replace('_', ' ')
