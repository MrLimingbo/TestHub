package io.testhub.platform.aitesting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.functional.FunctionalAssetService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static io.testhub.platform.aitesting.AiAgentModels.*;

@Service
class AiContextService {
    private static final int FILE_LIMIT = 2 * 1024 * 1024;
    private static final int DOCUMENT_LIMIT = 8 * 1024 * 1024;
    private static final int TEXT_LIMIT = 10000;
    private static final Set<String> TEXT_TYPES = Set.of(".txt", ".md", ".csv", ".json", ".log");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\r\\t]]");
    private static final String WORD_NAMESPACE = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private final FunctionalAssetService assets;
    private final ObjectMapper json;

    AiContextService(FunctionalAssetService assets, ObjectMapper json) {
        this.assets = assets;
        this.json = json;
    }

    AttachmentPreview preview(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > FILE_LIMIT) throw rejected("附件须为非空文件，且不能超过 2 MB");
        String filename = file.getOriginalFilename();
        String name = safeName(filename == null ? "" : filename.replace('\\', '/').replaceFirst("^.*/", ""));
        String extension = name.substring(Math.max(0, name.lastIndexOf('.'))).toLowerCase(Locale.ROOT);
        try (InputStream input = file.getInputStream()) {
            String text;
            if (".docx".equals(extension)) text = wordText(input);
            else if (TEXT_TYPES.contains(extension)) {
                text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(input.readNBytes(FILE_LIMIT + 1))).toString();
            } else throw rejected("支持 DOCX 及 UTF-8 格式的 TXT、MD、CSV、JSON、LOG 附件");
            text = CONTROL.matcher(text.replaceFirst("^\uFEFF", "")).replaceAll("").strip();
            if (text.isBlank()) throw rejected("附件中没有可读取的文本");
            return new AttachmentPreview(name, limit(text, TEXT_LIMIT), text.length() > TEXT_LIMIT);
        } catch (IOException | XMLStreamException exception) {
            throw rejected("无法读取附件，请检查文件格式和编码");
        }
    }

    Context build(AuthenticatedPrincipal actor, String projectId, SendInput input) {
        List<Source> sources = new ArrayList<>();
        for (Attachment attachment : input.attachments()) {
            sources.add(new Source("附件", safeName(attachment.name()), attachment.text()));
        }
        for (AssetRef ref : input.assets().stream().distinct().toList()) {
            if (ref.kind() == AssetKind.REQUIREMENT) {
                var row = assets.getRequirement(actor, projectId, ref.id());
                sources.add(new Source("需求", safeName(row.requirementCode() + " " + row.title()),
                        limit(row.description(), TEXT_LIMIT)));
            } else {
                var row = assets.getCase(actor, projectId, ref.id());
                sources.add(new Source("用例", safeName(row.caseCode() + " " + row.title()), limit(
                        "前置条件\n" + row.precondition() + "\n步骤\n" + row.steps() + "\n预期结果\n" + row.expectedResult(), TEXT_LIMIT)));
            }
        }
        if (sources.isEmpty()) return new Context("", "");
        try {
            return new Context(String.join("、", sources.stream().map(Source::name).toList()), json.writeValueAsString(sources));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize conversation context", exception);
        }
    }

    private String wordText(InputStream input) throws IOException, XMLStreamException {
        try (var archive = new ZipInputStream(input)) {
            int remaining = DOCUMENT_LIMIT;
            for (int index = 0; index < 128; index++) {
                var entry = archive.getNextEntry();
                if (entry == null) break;
                byte[] bytes = archive.readNBytes(remaining + 1);
                remaining -= bytes.length;
                if (remaining < 0) throw rejected("DOCX 解压后超过大小限制");
                if ("word/document.xml".equals(entry.getName())) return parseWordXml(bytes);
            }
        }
        throw rejected("DOCX 中缺少可读取的正文");
    }

    private String parseWordXml(byte[] bytes) throws XMLStreamException {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        var reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
        try {
            StringBuilder result = new StringBuilder();
            while (reader.hasNext() && result.length() <= TEXT_LIMIT) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && WORD_NAMESPACE.equals(reader.getNamespaceURI())) {
                    switch (reader.getLocalName()) {
                        case "t" -> result.append(reader.getElementText());
                        case "br" -> result.append('\n');
                        case "tab" -> result.append('\t');
                        default -> { }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && WORD_NAMESPACE.equals(reader.getNamespaceURI())
                        && "p".equals(reader.getLocalName())) {
                    result.append('\n');
                }
            }
            return result.toString();
        } finally {
            reader.close();
        }
    }

    private static String safeName(String name) {
        String clean = CONTROL.matcher(name).replaceAll("").replaceAll("\\s+", " ").strip();
        if (clean.isBlank()) throw rejected("上下文来源名称不能为空");
        return limit(clean, 120);
    }

    private static String limit(String text, int size) {
        if (text.length() <= size) return text;
        int end = Character.isHighSurrogate(text.charAt(size - 1)) ? size - 1 : size;
        return text.substring(0, end);
    }

    record Context(String summary, String text) { }
    private record Source(String type, String name, String text) { }

    private static ResponseStatusException rejected(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
