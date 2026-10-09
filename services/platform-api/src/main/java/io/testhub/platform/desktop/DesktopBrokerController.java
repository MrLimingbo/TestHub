package io.testhub.platform.desktop;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Narrow native-process bridge, not a general Redis proxy. Each credential is bound to one engine and runner. */
@RestController
@Profile("desktop")
@RequestMapping("/internal/desktop-broker/v1")
public class DesktopBrokerController {
    private static final Set<String> ENGINES = Set.of("api", "jmeter", "playwright", "appium");
    private static final Map<String, Set<String>> OUTPUTS = Map.of(
            "api", Set.of("events:v1", "logs:v1", "http-results:v2", "step-results:v1", "script-results:v1", "dead-letter:v1"),
            "jmeter", Set.of("events:v1", "metrics:v1", "resources:v1", "errors:v1", "dead-letter:v1"),
            "playwright", Set.of("events:v1", "step-results:v1"),
            "appium", Set.of("events:v1", "step-results:v1"));
    private final DesktopRuntimeBroker broker;
    private final String secret;
    public DesktopBrokerController(DesktopRuntimeBroker broker, @Value("${testhub.desktop.broker-key:}") String secret) {
        this.broker = broker; this.secret = secret;
    }

    @PostMapping("/command")
    public Map<String, Object> command(@RequestBody Command command, HttpServletRequest request) {
        authenticate(command, request);
        String prefix = "testhub:runner:" + command.engine() + ":";
        String operation = command.operation();
        if (operation == null) throw badRequest();
        Object result = switch (operation) {
            case "ping" -> { broker.check(); yield "PONG"; }
            case "heartbeat" -> {
                if (command.ttlSeconds() < 1 || command.ttlSeconds() > 300 || command.body() == null || command.body().length() > 16384) throw badRequest();
                broker.publish(prefix + "members:v1", prefix + "heartbeat:v1:" + command.runnerId(), command.runnerId(), command.body(), Duration.ofSeconds(command.ttlSeconds()));
                yield "OK";
            }
            case "stop" -> { broker.delete(prefix + "heartbeat:v1:" + command.runnerId()); yield "OK"; }
            case "publish" -> {
                if (command.stream() == null || !command.stream().startsWith(prefix)
                        || !OUTPUTS.get(command.engine()).contains(command.stream().substring(prefix.length()))) throw forbidden();
                validateFields(command.fields());
                yield broker.append(command.stream(), command.fields());
            }
            case "group", "read", "claim", "ack" -> {
                String suffix = command.stream() == null || !command.stream().startsWith(prefix) ? "" : command.stream().substring(prefix.length());
                if (!Set.of("tasks:v1", "cancellations:v1").contains(suffix)) throw forbidden();
                String expectedGroup = "testhub-" + command.engine() + "-runner" + (suffix.startsWith("cancellations") ? "-cancellations" : "") + "-v1";
                if (!expectedGroup.equals(command.group())) throw forbidden();
                yield switch (operation) {
                    case "group" -> {
                        try { yield broker.createGroup(command.stream(), ReadOffset.from("0-0"), command.group()); }
                        catch (IllegalStateException exception) {
                            if (exception.getMessage().startsWith("BUSYGROUP")) throw new ResponseStatusException(HttpStatus.CONFLICT, "BUSYGROUP");
                            throw exception;
                        }
                    }
                    case "read" -> {
                        if (command.offset() == null || !command.offset().matches(">|0|[0-9]{1,18}-0")) throw badRequest();
                        validateCount(command.count());
                        yield wire(broker.readGroup(command.stream(), command.group(), command.runnerId(), command.count(), command.offset()));
                    }
                    case "claim" -> {
                        validateCount(command.count());
                        if (command.idleMillis() < 1000 || command.idleMillis() > 86400000) throw badRequest();
                        yield wire(broker.claim(command.stream(), command.group(), command.runnerId(), command.idleMillis(), command.count(), true));
                    }
                    default -> {
                        if (command.ids() == null || command.ids().isEmpty() || command.ids().size() > 1000
                                || command.ids().stream().anyMatch(id -> id == null || !id.matches("[0-9]{1,18}-0"))) throw badRequest();
                        yield broker.acknowledge(command.stream(), command.group(), command.runnerId(), command.ids());
                    }
                };
            }
            default -> throw badRequest();
        };
        return Map.of("result", result);
    }

    private void authenticate(Command command, HttpServletRequest request) {
        if (!loopback(request.getRemoteAddr()) || !loopback(request.getServerName()) || request.getHeader("Origin") != null) throw forbidden();
        if (command.engine() == null || !ENGINES.contains(command.engine()) || command.runnerId() == null
                || !command.runnerId().matches("[A-Za-z0-9._-]{1,64}") || secret.length() < 32) throw forbidden();
        String provided = request.getHeader("X-TestHub-Desktop-Token");
        if (provided == null || !provided.matches("[0-9a-f]{64}") || !MessageDigest.isEqual(
                token(secret, command.engine(), command.runnerId()).getBytes(StandardCharsets.US_ASCII), provided.getBytes(StandardCharsets.US_ASCII))) throw forbidden();
    }
    static String token(String secret, String engine, String runnerId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("testhub.desktop-broker.v1:" + engine + ":" + runnerId).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) { throw new IllegalStateException("Unable to authenticate local Runner", exception); }
    }
    private static boolean loopback(String address) { return Set.of("127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1", "localhost").contains(address == null ? "" : address); }
    private static void validateCount(int count) { if (count < 1 || count > 1000) throw badRequest(); }
    private static void validateFields(Map<String, String> fields) {
        if (fields == null || fields.isEmpty() || fields.size() > 128) throw badRequest();
        long size = 0;
        for (var field : fields.entrySet()) {
            if (field.getKey() == null || field.getKey().length() > 128 || field.getValue() == null) throw badRequest();
            size += field.getKey().length() + field.getValue().length();
        }
        if (size > 4 * 1024 * 1024) throw badRequest();
    }
    private static List<List<Object>> wire(List<MapRecord<String, Object, Object>> records) {
        return records.stream().map(record -> {
            List<Object> fields = new ArrayList<>();
            record.getValue().forEach((key, value) -> { fields.add(key); fields.add(value); });
            return List.<Object>of(record.getId().getValue(), fields);
        }).toList();
    }
    private static ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "Local Runner access denied"); }
    private static ResponseStatusException badRequest() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid local Runner command"); }
    public record Command(String engine, String runnerId, String operation, String stream, String group, String offset,
                          int count, long idleMillis, List<String> ids, Map<String, String> fields, String body, int ttlSeconds) {}
}
