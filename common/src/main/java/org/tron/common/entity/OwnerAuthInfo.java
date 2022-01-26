package org.tron.common.entity;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class OwnerAuthInfo implements Serializable {

  private String ownerAddress;

  private List<AuthInfo> oldAuthList;

  private List<AuthInfo> newAuthList;

  public OwnerAuthInfo(String ownerAddress, List<AuthInfo> oldAuthList, List<AuthInfo> newAuthList) {
    this.ownerAddress = ownerAddress;
    this.oldAuthList = oldAuthList;
    this.newAuthList = newAuthList;
  }
}
