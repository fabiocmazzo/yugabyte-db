// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.models;

import com.avaje.ebean.Model;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.data.validation.Constraints;
import play.libs.Json;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Entity
public class Alert extends Model {

  @Constraints.Required
  @Id
  @Column(nullable = false, unique = true)
  public UUID uuid;

  @Constraints.Required
  @Column(nullable = false)
  public UUID customerUUID;

  @Constraints.Required
  @Column(nullable = false)
  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd hh:mm:ss")
  private Date createTime;

  @Constraints.Required
  @Column(length = 255)
  public String type;

  @Constraints.Required
  @Column(columnDefinition = "Text", nullable = false)
  public String message;

  public static final Logger LOG = LoggerFactory.getLogger(Alert.class);
  private static final Find<UUID, Alert> find = new Find<UUID, Alert>() {};

  /**
   * Create new alert.
   *
   * @param uuid
   * @param customerUUID
   * @param createTime
   * @param type
   * @param message
   * @return new alert
   */
  public static Alert create(UUID customerUUID, String type, String message) {
    Alert alert = new Alert();
    alert.uuid = UUID.randomUUID();
    alert.customerUUID = customerUUID;
    alert.createTime = new Date();
    alert.type = type;
    alert.message = message;
    alert.save();
    return alert;
  }

  public JsonNode toJson() {
    ObjectNode json = Json.newObject()
        .put("uuid", uuid.toString())
        .put("customerUUID", customerUUID.toString())
        .put("createTime", createTime.toString())
        .put("type", type)
        .put("message", message);
    return json;
  }

  public static List<Alert> get(UUID customerUUID) {
    return find.where().eq("customer_uuid", customerUUID).findList();
  }
}
