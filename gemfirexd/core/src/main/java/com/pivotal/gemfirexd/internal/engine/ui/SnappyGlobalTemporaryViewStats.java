package com.pivotal.gemfirexd.internal.engine.ui;

import java.util.Map;

public class SnappyGlobalTemporaryViewStats {

  private String tableName;
  private String fullyQualifiedName;
  private String tableType;
  private String comment;
  private Object schema;
  private Map<String, String> properties;

  public SnappyGlobalTemporaryViewStats(String name, String qname, String type, String comment,
      Object schema, Map<String, String> properties) {
    this.tableName = name;
    this.fullyQualifiedName = qname;
    this.tableType = type;
    this.comment = comment;
    this.schema = schema;
    this.properties = properties;
  }

  public String getTableName() {
    return tableName;
  }

  public String getFullyQualifiedName() {
    return fullyQualifiedName;
  }

  public String getTableType() {
    return tableType;
  }

  public String getComment() {
    return comment;
  }

  public Object getSchema() {
    return schema;
  }

  public Map<String, String> getProperties() {
    return properties;
  }

}
