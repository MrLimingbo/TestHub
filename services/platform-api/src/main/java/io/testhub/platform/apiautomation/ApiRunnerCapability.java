package io.testhub.platform.apiautomation;

import java.util.List;

public record ApiRunnerCapability(String runnerId, String protocolVersion, boolean trusted, boolean executionEnabled,
                                  String runtimeConfiguration, List<String> grantedCapabilities,
                                  List<String> withheldCapabilities) {
}
