package io.testhub.platform.apidebug;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A deliberately small, in-process proto3 compiler for the dynamic debugger.
 * It accepts declarative schemas only; no external compiler or user code is run.
 */
final class GrpcProtoCompiler {
    private static final Map<String, FieldDescriptorProto.Type> SCALARS = Map.ofEntries(
            Map.entry("double", FieldDescriptorProto.Type.TYPE_DOUBLE),
            Map.entry("float", FieldDescriptorProto.Type.TYPE_FLOAT),
            Map.entry("int64", FieldDescriptorProto.Type.TYPE_INT64),
            Map.entry("uint64", FieldDescriptorProto.Type.TYPE_UINT64),
            Map.entry("int32", FieldDescriptorProto.Type.TYPE_INT32),
            Map.entry("fixed64", FieldDescriptorProto.Type.TYPE_FIXED64),
            Map.entry("fixed32", FieldDescriptorProto.Type.TYPE_FIXED32),
            Map.entry("bool", FieldDescriptorProto.Type.TYPE_BOOL),
            Map.entry("string", FieldDescriptorProto.Type.TYPE_STRING),
            Map.entry("bytes", FieldDescriptorProto.Type.TYPE_BYTES),
            Map.entry("uint32", FieldDescriptorProto.Type.TYPE_UINT32),
            Map.entry("sfixed32", FieldDescriptorProto.Type.TYPE_SFIXED32),
            Map.entry("sfixed64", FieldDescriptorProto.Type.TYPE_SFIXED64),
            Map.entry("sint32", FieldDescriptorProto.Type.TYPE_SINT32),
            Map.entry("sint64", FieldDescriptorProto.Type.TYPE_SINT64));

    private GrpcProtoCompiler() {
    }

