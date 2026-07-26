package io.testhub.platform.extension;

import java.util.List;

/** A declarative boundary; provider-specific network clients are intentionally outside the control plane. */
public record EnterpriseAdapterDefinition(String type, String displayName, String category, List<String> requiredFields,
                                          String description) {
}
