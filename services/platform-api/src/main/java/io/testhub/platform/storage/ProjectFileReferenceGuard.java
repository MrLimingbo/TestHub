package io.testhub.platform.storage;

import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Only project-local, canonical download links backed by an existing file may enter sync writes. */
@Service
public class ProjectFileReferenceGuard {
    private static final Pattern DOWNLOAD = Pattern.compile("(?<![\\p{L}\\p{N}_:/%.\\\\-])/api/v1/projects/([A-Z0-9]{26})/files/([A-Z0-9]{26})/download(?=$|[\\s\\\"'<>?#)\\]])");
    private static final Pattern OTHER = Pattern.compile("(?:/|%2f)api(?:/|%2f)v1(?:/|%2f)projects(?:/|%2f)|testhub-file:", Pattern.CASE_INSENSITIVE);
    private final JdbcClient jdbc;

    public ProjectFileReferenceGuard(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void validate(Object value, String project) {
        if (value instanceof String text) validateText(text, project);
        else if (value instanceof Map<?, ?> map) map.values().forEach(part -> validate(part, project));
        else if (value instanceof Iterable<?> parts) parts.forEach(part -> validate(part, project));
    }

    private void validateText(String text, String project) {
        var matcher = DOWNLOAD.matcher(text);
        var remaining = new StringBuffer();
        while (matcher.find()) {
            if (withinExternalUrl(text, matcher.start()) || !project.equals(matcher.group(1)) || jdbc.sql("SELECT COUNT(*) FROM file_object WHERE project_id=:p AND id=:id")
                    .param("p", project).param("id", matcher.group(2)).query(Long.class).single() != 1L)
                throw invalid();
            matcher.appendReplacement(remaining, "");
        }
        matcher.appendTail(remaining);
        if (OTHER.matcher(remaining).find()) throw invalid();
    }

    private boolean withinExternalUrl(String text, int offset) {
        int start = offset;
        while (start > 0 && " \t\r\n\"'<>()[\\]".indexOf(text.charAt(start - 1)) < 0) start--;
        return text.substring(start, offset).matches("(?i).*(?:[a-z][a-z0-9+.-]*:)?//.*");
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "正文包含未确认、失效或跨项目的文件链接；请先核对项目文件同步对应关系");
    }
}
