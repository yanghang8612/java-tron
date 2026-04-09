package org.tron.common.entity;

import lombok.Data;
import org.tron.common.runtime.vm.LogInfo;

import java.util.List;

/**
 * === TronLink Feature ===
 */
@Data
public class AssetTransferLogInfo {

  private String txId;

  private String note;

  private Boolean isSuccess;

  private List<LogInfo> logInfoList;
}
