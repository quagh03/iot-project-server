package com.huylq.iotprojectserver.mqtt;

import com.huylq.iotprojectserver.telemetry.TelemetryIngestCommand;
import com.huylq.iotprojectserver.telemetry.TelemetryService;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The one hard requirement for this listener: an exception must never escape
 * {@code messageArrived} — Paho kills the callback thread for the whole connection if
 * one does, and MQTT has no response channel to report a rejection anyway.
 */
@ExtendWith(MockitoExtension.class)
class TelemetryMqttListenerTest {

  @Mock private TelemetryService telemetryService;

  private TelemetryMqttListener listener;

  @BeforeEach
  void setUp() {
    listener = new TelemetryMqttListener(telemetryService, JsonMapper.builder().build());
  }

  @Test
  void malformed_json_never_propagates() {
    MqttMessage message = new MqttMessage("{not valid json".getBytes(StandardCharsets.UTF_8));

    assertThatCode(() -> listener.messageArrived("iot/telemetry/office_1/gw_1", message))
        .doesNotThrowAnyException();

    verify(telemetryService, never()).ingest(any());
  }

  @Test
  void malformed_topic_never_propagates() {
    String payload = """
        {"timestamp":"2026-06-25T10:30:00Z","zone":"office_1","gateway_id":"gw_1","sensors":[]}
        """;
    MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));

    assertThatCode(() -> listener.messageArrived("iot/telemetry/office_1", message))
        .doesNotThrowAnyException();

    verify(telemetryService, never()).ingest(any());
  }

  @Test
  void topic_gatewayId_not_matching_payload_gatewayId_is_dropped_not_thrown() {
    String payload = """
        {"timestamp":"2026-06-25T10:30:00Z","zone":"office_1","gateway_id":"gw_other",
         "sensors":[{"id":"s_temp_1","type":"temp","value":22.4,"unit":"C"}]}
        """;
    MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));

    assertThatCode(() -> listener.messageArrived("iot/telemetry/office_1/gw_1", message))
        .doesNotThrowAnyException();

    verify(telemetryService, never()).ingest(any());
  }

  @Test
  void service_throwing_never_propagates_out_of_the_callback() {
    org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(telemetryService).ingest(any());
    String payload = """
        {"timestamp":"2026-06-25T10:30:00Z","zone":"office_1","gateway_id":"gw_1",
         "sensors":[{"id":"s_temp_1","type":"temp","value":22.4,"unit":"C"}]}
        """;
    MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));

    assertThatCode(() -> listener.messageArrived("iot/telemetry/office_1/gw_1", message))
        .doesNotThrowAnyException();
  }

  @Test
  void valid_message_maps_to_ingest_command() {
    String payload = """
        {"timestamp":"2026-06-25T10:30:00Z","zone":"office_1","gateway_id":"gw_1",
         "sensors":[
           {"id":"s_temp_1","type":"temp","value":22.4,"unit":"C"},
           {"id":"s_smoke_1","type":"smoke","value":false}
         ]}
        """;
    MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));

    listener.messageArrived("iot/telemetry/office_1/gw_1", message);

    verify(telemetryService).ingest(org.mockito.ArgumentMatchers.argThat((TelemetryIngestCommand cmd) ->
        cmd.zone().equals("office_1")
            && cmd.gatewayId().equals("gw_1")
            && cmd.authenticatedDeviceId() == null
            && cmd.readings().size() == 2));
  }
}
