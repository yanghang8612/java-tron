package org.tron.common.entity;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * === TronLink Feature ===
 */
@Data
public class OwnerAuthInfo implements Serializable {

  private String ownerAddress;

  private List<AuthInfo> oldAuthList;

  private List<AuthInfo> newAuthList;
}
