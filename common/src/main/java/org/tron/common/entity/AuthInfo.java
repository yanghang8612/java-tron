package org.tron.common.entity;

import lombok.Data;

import java.io.Serializable;

@Data
public class AuthInfo implements Serializable {


  private String ownerAddress; // 授权地址，是from

  private String authAddress;  // 授权地址，是to

  private String operations;   // 权限组

  private Integer permissionType; // 权限类型 0=owner, 1=witness,2=active

  private Integer permissionId; // 权限id

  private Long threshold;     // 权限id

  private Long weight;        // 权限权重

  public AuthInfo(String ownerAddress, String authAddress, String operations, Integer permissionType, Integer permissionId, Long threshold, Long weight) {
    this.ownerAddress = ownerAddress;
    this.authAddress = authAddress;
    this.operations = operations;
    this.permissionType = permissionType;
    this.permissionId = permissionId;
    this.threshold = threshold;
    this.weight = weight;
  }
}
