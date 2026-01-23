package org.tron.common.entity;

import lombok.Data;

/**
 * === TronLink Feature ===
 */
@Data
public class AssetTransferInfo {

  public String fromAddress;

  public String toAddress;

  public String txId;

  public String tokenAddress;  //trc20, trc721, trc10

  public String assetId;  //trc721

  public String note;  //备注

  public String amount; //trx, trc10, trc20, trc721

  public Integer assetType; // 0=trx, 1=trc10, 2=trc20, 3=trc721

  public Boolean isSuccess;
}
