package com.huylq.iotprojectserver.common.idempotency;

import com.huylq.iotprojectserver.common.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Controller-facing wrapper around {@link IdempotencyService} that turns the
 * lookup/replay/store dance into a single call.
 *
 * <pre>{@code
 *   return idempotency.run(key, "POST /v1/devices", requestBodyJson, DeviceDto.class,
 *       () -> ResponseEntity.created(uri).body(dto));
 * }</pre>
 *
 * <p>When {@code key} is {@code null} the action runs once with no replay protection.
 * On replay the original status + body are returned; response <em>headers</em>
 * (e.g. {@code Location}) are not stored, so a replayed {@code 201} omits them — the
 * body still carries the resource identifiers a retrying client needs.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyHelper {

  private final IdempotencyService service;
  private final ObjectMapper mapper;

  @SuppressWarnings("unchecked")
  public <T> ResponseEntity<T> run(UUID key, String endpoint, String requestBody,
                                   Class<T> type, Supplier<ResponseEntity<T>> action) {
    if (key == null) {
      return action.get();
    }

    IdempotencyResult lookup = service.lookup(key, endpoint, requestBody);
    switch (lookup.kind()) {
      case CONFLICT -> throw ApiException.conflict("Idempotency-Key reused with a different request");
      case REPLAY -> {
        T body = mapper.convertValue(lookup.responseBody(), type);
        return ResponseEntity.status(lookup.responseStatus()).body(body);
      }
      default -> {
        ResponseEntity<T> response = action.get();
        Map<String, Object> bodyMap = response.getBody() == null
            ? Map.of()
            : mapper.convertValue(response.getBody(), Map.class);
        service.store(key, endpoint, requestBody, (short) response.getStatusCode().value(), bodyMap);
        return response;
      }
    }
  }
}
