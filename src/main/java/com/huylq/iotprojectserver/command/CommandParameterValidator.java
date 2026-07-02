package com.huylq.iotprojectserver.command;

import com.huylq.iotprojectserver.common.error.ApiException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Command-parameter whitelist per {@code device_type} (System Design §7 input validation;
 * device-team spec §5) — the injection defense against free-form passthrough to the
 * device. Unknown action/params, or a value outside the documented contract, → {@code 422}
 * with the offending token.
 *
 * <p>{@code curtain}'s wire contract is flagged as an unresolved conflict between the
 * device-team spec ({@code direction: UP|DOWN|STOP}) and the API/system-design docs
 * ({@code OPEN|CLOSED}) — this whitelist follows the device-team (firmware) spec since
 * that's what the actual hardware implements.
 */
final class CommandParameterValidator {

  private static final Set<String> ON_OFF = Set.of("ON", "OFF");

  private CommandParameterValidator() {
  }

  record ValidatedCommand(String desiredState, Map<String, Object> attributes) {
  }

  static ValidatedCommand validate(String deviceType, String action, Map<String, Object> parameters) {
    if (!"SET".equals(action)) {
      throw ApiException.unprocessable("Unsupported action: " + action);
    }
    Map<String, Object> params = parameters == null ? Map.of() : parameters;
    return switch (deviceType) {
      case "light" -> validateLight(params);
      case "ac" -> validateAc(params);
      case "exhst_fan" -> validateExhaustFan(params);
      case "curtain" -> validateCurtain(params);
      default -> throw ApiException.unprocessable("No command contract for device_type: " + deviceType);
    };
  }

  private static ValidatedCommand validateLight(Map<String, Object> p) {
    String status = requireEnum(p, "status", ON_OFF);
    Map<String, Object> attrs = withoutKeys(p, "status");
    if (attrs.containsKey("level")) requireIntRange(p, "level", 0, 100);
    rejectUnknown(attrs.keySet(), Set.of("level"));
    return new ValidatedCommand(status, attrs);
  }

  private static ValidatedCommand validateAc(Map<String, Object> p) {
    String status = requireEnum(p, "status", ON_OFF);
    Map<String, Object> attrs = withoutKeys(p, "status");
    if (attrs.containsKey("set_temp")) requireNumberRange(p, "set_temp", 16, 30);
    if (attrs.containsKey("mode")) requireEnum(p, "mode", Set.of("COOL", "HEAT", "DRY", "FAN", "AUTO"));
    rejectUnknown(attrs.keySet(), Set.of("set_temp", "mode"));
    return new ValidatedCommand(status, attrs);
  }

  private static ValidatedCommand validateExhaustFan(Map<String, Object> p) {
    String status = requireEnum(p, "status", ON_OFF);
    Map<String, Object> attrs = withoutKeys(p, "status");
    rejectUnknown(attrs.keySet(), Set.of());
    return new ValidatedCommand(status, attrs);
  }

  private static ValidatedCommand validateCurtain(Map<String, Object> p) {
    String direction = requireEnum(p, "direction", Set.of("UP", "DOWN", "STOP"));
    Map<String, Object> attrs = withoutKeys(p, "direction");
    rejectUnknown(attrs.keySet(), Set.of());
    return new ValidatedCommand(direction, attrs);
  }

  private static Map<String, Object> withoutKeys(Map<String, Object> p, String... keys) {
    Map<String, Object> copy = new LinkedHashMap<>(p);
    for (String k : keys) copy.remove(k);
    return copy;
  }

  private static String requireEnum(Map<String, Object> p, String key, Set<String> allowed) {
    Object v = p.get(key);
    if (!(v instanceof String s) || !allowed.contains(s)) {
      throw ApiException.unprocessable("parameters." + key + " must be one of " + allowed);
    }
    return s;
  }

  private static void requireIntRange(Map<String, Object> p, String key, int min, int max) {
    Object v = p.get(key);
    if (!(v instanceof Number n) || n.intValue() < min || n.intValue() > max) {
      throw ApiException.unprocessable("parameters." + key + " must be an integer between " + min + " and " + max);
    }
  }

  private static void requireNumberRange(Map<String, Object> p, String key, double min, double max) {
    Object v = p.get(key);
    if (!(v instanceof Number n) || n.doubleValue() < min || n.doubleValue() > max) {
      throw ApiException.unprocessable("parameters." + key + " must be a number between " + min + " and " + max);
    }
  }

  private static void rejectUnknown(Set<String> keys, Set<String> allowed) {
    for (String k : keys) {
      if (!allowed.contains(k)) {
        throw ApiException.unprocessable("Unknown parameter: " + k);
      }
    }
  }
}
