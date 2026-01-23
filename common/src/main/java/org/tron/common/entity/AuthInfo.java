package org.tron.common.entity;

import lombok.Data;
import org.springframework.util.CollectionUtils;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.StringUtil;
import org.tron.protos.Protocol;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * === TronLink Feature ===
 */
@Data
public class AuthInfo implements Serializable {


  private String ownerAddress; // 授权地址，是from

  private String authAddress;  // 授权地址，是to

  private String operations;   // 权限组

  private Integer permissionType; // 权限类型 0=owner, 1=witness,2=active

  private Integer permissionId; // 权限id

  private Long threshold;     // 权限阈值

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

  public static List<AuthInfo> getAuthList(String ownerAddress,
                                    Protocol.Permission owner,
                                    Protocol.Permission witness,
                                    List<Protocol.Permission> actives) {
    List<AuthInfo> authInfoList = new ArrayList<>();

    List<AuthInfo> ownerAuthInfoList = getAuthInfo(ownerAddress, owner);
    authInfoList.addAll(ownerAuthInfoList);

    List<AuthInfo> witnessAuthInfoList = getAuthInfo(ownerAddress, witness);
    authInfoList.addAll(witnessAuthInfoList);

    if (!CollectionUtils.isEmpty(actives)) {
      actives.forEach(item -> {
        List<AuthInfo> activeAuthInfoList = getAuthInfo(ownerAddress, item);
        authInfoList.addAll(activeAuthInfoList);
      });
    }

    return authInfoList;
  }

  private static List<AuthInfo> getAuthInfo(String ownerAddress, Protocol.Permission permission) {
    List<AuthInfo> resultList = new ArrayList<>();
    List<Protocol.Key> keyList = permission.getKeysList();
    if (CollectionUtils.isEmpty(keyList)) {
      return resultList;
    }

    keyList.forEach(key->{
      AuthInfo authInfo = new AuthInfo(ownerAddress, StringUtil.encode58Check(key.getAddress().toByteArray()),
          ByteArray.toHexString(permission.getOperations().toByteArray()), permission.getType().getNumber(),
          permission.getId(), permission.getThreshold(), key.getWeight());
      resultList.add(authInfo);
    });

    return resultList;
  }
}
