package io.testhub.platform.apiautomation;

import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Control-plane validation only. User-authored code only runs in the isolated API Runner. */
public final class ApiAutomationScriptPolicy {
    public static final int MAX_SOURCE_LENGTH = 8 * 1024;
    private static final Pattern FORBIDDEN_GLOBAL = Pattern.compile(
            "(?i)\\b(require|import|export|process|globalThis|global|module|exports|eval|webassembly|"
                    + "worker|sharedarraybuffer|atomics|settimeout|setinterval|queuemicrotask|fetch|xmlhttprequest|deno|bun)\\b");
    private static final Pattern DYNAMIC_FUNCTION = Pattern.compile("\\bFunction\\b");
    private static final Pattern UNSUPPORTED_PM_API = Pattern.compile(
            "(?i)\\b(?:postman|pm)\\s*\\.\\s*(?:globals|collectionVariables|cookies|sendRequest|execution|request|visualizer|vault|require)\\b"
                    + "|\\bpm\\s*\\.\\s*response\\s*\\.\\s*(?:json|text|headers|cookies)\\b");

    private ApiAutomationScriptPolicy() {
    }

    public static String normalize(String source, String label) {
        String result = source == null ? "" : source.trim();
        if (result.length() > MAX_SOURCE_LENGTH) {
            throw rejected(label + "不能超过 " + MAX_SOURCE_LENGTH + " 个字符");
        }
        if (!result.isEmpty() && FORBIDDEN_GLOBAL.matcher(result).find()) {
            throw rejected(label + "包含不允许的运行时能力");
        }
        if (!result.isEmpty() && DYNAMIC_FUNCTION.matcher(result).find()) {
            throw rejected(label + "包含不允许的动态函数构造器");
        }
        if (!result.isEmpty() && UNSUPPORTED_PM_API.matcher(result).find()) {
            throw rejected(label + "使用了当前 Runner 不支持的 pm API");
        }
        return result;
    }

    public static boolean isSafeVariableName(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}");
    }

    private static ResponseStatusException rejected(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
