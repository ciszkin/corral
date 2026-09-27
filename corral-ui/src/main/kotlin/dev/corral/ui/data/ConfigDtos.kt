package dev.corral.ui.data

import kotlinx.serialization.Serializable

@Serializable
data class ConfigSetting(val key: String, val value: String, val default: String, val restartRequired: Boolean)

@Serializable
data class ConfigRules(
    val readonly: List<String> = emptyList(),
    val valueflags: List<String> = emptyList(),
    val shell_skip: List<String> = emptyList(),
    val init_floor: List<String> = emptyList(),
)

@Serializable
data class ConfigResponse(
    val settings: List<ConfigSetting>,
    val rules: ConfigRules,
    val rulesPath: String,
    val envPath: String,
)

@Serializable
data class PutConfigRequest(val settings: Map<String, String> = emptyMap(), val rules: ConfigRules? = null)

@Serializable
data class PutConfigResponse(
    val restartRequired: Boolean = true,
    val rulesLive: Boolean = true,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class ConfigErrors(val errors: List<String>)

@Serializable
data class DoctorLayer(val layer: String, val status: String, val detail: String)

@Serializable
data class DoctorResponse(val adb: String, val layers: List<DoctorLayer>)
