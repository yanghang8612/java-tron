package org.tron.common.entity;

import lombok.Data;
import org.tron.common.runtime.vm.LogInfo;

import java.util.List;

/**
 * @author ：Tron
 * @description ：
 * @date ：2021/6/15
 */
@Data
public class AssetTransferLogInfo {

  private String txId;

  private List<LogInfo> logInfoList;
}
