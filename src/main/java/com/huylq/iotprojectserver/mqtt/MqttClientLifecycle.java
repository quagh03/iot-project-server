package com.huylq.iotprojectserver.mqtt;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the single shared MQTT connection (System Design §8 — persistent session,
 * reconnect with backoff, topic↔handler mapping). {@code cleanSession=false} is
 * load-bearing, not configurable: QoS-1 messages published while we're disconnected
 * must be redelivered on reconnect, which requires the broker to remember our session.
 *
 * <p>The initial connect runs on a background thread so a broker outage never blocks
 * Spring Boot startup or the HTTP API — MQTT is one ingest path, not the only one.
 * After that, Paho's {@code automaticReconnect} handles backoff, and every successful
 * (re)connect re-subscribes every registered {@link MqttTopicSubscription} — cheap and
 * defensive against the broker having dropped our session state.
 */
@Slf4j
@Component
public class MqttClientLifecycle implements SmartLifecycle {

  private final MqttProperties props;
  private final List<MqttTopicSubscription> subscriptions;
  private final MqttClient client;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicBoolean subscribed = new AtomicBoolean(false);

  public MqttClientLifecycle(MqttProperties props, List<MqttTopicSubscription> subscriptions) {
    this.props = props;
    this.subscriptions = subscriptions;
    try {
      this.client = new MqttClient(props.brokerUrl(), props.clientId(), new MemoryPersistence());
      this.client.setCallback(new Callback());
    } catch (MqttException e) {
      throw new IllegalStateException("Failed to construct MQTT client for " + props.brokerUrl(), e);
    }
  }

  @Override
  public void start() {
    running.set(true);
    Thread connectThread = new Thread(this::connect, "mqtt-initial-connect");
    connectThread.setDaemon(true);
    connectThread.start();
  }

  @Override
  public void stop() {
    running.set(false);
    subscribed.set(false);
    try {
      if (client.isConnected()) {
        client.disconnect(2_000);
      }
    } catch (MqttException e) {
      log.warn("Error disconnecting MQTT client: {}", e.getMessage());
    }
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  /**
   * True once every registered {@link MqttTopicSubscription} has been subscribed on the
   * current connection — a readiness signal for tests polling before they publish (a
   * message published before the subscribe completes is simply never delivered).
   */
  public boolean isReady() {
    return subscribed.get();
  }

  /**
   * Publish surface for later phases (command dispatch, etc.) — unused by Phase 4 itself.
   */
  public void publish(String topic, byte[] payload, int qos, boolean retained) {
    try {
      client.publish(topic, payload, qos, retained);
    } catch (MqttException e) {
      throw new IllegalStateException("MQTT publish to " + topic + " failed", e);
    }
  }

  private void connect() {
    try {
      MqttConnectOptions opts = new MqttConnectOptions();
      opts.setCleanSession(false);
      opts.setAutomaticReconnect(props.automaticReconnect());
      opts.setMaxReconnectDelay(props.maxReconnectDelayMs());
      opts.setConnectionTimeout(props.connectTimeoutSeconds());
      opts.setKeepAliveInterval(props.keepAliveIntervalSeconds());
      // Prod (MQTTS + broker auth): iot.mqtt.username/password were previously accepted
      // by application-prod.yaml but never applied here — the broker connection silently
      // ran unauthenticated. Only set when configured so local/test (allow_anonymous)
      // keeps working unchanged.
      if (props.username() != null && !props.username().isBlank()) {
        opts.setUserName(props.username());
        opts.setPassword(props.password() == null ? new char[0] : props.password().toCharArray());
      }
      client.connect(opts);
    } catch (MqttException e) {
      log.error("Initial MQTT connect to {} failed (will rely on automatic reconnect): {}",
          props.brokerUrl(), e.getMessage());
    }
  }

  private void subscribeAll() {
    if (subscriptions.isEmpty()) return;
    String[] filters = subscriptions.stream().map(MqttTopicSubscription::topicFilter).toArray(String[]::new);
    int[] qos = subscriptions.stream().mapToInt(MqttTopicSubscription::qos).toArray();
    IMqttMessageListener[] listeners = subscriptions.stream()
        .map(MqttTopicSubscription::listener).toArray(IMqttMessageListener[]::new);
    try {
      client.subscribe(filters, qos, listeners);
      subscribed.set(true);
      log.info("Subscribed to {} MQTT topic filter(s): {}", filters.length, List.of(filters));
    } catch (MqttException e) {
      log.error("MQTT subscribe failed: {}", e.getMessage());
    }
  }

  private class Callback implements MqttCallbackExtended {
    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
      log.info("MQTT connection {} to {}", reconnect ? "re-established" : "established", serverURI);
      subscribeAll();
    }

    @Override
    public void connectionLost(Throwable cause) {
      log.warn("MQTT connection lost: {}", cause.getMessage());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
      // Per-topic listeners passed to subscribe() handle delivery; this fallback only
      // fires for messages on a topic we didn't register a listener for.
      log.debug("Unhandled MQTT message on topic {}", topic);
    }

    @Override
    public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
      // No outbound publishes with delivery tracking in Phase 4.
    }
  }
}