    static List<FileDescriptorProto> compile(List<ProtoSource> sources) throws CompilationException {
        if (sources == null || sources.isEmpty()) throw new CompilationException("未提供 .proto 文件");
        List<Parser> parsers = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ProtoSource source : sources) {
            if (source == null || source.name() == null || source.name().isBlank()) throw new CompilationException("Proto 文件名不能为空");
            if (!names.add(source.name())) throw new CompilationException("Proto 文件名重复：" + source.name());
            parsers.add(new Parser(source));
        }
        for (Parser parser : parsers) parser.parse();
        Map<String, Symbol> symbols = new HashMap<>();
        for (Parser parser : parsers) collectSymbols(parser.partial(), symbols);
        for (Parser parser : parsers) parser.resolve(symbols);
        List<FileDescriptorProto> compiled = parsers.stream().map(Parser::build).toList();
        try {
            GrpcDescriptorRuntime.build(compiled);
            return compiled;
        } catch (GrpcDescriptorRuntime.InvalidDescriptorException exception) {
            throw new CompilationException(exception.getMessage());
        }
    }

    private static void collectSymbols(FileDescriptorProto file, Map<String, Symbol> symbols) throws CompilationException {
        String prefix = file.getPackage().isBlank() ? "" : file.getPackage() + ".";
        for (DescriptorProto message : file.getMessageTypeList()) collectMessage(prefix, message, symbols);
        for (EnumDescriptorProto value : file.getEnumTypeList()) addSymbol(prefix + value.getName(), Symbol.ENUM, symbols);
    }

    private static void collectMessage(String prefix, DescriptorProto message, Map<String, Symbol> symbols) throws CompilationException {
        String name = prefix + message.getName();
        addSymbol(name, Symbol.MESSAGE, symbols);
        for (DescriptorProto nested : message.getNestedTypeList()) collectMessage(name + ".", nested, symbols);
        for (EnumDescriptorProto value : message.getEnumTypeList()) addSymbol(name + "." + value.getName(), Symbol.ENUM, symbols);
    }

    private static void addSymbol(String name, Symbol kind, Map<String, Symbol> symbols) throws CompilationException {
        if (symbols.putIfAbsent(name, kind) != null) throw new CompilationException("Proto 类型重复：" + name);
    }

    record ProtoSource(String name, String contents) {
    }

    static final class CompilationException extends Exception {
        CompilationException(String message) {
            super(message);
        }
    }

    private enum Symbol { MESSAGE, ENUM }

    private record FieldReference(FieldDescriptorProto.Builder target, String typeName, String scope) {
    }

    private record MethodReference(MethodDescriptorProto.Builder target, boolean input, String typeName, String scope) {
    }

    private static final class Parser {
        private final ProtoSource source;
        private final List<Token> tokens;
        private final FileDescriptorProto.Builder file;
        private final List<FieldReference> fields = new ArrayList<>();
        private final List<MethodReference> methods = new ArrayList<>();
        private int position;
        private String packageName = "";
        private boolean syntaxDeclared;

        private Parser(ProtoSource source) throws CompilationException {
            this.source = source;
            this.tokens = Tokenizer.tokenize(source.name(), source.contents());
            this.file = FileDescriptorProto.newBuilder().setName(source.name()).setSyntax("proto3");
        }

        private void parse() throws CompilationException {
            while (!end()) {
                if (peek("syntax")) parseSyntax();
                else if (peek("package")) parsePackage();
                else if (peek("import")) parseImport();
                else if (peek("option")) skipStatement();
                else if (peek("message")) {
                    next();
                    parseMessage(file.addMessageTypeBuilder(), "");
                } else if (peek("enum")) {
                    next();
                    parseEnum(file.addEnumTypeBuilder());
                } else if (peek("service")) {
                    next();
                    parseService(file.addServiceBuilder());
                } else if (peek("extend")) {
                    throw error("当前 Proto 导入不支持 extend；请上传 protoc 生成的 FileDescriptorSet");
                } else {
                    throw error("不支持的顶层声明：" + current().text());
                }
            }
            if (!syntaxDeclared) throw error("仅支持显式声明 syntax = \"proto3\" 的文件");
        }

        private void parseSyntax() throws CompilationException {
            expect("syntax");
            expect("=");
            String syntax = string();
            expect(";");
            if (syntaxDeclared || !"proto3".equals(syntax)) throw error("仅支持 syntax = \"proto3\"");
            syntaxDeclared = true;
        }

        private void parsePackage() throws CompilationException {
            expect("package");
            if (!packageName.isBlank()) throw error("package 只能声明一次");
            packageName = qualifiedName();
            if (packageName.startsWith(".")) throw error("package 名称不能以 . 开头");
            file.setPackage(packageName);
            expect(";");
        }

        private void parseImport() throws CompilationException {
            expect("import");
            if (peek("public") || peek("weak")) next();
            String name = string();
            if (name.isBlank() || name.contains("\\") || name.startsWith("/") || name.contains("..")) throw error("导入路径不合法");
            file.addDependency(name);
            expect(";");
        }

        private void parseMessage(DescriptorProto.Builder message, String parentScope) throws CompilationException {
            String name = identifier();
            message.setName(name);
            String scope = fullName(parentScope, name);
            expect("{");
            while (!peek("}")) {
                if (end()) throw error("message 缺少结束符 }");
                if (peek("message")) {
                    next();
                    parseMessage(message.addNestedTypeBuilder(), scope);
                } else if (peek("enum")) {
                    next();
                    parseEnum(message.addEnumTypeBuilder());
                } else if (peek("oneof")) {
                    next();
                    parseOneof(message, scope);
                } else if (peek("option") || peek("reserved") || peek("extensions")) {
                    skipStatement();
                } else if (peek("extend")) {
                    throw error("当前 Proto 导入不支持 extend；请上传 protoc 生成的 FileDescriptorSet");
                } else {
                    parseField(message, scope, null);
                }
            }
            expect("}");
        }

        private void parseOneof(DescriptorProto.Builder message, String scope) throws CompilationException {
            String name = identifier();
            int index = message.getOneofDeclCount();
            message.addOneofDeclBuilder().setName(name);
            expect("{");
            while (!peek("}")) {
                if (end()) throw error("oneof 缺少结束符 }");
                if (peek("option")) skipStatement();
                else parseField(message, scope, index);
            }
            expect("}");
        }

        private void parseField(DescriptorProto.Builder message, String scope, Integer oneofIndex) throws CompilationException {
            boolean repeated = false;
            boolean optional = false;
            if (peek("repeated")) {
                if (oneofIndex != null) throw error("oneof 中不支持 repeated 字段");
                repeated = true;
                next();
            } else if (peek("optional")) {
                if (oneofIndex != null) throw error("oneof 中不能使用 optional");
                optional = true;
                next();
            } else if (peek("required")) {
                throw error("仅支持 proto3，不支持 required 字段");
            }
            if (peek("map")) {
                if (oneofIndex != null || repeated || optional) throw error("map 字段不能与 oneof、repeated 或 optional 组合");
                parseMapField(message, scope);
                return;
            }
            String type = qualifiedName();
            String name = identifier();
            expect("=");
            int number = number();
            FieldDescriptorProto.Builder field = message.addFieldBuilder().setName(name).setNumber(number)
                    .setLabel(repeated ? FieldDescriptorProto.Label.LABEL_REPEATED : FieldDescriptorProto.Label.LABEL_OPTIONAL);
            if (oneofIndex != null) field.setOneofIndex(oneofIndex);
            if (optional) {
                int syntheticOneof = message.getOneofDeclCount();
                message.addOneofDeclBuilder().setName("_" + name);
                field.setProto3Optional(true).setOneofIndex(syntheticOneof);
            }
            setType(field, type, scope);
            skipFieldOptions();
            expect(";");
        }

        private void parseMapField(DescriptorProto.Builder message, String scope) throws CompilationException {
            expect("map");
            expect("<");
            String keyType = qualifiedName();
            if (!SCALARS.containsKey(keyType) || "bytes".equals(keyType) || "float".equals(keyType) || "double".equals(keyType)) {
                throw error("map key 必须为整数、bool 或 string 标量类型");
            }
            expect(",");
            String valueType = qualifiedName();
            expect(">");
            String name = identifier();
            expect("=");
            int number = number();
            String entryName = upperCamel(name) + "Entry";
            DescriptorProto.Builder entry = message.addNestedTypeBuilder().setName(entryName)
                    .setOptions(MessageOptions.newBuilder().setMapEntry(true));
            FieldDescriptorProto.Builder key = entry.addFieldBuilder().setName("key").setNumber(1)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
            setType(key, keyType, scope + "." + entryName);
            FieldDescriptorProto.Builder value = entry.addFieldBuilder().setName("value").setNumber(2)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
            setType(value, valueType, scope + "." + entryName);
            message.addFieldBuilder().setName(name).setNumber(number).setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName("." + scope + "." + entryName);
            skipFieldOptions();
            expect(";");
        }

        private void parseEnum(EnumDescriptorProto.Builder enumeration) throws CompilationException {
            enumeration.setName(identifier());
            expect("{");
            while (!peek("}")) {
                if (end()) throw error("enum 缺少结束符 }");
                if (peek("option") || peek("reserved")) {
                    skipStatement();
                    continue;
                }
                String name = identifier();
                expect("=");
                int number = signedNumber();
                enumeration.addValue(EnumValueDescriptorProto.newBuilder().setName(name).setNumber(number));
                skipFieldOptions();
                expect(";");
            }
            expect("}");
        }

        private void parseService(ServiceDescriptorProto.Builder service) throws CompilationException {
            service.setName(identifier());
            expect("{");
            while (!peek("}")) {
                if (end()) throw error("service 缺少结束符 }");
                if (peek("option")) {
                    skipStatement();
                    continue;
                }
                expect("rpc");
                MethodDescriptorProto.Builder method = service.addMethodBuilder().setName(identifier());
                expect("(");
                boolean clientStreaming = consume("stream");
                String input = qualifiedName();
                expect(")");
                expect("returns");
                expect("(");
                boolean serverStreaming = consume("stream");
                String output = qualifiedName();
                expect(")");
                method.setClientStreaming(clientStreaming).setServerStreaming(serverStreaming);
                methods.add(new MethodReference(method, true, input, ""));
                methods.add(new MethodReference(method, false, output, ""));
                if (peek("{")) skipBlock();
                else expect(";");
            }
            expect("}");
        }

        private void setType(FieldDescriptorProto.Builder field, String type, String scope) {
            FieldDescriptorProto.Type scalar = SCALARS.get(type);
            if (scalar != null) field.setType(scalar);
            else fields.add(new FieldReference(field, type, scope));
        }

        private void resolve(Map<String, Symbol> symbols) throws CompilationException {
            for (FieldReference field : fields) {
                String name = resolveType(field.typeName(), field.scope(), symbols);
                field.target().setType(symbols.get(name) == Symbol.ENUM ? FieldDescriptorProto.Type.TYPE_ENUM : FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("." + name);
            }
            for (MethodReference method : methods) {
                String name = resolveType(method.typeName(), method.scope(), symbols);
                if (symbols.get(name) != Symbol.MESSAGE) throw error("RPC 请求与响应必须是 message 类型：" + method.typeName());
                if (method.input()) method.target().setInputType("." + name);
                else method.target().setOutputType("." + name);
            }
        }

        private String resolveType(String type, String scope, Map<String, Symbol> symbols) throws CompilationException {
            if (type.startsWith(".")) {
                String absolute = type.substring(1);
                if (symbols.containsKey(absolute)) return absolute;
                throw error("未找到类型：" + type);
            }
            String normalizedScope = scope == null ? "" : scope;
            while (true) {
                String candidate = normalizedScope.isBlank() ? type : normalizedScope + "." + type;
                if (symbols.containsKey(candidate)) return candidate;
                int separator = normalizedScope.lastIndexOf('.');
                if (separator < 0) break;
                normalizedScope = normalizedScope.substring(0, separator);
            }
            if (!packageName.isBlank()) {
                String candidate = packageName + "." + type;
                if (symbols.containsKey(candidate)) return candidate;
            }
            if (symbols.containsKey(type)) return type;
            throw error("未找到类型：" + type);
        }

        private FileDescriptorProto partial() {
            return file.build();
        }

        private FileDescriptorProto build() {
            return file.build();
        }

        private String fullName(String parentScope, String name) {
            if (!parentScope.isBlank()) return parentScope + "." + name;
            return packageName.isBlank() ? name : packageName + "." + name;
        }

        private void skipStatement() throws CompilationException {
            next();
            int square = 0;
            while (!end()) {
                Token token = next();
                if ("[".equals(token.text())) square++;
                else if ("]".equals(token.text())) square--;
                else if (";".equals(token.text()) && square == 0) return;
            }
            throw error("声明缺少结束分号");
        }

        private void skipFieldOptions() throws CompilationException {
            if (!peek("[")) return;
            int depth = 0;
            do {
                Token token = next();
                if ("[".equals(token.text())) depth++;
                if ("]".equals(token.text())) depth--;
                if (end() && depth > 0) throw error("字段选项缺少结束符 ]");
            } while (depth > 0);
        }

        private void skipBlock() throws CompilationException {
            expect("{");
            int depth = 1;
            while (depth > 0) {
                if (end()) throw error("声明块缺少结束符 }");
                Token token = next();
                if ("{".equals(token.text())) depth++;
                else if ("}".equals(token.text())) depth--;
            }
        }

        private String qualifiedName() throws CompilationException {
            StringBuilder result = new StringBuilder();
            if (consume(".")) result.append('.');
            result.append(identifier());
            while (consume(".")) result.append('.').append(identifier());
            return result.toString();
        }

        private int number() throws CompilationException {
            String value = current().text();
            if (current().kind() != Kind.NUMBER) throw error("字段编号必须是整数");
            next();
            try {
                int number = Integer.parseInt(value);
                if (number < 1 || number > 536_870_911 || number >= 19_000 && number <= 19_999) throw error("字段编号超出 Proto3 允许范围");
                return number;
            } catch (NumberFormatException exception) {
                throw error("字段编号超出范围");
            }
        }

        private int signedNumber() throws CompilationException {
            boolean negative = consume("-");
            String value = current().text();
            if (current().kind() != Kind.NUMBER) throw error("枚举值必须是整数");
            next();
            try {
                return Integer.parseInt((negative ? "-" : "") + value);
            } catch (NumberFormatException exception) {
                throw error("枚举值超出范围");
            }
        }

        private String identifier() throws CompilationException {
            if (current().kind() != Kind.IDENTIFIER) throw error("需要标识符");
            return next().text();
        }

        private String string() throws CompilationException {
            if (current().kind() != Kind.STRING) throw error("需要字符串字面量");
            return next().text();
        }

        private boolean consume(String value) {
            if (!peek(value)) return false;
            position++;
            return true;
        }

        private void expect(String value) throws CompilationException {
            if (!consume(value)) throw error("需要 \"" + value + "\"");
        }

        private boolean peek(String value) {
            return !end() && value.equals(current().text());
        }

        private Token current() {
            return tokens.get(position);
        }

        private Token next() {
            return tokens.get(position++);
        }

        private boolean end() {
            return current().kind() == Kind.END;
        }

        private CompilationException error(String message) {
            return new CompilationException(source.name() + " 第 " + current().line() + " 行：" + message);
        }
    }

    private static String upperCamel(String value) {
        StringBuilder result = new StringBuilder();
        boolean upper = true;
        for (char character : value.toCharArray()) {
            if (character == '_') {
                upper = true;
            } else {
                result.append(upper ? Character.toUpperCase(character) : character);
                upper = false;
            }
        }
        return result.isEmpty() ? "Map" : result.toString();
    }

    private enum Kind { IDENTIFIER, NUMBER, STRING, SYMBOL, END }

    private record Token(Kind kind, String text, int line) {
    }

    private static final class Tokenizer {
        private Tokenizer() {
        }

        private static List<Token> tokenize(String name, String contents) throws CompilationException {
            if (contents == null) throw new CompilationException(name + " 内容不能为空");
            List<Token> result = new ArrayList<>();
            int line = 1;
            for (int index = 0; index < contents.length();) {
                char character = contents.charAt(index);
                if (Character.isWhitespace(character)) {
                    if (character == '\n') line++;
                    index++;
                    continue;
                }
                if (character == '/' && index + 1 < contents.length() && contents.charAt(index + 1) == '/') {
                    index += 2;
                    while (index < contents.length() && contents.charAt(index) != '\n') index++;
                    continue;
                }
                if (character == '/' && index + 1 < contents.length() && contents.charAt(index + 1) == '*') {
                    index += 2;
                    boolean closed = false;
                    while (index < contents.length()) {
                        if (contents.charAt(index) == '\n') line++;
                        if (contents.charAt(index) == '*' && index + 1 < contents.length() && contents.charAt(index + 1) == '/') {
                            index += 2;
                            closed = true;
                            break;
                        }
                        index++;
                    }
                    if (!closed) throw new CompilationException(name + " 第 " + line + " 行：注释未结束");
                    continue;
                }
                if (character == '"' || character == '\'') {
                    char quote = character;
                    int tokenLine = line;
                    StringBuilder value = new StringBuilder();
                    index++;
                    boolean closed = false;
                    while (index < contents.length()) {
                        char item = contents.charAt(index++);
                        if (item == '\n') line++;
                        if (item == quote) {
                            closed = true;
                            break;
                        }
                        if (item == '\\') {
                            if (index >= contents.length()) throw new CompilationException(name + " 第 " + tokenLine + " 行：字符串转义不完整");
                            char escaped = contents.charAt(index++);
                            value.append(switch (escaped) {
                                case 'n' -> '\n';
                                case 'r' -> '\r';
                                case 't' -> '\t';
                                case '\\' -> '\\';
                                case '\'' -> '\'';
                                case '"' -> '"';
                                default -> escaped;
                            });
                        } else value.append(item);
                    }
                    if (!closed) throw new CompilationException(name + " 第 " + tokenLine + " 行：字符串未结束");
                    result.add(new Token(Kind.STRING, value.toString(), tokenLine));
                    continue;
                }
                if (Character.isLetter(character) || character == '_') {
                    int start = index++;
                    while (index < contents.length() && (Character.isLetterOrDigit(contents.charAt(index)) || contents.charAt(index) == '_')) index++;
                    result.add(new Token(Kind.IDENTIFIER, contents.substring(start, index), line));
                    continue;
                }
                if (Character.isDigit(character)) {
                    int start = index++;
                    while (index < contents.length() && Character.isDigit(contents.charAt(index))) index++;
                    result.add(new Token(Kind.NUMBER, contents.substring(start, index), line));
                    continue;
                }
                if ("{}[]()<>;=,.+-".indexOf(character) >= 0) {
                    result.add(new Token(Kind.SYMBOL, Character.toString(character), line));
                    index++;
                    continue;
                }
                throw new CompilationException(name + " 第 " + line + " 行：不支持的字符 " + character);
            }
            result.add(new Token(Kind.END, "", line));
            return result;
        }
    }
}
