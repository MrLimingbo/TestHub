package io.testhub.platform.mobileautomation;

import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The supported asset matrix is also enforced on frozen execution inputs. */
final class MobileLocatorPolicy {
    private static final Set<String> COMMON = Set.of("ACCESSIBILITY_ID", "ID", "XPATH");
    private static final Set<String> REQUIRES_ELEMENT = Set.of("TAP", "INPUT", "ASSERT_VISIBLE", "ASSERT_TEXT");
    private MobileLocatorPolicy() { }

    static void require(String platform, String strategy) {
        boolean android = "ANDROID".equals(platform), ios = "IOS".equals(platform);
        if ((!android && !ios) || strategy == null || !(COMMON.contains(strategy)
                || android && "ANDROID_UIAUTOMATOR".equals(strategy) || ios && "IOS_PREDICATE".equals(strategy))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "locatorStrategy 与移动端平台不匹配");
        }
    }

    static void requireResolved(String platform, String type, String strategy, String value) {
        if (strategy == null && value == null && (type == null || !REQUIRES_ELEMENT.contains(type))) return;
        require(platform, strategy);
        if (value == null || value.isBlank() || value.length() > 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "移动端 locatorValue 不合法");
        }
    }
}
