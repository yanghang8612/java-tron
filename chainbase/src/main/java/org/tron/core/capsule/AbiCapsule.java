package org.tron.core.capsule;

import lombok.extern.slf4j.Slf4j;
import org.tron.protos.contract.SmartContractOuterClass;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract.ABI;

@Slf4j(topic = "capsule")
public class AbiCapsule implements ProtoCapsule<ABI> {

  private ABI abi;

  public AbiCapsule(SmartContract contract) {
    this.abi = contract.getAbi();
  }

  @Override
  public byte[] getData() {
    return abi.toByteArray();
  }

  @Override
  public ABI getInstance() {
    return abi;
  }
}
