package com.huylq.iotprojectserver.rules;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Rule {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "rule_id", updatable = false, nullable = false)
  private UUID ruleId;

  @Column(name = "name", nullable = false, length = 128)
  private String name;

  @Builder.Default
  @Column(name = "enabled", nullable = false)
  private Boolean enabled = Boolean.TRUE;

  @Column(name = "condition", nullable = false, columnDefinition = "text")
  private String condition;

  @Column(name = "action", nullable = false, columnDefinition = "text")
  private String action;

  @Builder.Default
  @Column(name = "priority", nullable = false)
  private Integer priority = 0;

  @Column(name = "created_by", nullable = false, length = 64)
  private String createdBy;

  @Version
  @Column(name = "version", nullable = false)
  private Integer version;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;
}
