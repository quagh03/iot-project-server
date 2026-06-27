package com.huylq.iotprojectserver.security.device;

import com.huylq.iotprojectserver.registry.Device;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "device_scopes")
@IdClass(DeviceScopeId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceScope {

  @Id
  @Column(name = "device_id", nullable = false, length = 64)
  private String deviceId;

  // Stored as the raw DB value e.g. "telemetry:publish" — colons are invalid in enum names,
  // and @Convert is disallowed on @Id fields in Hibernate 7, so we use String here.
  @Id
  @Column(name = "scope", nullable = false, length = 32)
  private String scope;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "device_id", insertable = false, updatable = false)
  private Device device;

  public Scope getScopeEnum() {
    return Scope.fromDbValue(scope);
  }

  public void setScopeEnum(Scope s) {
    this.scope = s.getDbValue();
  }

  public enum Scope {
    TELEMETRY_PUBLISH("telemetry:publish"),
    COMMAND_SUBSCRIBE("command:subscribe"),
    COMMAND_ACK("command:ack"),
    HEARTBEAT_PUBLISH("heartbeat:publish");

    private final String dbValue;

    Scope(String dbValue) {
      this.dbValue = dbValue;
    }

    public String getDbValue() {
      return dbValue;
    }

    public static Scope fromDbValue(String value) {
      for (Scope s : values()) {
        if (s.dbValue.equals(value)) return s;
      }
      throw new IllegalArgumentException("Unknown scope: " + value);
    }
  }
}
