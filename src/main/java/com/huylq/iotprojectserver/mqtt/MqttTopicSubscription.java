package com.huylq.iotprojectserver.mqtt;

import org.eclipse.paho.client.mqttv3.IMqttMessageListener;

/**
 * One topic↔handler registration. {@link MqttClientLifecycle} collects every bean of
 * this type and (re-)subscribes all of them on connect/reconnect, so each module wires
 * its own listener without touching the connection-management code.
 */
public record MqttTopicSubscription(String topicFilter, int qos, IMqttMessageListener listener) {
}
