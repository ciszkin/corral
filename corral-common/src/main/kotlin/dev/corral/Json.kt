package dev.corral

import kotlinx.serialization.json.Json

/** Shared client/server JSON config — lenient on purpose: UI and daemon versions may differ. */
val corralJson: Json = Json { ignoreUnknownKeys = true }
