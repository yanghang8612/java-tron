package org.tron.common.logsfilter.trigger;

import com.beust.jcommander.internal.Lists;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * === JustLend Feature ===
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class JustlendTrackerTrigger extends Trigger {

  private Long blockNumber;

  private String parentHash;

  private String blockHash;

  private Boolean solidity = false;

  private List<AssetStatusPojo> assetStatusList = Lists.newArrayList();

  public JustlendTrackerTrigger() {
    super();
    setTriggerName(Trigger.JUSTLEND_TRACKER_TRIGGER_NAME);
  }

  @Data
  public static class AssetStatusPojo {
    private Integer miningType;
    private String accountAddress;
    private String tokenAddress;
    private String balance;
    private String accountTotalBorrow;
    private String marketTotalBorrow;
  }

  public enum HexTopicEnum {
    TRANSFER("Transfer(address,address,uint256)", "ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"),
    BORROW("Borrow(address,uint256,uint256,uint256,uint256)", "2dd79f4fccfd18c360ce7f9132f3621bf05eee18f995224badb32d17f172df73"),
    REPAY_BORROW("RepayBorrow(address,address,uint256,uint256,uint256,uint256)", "6fadbf7329d21f278e724fa0d4511001a158f2a97ee35c5bc4cf8b64417399ef"),
    SNAPSHOT("SnapShot(address,uint256)", "b7a265ee24a245917823a466fcc8a6011cf216dd22b70a297bd9a26315a19ec6"),
    RENT_RESOURCE("RentResource(address,address,uint256,uint256,uint256,uint256,uint256,uint256)", "cbf7e7bd355ef86ba2c3d4f8c101abbec40074070c31fcb142bd713cd5409158"),
    RETURN_RESOURCE("ReturnResource(address,address,uint256,uint256,uint256,uint256,uint256,uint256,uint256)", "f7e21d5bf17851f93ab7bda7e390841620f59dfbe9d86add32824f33bd40d3f5"),
    RENT_LIQUIDATE("Liquidate(address,address,address,uint256,uint256,uint256,uint256,uint256)", "b0dbe18c6ffdf0da655dd690e77211d379205c497be44c64447c3f5f021b5167"),
    UNKNOWN("UNKNOWN()", "0c78932dd210147f42a4ec6c5a353697626c4043d49be5f063518e57f3399e61");

    @Getter
    private String sign;
    @Getter
    private String signHash;

    HexTopicEnum(String sign, String signHash) {
      this.sign = sign;
      this.signHash = signHash;
    }

    public static HexTopicEnum getBySignHash(String signHash) {
      for (HexTopicEnum value : HexTopicEnum.values()) {
        if (value.signHash.equals(signHash)) {
          return value;
        }
      }
      return UNKNOWN;
    }
  }

  public enum JTokenAddressEnum {
    jTRX("jTRX", "TE2RzoSV3wFK99w6J9UnnZ4vLfXYoxvRwP"),
    jUSDT("jUSDT", "TXJgMdjVX5dKiQaUi9QobwNxtSQaFqccvd"),
    jBTC("jBTC", "TLeEu311Cbw63BcmMHDgDLu7fnk9fqGcqT"),
    jSUN("jSUN", "TGBr8uh9jBVHJhhkwSJvQN2ZAKzVkxDmno"),
    jJST("jJST", "TWQhCXaWz4eHK4Kd1ErSDHjMFPoPc9czts"),
    jUSDJ("jUSDJ", "TL5x9MtSnDy537FXKx53yAaHRRNdg9TkkA"),
    jWBTT("jWBTT", "TUY54PVeH6WCcYCd6ZXXoBDsHytN9V5PXt"),
    jWIN("jWIN", "TRg6MnpsFXc82ymUPgf5qbj59ibxiEDWvv");

    @Getter
    private String name;
    @Getter
    private String tokenAddress;

    JTokenAddressEnum(String name, String tokenAddress) {
      this.name = name;
      this.tokenAddress = tokenAddress;
    }

    public static JTokenAddressEnum getByTokenAddress(String tokenAddress) {
      for (JTokenAddressEnum value : JTokenAddressEnum.values()) {
        if (value.tokenAddress.equals(tokenAddress)) {
          return value;
        }
      }
      return null;
    }
  }


  public enum MiningTypeEnum {
    DEPOSIT(1, "存款挖矿"),
    BORROW(2, "借款挖矿"),
    RENT(3, "能量租赁挖矿"),
    UNKNOWN(-1, "未知");

    @Getter
    private Integer type;
    @Getter
    private String desc;

    MiningTypeEnum(Integer type, String desc) {
      this.type = type;
      this.desc = desc;
    }

    public static MiningTypeEnum getByType(Integer type) {
      for (MiningTypeEnum value : MiningTypeEnum.values()) {
        if (value.type.equals(type)) {
          return value;
        }
      }
      return null;
    }

  }

  public static String contactOrder(String rent, String receiver, String resourceType){
    return rent + "_" + receiver + "_" + resourceType;
  }

}


