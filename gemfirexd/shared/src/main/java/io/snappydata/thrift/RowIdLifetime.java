/**
 * Autogenerated by Thrift Compiler (0.9.3)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package io.snappydata.thrift;


import java.util.Map;
import java.util.HashMap;
import org.apache.thrift.TEnum;

public enum RowIdLifetime implements org.apache.thrift.TEnum {
  ROWID_UNSUPPORTED(1),
  ROWID_VALID_OTHER(2),
  ROWID_VALID_SESSION(3),
  ROWID_VALID_TRANSACTION(4),
  ROWID_VALID_FOREVER(5);

  private final int value;

  private RowIdLifetime(int value) {
    this.value = value;
  }

  /**
   * Get the integer value of this enum value, as defined in the Thrift IDL.
   */
  public int getValue() {
    return value;
  }

  /**
   * Find a the enum type by its integer value, as defined in the Thrift IDL.
   * @return null if the value is not found.
   */
  public static RowIdLifetime findByValue(int value) { 
    switch (value) {
      case 1:
        return ROWID_UNSUPPORTED;
      case 2:
        return ROWID_VALID_OTHER;
      case 3:
        return ROWID_VALID_SESSION;
      case 4:
        return ROWID_VALID_TRANSACTION;
      case 5:
        return ROWID_VALID_FOREVER;
      default:
        return null;
    }
  }
}
