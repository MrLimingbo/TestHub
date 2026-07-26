package io.testhub.platform.extension;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Exact bytes of the JSON manifest are signed; this record is only its validated projection. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record PluginManifest(String pluginKey, String displayName, String pluginType, String version, String entrypoint,
                             List<String> permissions) {
}
