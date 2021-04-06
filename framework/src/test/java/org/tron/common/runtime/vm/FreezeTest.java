package org.tron.common.runtime.vm;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.junit.*;
import org.spongycastle.util.encoders.Hex;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.runtime.Runtime;
import org.tron.common.runtime.TVMTestResult;
import org.tron.common.runtime.TvmTestUtils;
import org.tron.common.storage.Deposit;
import org.tron.common.storage.DepositImpl;
import org.tron.common.utils.*;
import org.tron.core.Constant;
import org.tron.core.Wallet;
import org.tron.core.actuator.VMActuator;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.DelegatedResourceAccountIndexCapsule;
import org.tron.core.capsule.DelegatedResourceCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.Parameter;
import org.tron.core.config.args.Args;
import org.tron.core.db.Manager;
import org.tron.core.db.TransactionTrace;
import org.tron.core.store.AccountStore;
import org.tron.core.store.DelegatedResourceAccountIndexStore;
import org.tron.core.store.DelegatedResourceStore;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.vm.EnergyCost;
import org.tron.core.vm.config.ConfigLoader;
import org.tron.core.vm.config.VMConfig;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.Transaction.Result.contractResult;
import stest.tron.wallet.common.client.utils.AbiUtil;

import java.io.File;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Consumer;

import static org.tron.core.config.Parameter.ChainConstant.FROZEN_PERIOD;
import static org.tron.core.config.Parameter.ChainConstant.TRX_PRECISION;
import static org.tron.protos.Protocol.Transaction.Result.contractResult.REVERT;
import static org.tron.protos.Protocol.Transaction.Result.contractResult.SUCCESS;

@Slf4j
public class FreezeTest {

  private static final String CONTRACT_CODE = "608060405261037e806100136000396000f3fe608060405234801561001057600080fd5b50d3801561001d57600080fd5b50d2801561002a57600080fd5b50600436106100655760003560e01c8062f55d9d1461006a57806330e1e4e5146100ae5780637b46b80b1461011a578063e7aa4e0b1461017c575b600080fd5b6100ac6004803603602081101561008057600080fd5b81019080803573ffffffffffffffffffffffffffffffffffffffff1690602001909291905050506101de565b005b610104600480360360608110156100c457600080fd5b81019080803573ffffffffffffffffffffffffffffffffffffffff16906020019092919080359060200190929190803590602001909291905050506101f7565b6040518082815260200191505060405180910390f35b6101666004803603604081101561013057600080fd5b81019080803573ffffffffffffffffffffffffffffffffffffffff169060200190929190803590602001909291905050506102f0565b6040518082815260200191505060405180910390f35b6101c86004803603604081101561019257600080fd5b81019080803573ffffffffffffffffffffffffffffffffffffffff16906020019092919080359060200190929190505050610327565b6040518082815260200191505060405180910390f35b8073ffffffffffffffffffffffffffffffffffffffff16ff5b60008373ffffffffffffffffffffffffffffffffffffffff168383d5158015610224573d6000803e3d6000fd5b50423073ffffffffffffffffffffffffffffffffffffffff1663e7aa4e0b86856040518363ffffffff1660e01b8152600401808373ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff1681526020018281526020019250505060206040518083038186803b1580156102ab57600080fd5b505afa1580156102bf573d6000803e3d6000fd5b505050506040513d60208110156102d557600080fd5b81019080805190602001909291905050500390509392505050565b60008273ffffffffffffffffffffffffffffffffffffffff1682d615801561031c573d6000803e3d6000fd5b506001905092915050565b60008273ffffffffffffffffffffffffffffffffffffffff1682d790509291505056fea26474726f6e58200fd975eab4a8c8afe73bf3841efe4da7832d5a0d09f07115bb695c7260ea642164736f6c63430005100031";
  private static final String FACTORY_CODE = "6080604052610640806100136000396000f3fe608060405234801561001057600080fd5b50d3801561001d57600080fd5b50d2801561002a57600080fd5b50600436106100505760003560e01c806341aa901414610055578063bb63e785146100c3575b600080fd5b6100816004803603602081101561006b57600080fd5b8101908080359060200190929190505050610131565b604051808273ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff16815260200191505060405180910390f35b6100ef600480360360208110156100d957600080fd5b810190808035906020019092919050505061017d565b604051808273ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff16815260200191505060405180910390f35b6000806060604051806020016101469061026e565b6020820181038252601f19601f820116604052509050838151602083016000f59150813b61017357600080fd5b8192505050919050565b60008060a060f81b3084604051806020016101979061026e565b6020820181038252601f19601f820116604052508051906020012060405160200180857effffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff19167effffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff191681526001018473ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff1660601b81526014018381526020018281526020019450505050506040516020818303038152906040528051906020012060001c905080915050919050565b6103918061027c8339019056fe608060405261037e806100136000396000f3fe608060405234801561001057600080fd5b50d3801561001d57600080fd5b50d2801561002a57600080fd5b50600436106100655760003560e01c8062f55d9d1461006a57806330e1e4e5146100ae5780637b46b80b1461011a578063e7aa4e0b1461017c575b600080fd5b6100ac6004803603602081101561008057600080fd5b81019080803573ffffffffffffffffffffffffffffffffffffffff1690602001909291905050506101de565b005b610104600480360360608110156100c457600080fd5b81019080803573ffffffffffffffffffffffffffffffffffffffff16906020019092919080359060200190929190803590602001909291905050506101f7565b6040518082815260200191505060405180910390f35b6101666004803603604081101561013057600080fd5b81019080803573ffffffffffffffffffffffffffffffffffffffff169060200190929190803590602001909291905050506102f0565b6040518082815260200191505060405180910390f35b6101c86004803603604081101561019257600080fd5b81019080803573ffffffffffffffffffffffffffffffffffffffff16906020019092919080359060200190929190505050610327565b6040518082815260200191505060405180910390f35b8073ffffffffffffffffffffffffffffffffffffffff16ff5b60008373ffffffffffffffffffffffffffffffffffffffff168383d5158015610224573d6000803e3d6000fd5b50423073ffffffffffffffffffffffffffffffffffffffff1663e7aa4e0b86856040518363ffffffff1660e01b8152600401808373ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff1681526020018281526020019250505060206040518083038186803b1580156102ab57600080fd5b505afa1580156102bf573d6000803e3d6000fd5b505050506040513d60208110156102d557600080fd5b81019080805190602001909291905050500390509392505050565b60008273ffffffffffffffffffffffffffffffffffffffff1682d615801561031c573d6000803e3d6000fd5b506001905092915050565b60008273ffffffffffffffffffffffffffffffffffffffff1682d790509291505056fea26474726f6e58200fd975eab4a8c8afe73bf3841efe4da7832d5a0d09f07115bb695c7260ea642164736f6c63430005100031a26474726f6e5820403c4e856a1ab2fe0eeaf6b157c29c07fef7a9e9bdc6f0faac870d2d8873159d64736f6c63430005100031";

  private static final long value = 100_000_000_000_000_000L;
  private static final long fee = 1_000_000_000;
  private static final String userAStr = "27k66nycZATHzBasFT9782nTsYWqVtxdtAc";
  private static final byte[] userA = Commons.decode58Check(userAStr);
  private static final String userBStr = "27jzp7nVEkH4Hf3H1PHPp4VDY7DxTy5eydL";
  private static final byte[] userB = Commons.decode58Check(userBStr);
  private static final String userCStr = "27juXSbMvL6pb8VgmKRgW6ByCfw5RqZjUuo";
  private static final byte[] userC = Commons.decode58Check(userCStr);

  private static String dbPath;
  private static TronApplicationContext context;
  private static Manager manager;
  private static String ownerStr;
  private static byte[] owner;
  private static Deposit rootDeposit;

  private enum OpType {
    FREEZE, UNFREEZE
  }

  @Before
  public void init() throws Exception {
    dbPath = "output_" + FreezeTest.class.getName();
    Args.setParam(new String[]{"--output-directory", dbPath, "--debug"}, Constant.TEST_CONF);
    context = new TronApplicationContext(DefaultConfig.class);
    manager = context.getBean(Manager.class);
    owner = Hex.decode(Wallet.getAddressPreFixString() + "abd4b9367799eaa3197fecb144eb71de1e049abc");
    ownerStr = StringUtil.encode58Check(owner);
    rootDeposit = DepositImpl.createRoot(manager);
    rootDeposit.createAccount(owner, Protocol.AccountType.Normal);
    rootDeposit.addBalance(owner, 900_000_000_000_000_000L);
    rootDeposit.commit();

    ConfigLoader.disable = true;
    CommonParameter.getInstance().setBlockNumForEnergyLimit(0);
    manager.getDynamicPropertiesStore().saveAllowTvmFreeze(1);
    VMConfig.initVmHardFork(true);
    VMConfig.initAllowTvmTransferTrc10(1);
    VMConfig.initAllowTvmConstantinople(1);
    VMConfig.initAllowTvmSolidity059(1);
    VMConfig.initAllowTvmIstanbul(1);
    VMConfig.initAllowTvmFreeze(1);
  }

  private byte[] deployContract(String contractName, String code) throws Exception {
    return deployContract(owner, contractName, code, 0, 100_000);
  }

  private byte[] deployContract(byte[] deployer, String contractName, String code,
      long consumeUserResourcePercent, long originEnergyLimit) throws Exception {
    Protocol.Transaction trx = TvmTestUtils.generateDeploySmartContractAndGetTransaction(
        contractName, deployer, "[]", code, value, fee, consumeUserResourcePercent,
        null, originEnergyLimit);
    byte[] contractAddr = WalletUtil.generateContractAddress(trx);
    //String contractAddrStr = StringUtil.encode58Check(contractAddr);
    Runtime runtime = TvmTestUtils.processTransactionAndReturnRuntime(trx, rootDeposit, null);
    Assert.assertEquals(SUCCESS, runtime.getResult().getResultCode());
    Assert.assertEquals(value, manager.getAccountStore().get(contractAddr).getBalance());

    return contractAddr;
  }

  private TVMTestResult triggerContract(byte[] callerAddr, byte[] contractAddr, long feeLimit,
                                        contractResult expectedResult, Consumer<byte[]> check, String method,
                                        Object... args) throws Exception {
    String hexInput = AbiUtil.parseMethod(method, Arrays.asList(args));
    TVMTestResult result = TvmTestUtils.triggerContractAndReturnTvmTestResult(
        callerAddr, contractAddr, Hex.decode(hexInput), 0, feeLimit, manager, null);
    Assert.assertEquals(expectedResult, result.getReceipt().getResult());
    if (check != null) {
      check.accept(result.getRuntime().getResult().getHReturn());
    }
    return result;
  }

  private TVMTestResult triggerFreeze(byte[] callerAddr, byte[] contractAddr, byte[] receiverAddr,
                                      long frozenBalance, long res,
                                      contractResult expectedResult, Consumer<byte[]> check) throws Exception {
    return triggerContract(callerAddr, contractAddr, fee, expectedResult, check,
        "freeze(address,uint256,uint256)", StringUtil.encode58Check(receiverAddr), frozenBalance, res);
  }

  private TVMTestResult triggerUnfreeze(byte[] callerAddr, byte[] contractAddr, byte[] receiverAddr, long res,
                                      contractResult expectedResult, Consumer<byte[]> check) throws Exception {
    return triggerContract(callerAddr, contractAddr, fee, expectedResult, check,
        "unfreeze(address,uint256)", StringUtil.encode58Check(receiverAddr), res);
  }

  private void setBalance(byte[] accountAddr, long balance) {
    AccountCapsule accountCapsule = manager.getAccountStore().get(accountAddr);
    accountCapsule.setBalance(balance);
    manager.getAccountStore().put(accountCapsule.createDbKey(), accountCapsule);
  }

  private byte[] getCreate2Addr(byte[] factoryAddr, long salt) throws Exception {
    String methodByAddr = "getCreate2Addr(uint256)";
    String hexInput = AbiUtil.parseMethod(methodByAddr, Collections.singletonList(salt));
    TVMTestResult result = TvmTestUtils.triggerContractAndReturnTvmTestResult(
        owner, factoryAddr, Hex.decode(hexInput), 0, fee, manager, null);
    Assert.assertEquals(SUCCESS, result.getReceipt().getResult());
    return TransactionTrace.convertToTronAddress(
        new DataWord(result.getRuntime().getResult().getHReturn()).getLast20Bytes());
  }

  private byte[] deployCreate2Contract(byte[] factoryAddr, long salt) throws Exception {
    String methodByAddr = "deployCreate2Contract(uint256)";
    String hexInput = AbiUtil.parseMethod(methodByAddr, Collections.singletonList(salt));
    TVMTestResult result = TvmTestUtils.triggerContractAndReturnTvmTestResult(
        owner, factoryAddr, Hex.decode(hexInput), 0, fee, manager, null);
    Assert.assertEquals(SUCCESS, result.getReceipt().getResult());
    return TransactionTrace.convertToTronAddress(
        new DataWord(result.getRuntime().getResult().getHReturn()).getLast20Bytes());
  }

  @Test
  public void testWithCallerEnergyChangedInTx() throws Exception {
    byte[] contractAddr = deployContract("TestFreeze", CONTRACT_CODE);
    long frozenBalance = 10_000_000;
    AccountStore accountStore = manager.getAccountStore();
    AccountCapsule account = new AccountCapsule(ByteString.copyFromUtf8("Yang"),
        ByteString.copyFrom(userA), Protocol.AccountType.Normal, 10_000_000);
    account.setFrozenForEnergy(10_000_000, 1);
    accountStore.put(account.createDbKey(), account);
    manager.getDynamicPropertiesStore().addTotalEnergyWeight(10);

    TVMTestResult result = freezeForOther(userA, contractAddr, userA, frozenBalance, 1);

    System.out.println(result.getReceipt().getEnergyUsageTotal());
    System.out.println(accountStore.get(userA));
    System.out.println(accountStore.get(owner));

    clearDelegatedExpireTime(contractAddr, userA);

    result = unfreezeForOther(userA, contractAddr, userA, 1);

    System.out.println(result.getReceipt().getEnergyUsageTotal());
    System.out.println(accountStore.get(userA));
    System.out.println(accountStore.get(owner));
  }

  @Test
  public void testFreezeAndUnfreeze() throws Exception {
    byte[] contract = deployContract("TestFreeze", CONTRACT_CODE);
    long frozenBalance = 1_000_000;

    // trigger freezeForSelf(uint256,uint256) to get bandwidth
    freezeForSelf(contract, frozenBalance, 0);

    // trigger freezeForSelf(uint256,uint256) to get energy
    freezeForSelf(contract, frozenBalance, 1);

    // tests of freezeForSelf(uint256,uint256) with invalid args
    freezeForSelfWithException(contract, frozenBalance, 2);
    freezeForSelfWithException(contract, 0, 0);
    freezeForSelfWithException(contract, -frozenBalance, 0);
    freezeForSelfWithException(contract, frozenBalance - 1, 1);
    freezeForSelfWithException(contract, value, 0);

    // not time to unfreeze
    unfreezeForSelfWithException(contract, 0);
    unfreezeForSelfWithException(contract, 1);
    // invalid args
    unfreezeForSelfWithException(contract, 2);

    clearExpireTime(contract);

    unfreezeForSelfWithException(contract, 2);
    unfreezeForSelf(contract, 0);
    unfreezeForSelf(contract, 1);
    unfreezeForSelfWithException(contract, 0);
    unfreezeForSelfWithException(contract, 1);

    // trigger freezeForOther(address,uint256,uint256) to delegate bandwidth with creating a new account
    long energyWithCreatingAccountA = freezeForOther(contract, userA, frozenBalance, 0)
        .getReceipt().getEnergyUsageTotal();

    // trigger freezeForOther(address,uint256,uint256) to delegate bandwidth without creating a new account
    long energyWithoutCreatingAccountA = freezeForOther(contract, userA, frozenBalance, 0)
        .getReceipt().getEnergyUsageTotal();
    Assert.assertEquals(energyWithCreatingAccountA - EnergyCost.getInstance().getNEW_ACCT_CALL(),
        energyWithoutCreatingAccountA);

    // trigger freezeForOther(address,uint256,uint256) to delegate energy
    freezeForOther(contract, userA, frozenBalance, 1);

    // trigger freezeForOther(address,uint256,uint256) to delegate energy with creating a new account
    long energyWithCreatingAccountB = freezeForOther(contract, userB, frozenBalance, 1)
        .getReceipt().getEnergyUsageTotal();

    // trigger freezeForOther(address,uint256,uint256) to delegate energy without creating a new account
    long energyWithoutCreatingAccountB = freezeForOther(contract, userB, frozenBalance, 1)
        .getReceipt().getEnergyUsageTotal();
    Assert.assertEquals(energyWithCreatingAccountB - EnergyCost.getInstance().getNEW_ACCT_CALL(),
        energyWithoutCreatingAccountB);

    // trigger freezeForOther(address,uint256,uint256) to delegate bandwidth
    freezeForOther(contract, userB, frozenBalance, 0);

    // tests of freezeForSelf(uint256,uint256) with invalid args
    freezeForOtherWithException(contract, userC, frozenBalance, 2);
    freezeForOtherWithException(contract, userC, 0, 0);
    freezeForOtherWithException(contract, userB, -frozenBalance, 0);
    freezeForOtherWithException(contract, userC, frozenBalance - 1, 1);
    freezeForOtherWithException(contract, userB, value, 0);
    freezeForOtherWithException(contract,
        deployContract("OtherContract", CONTRACT_CODE), frozenBalance, 0);

    unfreezeForOtherWithException(contract, userA, 0);
    unfreezeForOtherWithException(contract, userA, 1);
    unfreezeForOtherWithException(contract, userA, 2);
    unfreezeForOtherWithException(contract, userC, 0);
    unfreezeForOtherWithException(contract, userC, 2);

    clearDelegatedExpireTime(contract, userA);

    unfreezeForOtherWithException(contract, userA, 2);
    unfreezeForOther(contract, userA, 0);
    unfreezeForOther(contract, userA, 1);
    unfreezeForOtherWithException(contract, userA, 0);
    unfreezeForOtherWithException(contract, userA, 1);
  }

  @Test
  public void testFreezeAndUnfreezeToCreate2Contract() throws Exception {
    byte[] factoryAddr = deployContract("FactoryContract", FACTORY_CODE);
    byte[] contractAddr = deployContract("TestFreeze", CONTRACT_CODE);
    long frozenBalance = 1_000_000;
    long salt = 1;
    byte[] predictedAddr = getCreate2Addr(factoryAddr, salt);
    Assert.assertNull(manager.getAccountStore().get(predictedAddr));
    freezeForOther(contractAddr, predictedAddr, frozenBalance, 0);
    Assert.assertNotNull(manager.getAccountStore().get(predictedAddr));
    freezeForOther(contractAddr, predictedAddr, frozenBalance, 1);
    unfreezeForOtherWithException(contractAddr, predictedAddr, 0);
    unfreezeForOtherWithException(contractAddr, predictedAddr, 1);
    clearDelegatedExpireTime(contractAddr, predictedAddr);
    unfreezeForOther(contractAddr, predictedAddr, 0);
    unfreezeForOther(contractAddr, predictedAddr, 1);

    freezeForOther(contractAddr, predictedAddr, frozenBalance, 0);
    freezeForOther(contractAddr, predictedAddr, frozenBalance, 1);
    Assert.assertArrayEquals(predictedAddr, deployCreate2Contract(factoryAddr, salt));
    freezeForOtherWithException(contractAddr, predictedAddr, frozenBalance, 0);
    freezeForOtherWithException(contractAddr, predictedAddr, frozenBalance, 1);
    clearDelegatedExpireTime(contractAddr, predictedAddr);
    unfreezeForOther(contractAddr, predictedAddr, 0);
    unfreezeForOther(contractAddr, predictedAddr, 1);
    unfreezeForOtherWithException(contractAddr, predictedAddr, 0);
    unfreezeForOtherWithException(contractAddr, predictedAddr, 1);

    setBalance(predictedAddr, 100_000_000);
    freezeForSelf(predictedAddr, frozenBalance, 0);
    freezeForSelf(predictedAddr, frozenBalance, 1);
    freezeForOther(predictedAddr, userA, frozenBalance, 0);
    freezeForOther(predictedAddr, userA, frozenBalance, 1);
    clearExpireTime(predictedAddr);
    unfreezeForSelf(predictedAddr, 0);
    unfreezeForSelf(predictedAddr, 1);
    clearDelegatedExpireTime(predictedAddr, userA);
    unfreezeForOther(predictedAddr, userA, 0);
    unfreezeForOther(predictedAddr, userA, 1);
  }

  @Test
  public void testContractSuicideToBlackHoleWithFreeze() throws Exception {
    byte[] contract = deployContract("TestFreeze", CONTRACT_CODE);
    long frozenBalance = 1_000_000;
    freezeForSelf(contract, frozenBalance, 0);
    freezeForSelf(contract, frozenBalance, 1);
    freezeForOther(contract, userA, frozenBalance, 0);
    freezeForOther(contract, userA, frozenBalance, 1);
    freezeForOther(contract, userB, frozenBalance, 0);
    freezeForOther(contract, userB, frozenBalance, 1);
    suicideToBlackHole(contract);
  }

  // TODO: 2021/3/30 msg.sender调用者
  // TODO: 2021/3/30 合约开发者

  @Test
  public void testContractSuicideToNonExistAccountWithFreeze() throws Exception {
    byte[] contract = deployContract("TestFreeze", CONTRACT_CODE);
    long frozenBalance = 1_000_000;
    freezeForSelf(contract, frozenBalance, 0);
    freezeForSelf(contract, frozenBalance, 1);
    freezeForOther(contract, userA, frozenBalance, 0);
    freezeForOther(contract, userA, frozenBalance, 1);
    freezeForOther(contract, userB, frozenBalance, 0);
    freezeForOther(contract, userB, frozenBalance, 1);
    suicideToAccount(contract, userC);
    clearExpireTime(userC);

  }

  @Test
  public void testContractSuicideToExistNormalAccountWithFreeze() throws Exception {
    byte[] contract = deployContract("TestFreeze", CONTRACT_CODE);
    long frozenBalance = 1_000_000;
    freezeForSelf(contract, frozenBalance, 0);
    freezeForSelf(contract, frozenBalance, 1);
    freezeForOther(contract, userA, frozenBalance, 0);
    freezeForOther(contract, userA, frozenBalance, 1);
    freezeForOther(contract, userB, frozenBalance, 0);
    freezeForOther(contract, userB, frozenBalance, 1);
    suicideToAccount(contract, userA);
  }

  @Test
  public void testContractSuicideToExistContractAccountWithFreeze() throws Exception {
    byte[] contract = deployContract("TestFreeze", CONTRACT_CODE);
    byte[] otherContract = deployContract("OtherTestFreeze", CONTRACT_CODE);
    long frozenBalance = 1_000_000;
    freezeForSelf(contract, frozenBalance, 0);
    freezeForSelf(contract, frozenBalance, 1);
    freezeForOther(contract, userA, frozenBalance, 0);
    freezeForOther(contract, userA, frozenBalance, 1);
    freezeForOther(contract, userB, frozenBalance, 0);
    freezeForOther(contract, userB, frozenBalance, 1);
    suicideToAccount(contract, otherContract);
  }

  @Test
  public void testCreate2SuicideToBlackHoleWithFreeze() throws Exception {
    byte[] factory = deployContract("FactoryContract", FACTORY_CODE);
    byte[] contract = deployContract("TestFreeze", CONTRACT_CODE);long frozenBalance = 1_000_000;
    freezeForSelf(contract, frozenBalance, 0);
    freezeForSelf(contract, frozenBalance, 1);
    long salt = 1;
    byte[] predictedAddr = getCreate2Addr(factory, salt);
    freezeForOther(contract, predictedAddr, frozenBalance, 0);
    freezeForOther(contract, predictedAddr, frozenBalance, 1);
    freezeForOther(contract, userA, frozenBalance, 0);
    freezeForOther(contract, userA, frozenBalance, 1);
    Assert.assertArrayEquals(predictedAddr, deployCreate2Contract(factory, salt));
    setBalance(predictedAddr, 100_000_000);
    freezeForSelf(predictedAddr, frozenBalance, 0);
    freezeForSelf(predictedAddr, frozenBalance, 1);
    freezeForOther(predictedAddr, userA, frozenBalance, 1);
    freezeForOther(predictedAddr, userA, frozenBalance, 1);
    suicideToBlackHole(predictedAddr);
    clearDelegatedExpireTime(contract, predictedAddr);
    unfreezeForOther(contract, predictedAddr, 0);
    unfreezeForOther(contract, predictedAddr, 1);
  }

  @Test
  public void testCreate2SuicideToAccountWithFreeze() throws Exception {
    byte[] factory = deployContract("FactoryContract", FACTORY_CODE);
    byte[] contract = deployContract("TestFreeze", CONTRACT_CODE);
    long frozenBalance = 1_000_000;
    freezeForSelf(contract, frozenBalance, 0);
    freezeForSelf(contract, frozenBalance, 1);
    long salt = 1;
    byte[] predictedAddr = getCreate2Addr(factory, salt);
    freezeForOther(contract, predictedAddr, frozenBalance, 0);
    freezeForOther(contract, predictedAddr, frozenBalance, 1);
    freezeForOther(contract, userA, frozenBalance, 0);
    freezeForOther(contract, userA, frozenBalance, 1);
    Assert.assertArrayEquals(predictedAddr, deployCreate2Contract(factory, salt));
    setBalance(predictedAddr, 100_000_000);
    freezeForSelf(predictedAddr, frozenBalance, 0);
    freezeForSelf(predictedAddr, frozenBalance, 1);
    freezeForOther(predictedAddr, userA, frozenBalance, 1);
    freezeForOther(predictedAddr, userA, frozenBalance, 1);
    suicideToAccount(predictedAddr, userA);
    clearDelegatedExpireTime(contract, predictedAddr);
    unfreezeForOtherWithException(contract, predictedAddr, 0);
    unfreezeForOtherWithException(contract, predictedAddr, 1);
    clearDelegatedExpireTime(contract, userA);
    unfreezeForOther(contract, userA, 0);
    unfreezeForOther(contract, userA, 1);
  }

  @Test
  public void testSuicideToMsgSender() throws Exception {
    byte[] contract = deployContract("TestFreeze", CONTRACT_CODE);
    long frozenBalance = 1_000_000;
    freezeForSelf(contract, frozenBalance, 0);
    freezeForSelf(contract, frozenBalance, 1);
    freezeForOther(contract, userA, frozenBalance, 0);
    freezeForOther(contract, userA, frozenBalance, 1);
    setBalance(userA,  100_000_000);
    AccountCapsule caller = manager.getAccountStore().get(userA);
    AccountCapsule deployer = manager.getAccountStore().get(owner);
    TVMTestResult result = suicideToAccount(userA, contract, owner);
    checkReceipt(result, caller, deployer);
  }

  private void clearExpireTime(byte[] owner) {
    AccountCapsule accountCapsule = manager.getAccountStore().get(owner);
    long now = manager.getDynamicPropertiesStore().getLatestBlockHeaderTimestamp();
    accountCapsule.setFrozenForBandwidth(accountCapsule.getFrozenBalance(), now);
    accountCapsule.setFrozenForEnergy(accountCapsule.getEnergyFrozenBalance(), now);
    manager.getAccountStore().put(accountCapsule.createDbKey(), accountCapsule);
  }

  private void clearDelegatedExpireTime(byte[] owner, byte[] receiver) {
    byte[] key = DelegatedResourceCapsule.createDbKey(owner, receiver);
    DelegatedResourceCapsule delegatedResource = manager.getDelegatedResourceStore().get(key);
    long now = manager.getDynamicPropertiesStore().getLatestBlockHeaderTimestamp();
    delegatedResource.setExpireTimeForBandwidth(now);
    delegatedResource.setExpireTimeForEnergy(now);
    manager.getDelegatedResourceStore().put(key, delegatedResource);
  }

  private TVMTestResult freezeForSelf(byte[] contractAddr, long frozenBalance, long res) throws Exception {
    return freezeForSelf(owner, contractAddr, frozenBalance, res);
  }

  private TVMTestResult freezeForSelf(byte[] callerAddr, byte[] contractAddr,
                                      long frozenBalance, long res) throws Exception {
    DynamicPropertiesStore dynamicStore = manager.getDynamicPropertiesStore();
    long oldTotalNetWeight = dynamicStore.getTotalNetWeight();
    long oldTotalEnergyWeight = dynamicStore.getTotalEnergyWeight();

    AccountStore accountStore = manager.getAccountStore();
    AccountCapsule oldOwner = accountStore.get(contractAddr);

    TVMTestResult result = triggerFreeze(callerAddr, contractAddr, contractAddr, frozenBalance, res, SUCCESS,
        returnValue -> Assert.assertEquals(dynamicStore.getMinFrozenTime() * FROZEN_PERIOD,
            new DataWord(returnValue).longValue() * 1000));

    AccountCapsule newOwner = accountStore.get(contractAddr);
    Assert.assertEquals(oldOwner.getBalance() - frozenBalance, newOwner.getBalance());
    Assert.assertEquals(oldOwner.getOldVotePower() + frozenBalance, newOwner.getOldVotePower());
    newOwner.setBalance(oldOwner.getBalance());
    newOwner.setOldVotePower(oldOwner.getOldVotePower());
    if (res == 0) {
      Assert.assertEquals(1, newOwner.getFrozenCount());
      Assert.assertEquals(oldOwner.getFrozenBalance() + frozenBalance, newOwner.getFrozenBalance());
      Assert.assertEquals(oldTotalNetWeight + frozenBalance / TRX_PRECISION,
          dynamicStore.getTotalNetWeight());
      Assert.assertEquals(oldTotalEnergyWeight, dynamicStore.getTotalEnergyWeight());
      oldOwner.setFrozenForBandwidth(0, 0);
      newOwner.setFrozenForBandwidth(0, 0);
    } else {
      Assert.assertEquals(oldOwner.getEnergyFrozenBalance() + frozenBalance, newOwner.getEnergyFrozenBalance());
      Assert.assertEquals(oldTotalNetWeight, dynamicStore.getTotalNetWeight());
      Assert.assertEquals(oldTotalEnergyWeight + frozenBalance / TRX_PRECISION,
          dynamicStore.getTotalEnergyWeight());
      oldOwner.setFrozenForEnergy(0, 0);
      newOwner.setFrozenForEnergy(0, 0);
    }
    Assert.assertArrayEquals(oldOwner.getData(), newOwner.getData());

    return result;
  }

  private void freezeForSelfWithException(byte[] contractAddr, long frozenBalance, long res) throws Exception {
    freezeOrUnfreezeForSelfWithException(contractAddr, OpType.FREEZE, frozenBalance, res);
  }

  private TVMTestResult unfreezeForSelf(byte[] contractAddr, long res) throws Exception {
    return unfreezeForSelf(owner, contractAddr, res);
  }

  private TVMTestResult unfreezeForSelf(byte[] callerAddr, byte[] contractAddr, long res) throws Exception {
    DynamicPropertiesStore dynamicStore = manager.getDynamicPropertiesStore();
    long oldTotalNetWeight = dynamicStore.getTotalNetWeight();
    long oldTotalEnergyWeight = dynamicStore.getTotalEnergyWeight();

    AccountStore accountStore = manager.getAccountStore();
    AccountCapsule oldOwner = accountStore.get(contractAddr);
    long frozenBalance = res == 0 ? oldOwner.getFrozenBalance() : oldOwner.getEnergyFrozenBalance();
    Assert.assertTrue(frozenBalance > 0);

    TVMTestResult result = triggerUnfreeze(callerAddr, contractAddr, contractAddr, res, SUCCESS, returnValue ->
        Assert.assertEquals("0000000000000000000000000000000000000000000000000000000000000001",
            Hex.toHexString(returnValue)));

    AccountCapsule newOwner = accountStore.get(contractAddr);
    Assert.assertEquals(oldOwner.getBalance() + frozenBalance, newOwner.getBalance());
    Assert.assertEquals(oldOwner.getOldVotePower() - frozenBalance, newOwner.getOldVotePower());
    oldOwner.setBalance(newOwner.getBalance());
    oldOwner.setOldVotePower(newOwner.getOldVotePower());
    if (res == 0) {
      Assert.assertEquals(0, newOwner.getFrozenCount());
      Assert.assertEquals(0, newOwner.getFrozenBalance());
      Assert.assertEquals(oldTotalNetWeight - frozenBalance / TRX_PRECISION,
          dynamicStore.getTotalNetWeight());
      Assert.assertEquals(oldTotalEnergyWeight, dynamicStore.getTotalEnergyWeight());
      oldOwner.setFrozenForBandwidth(0, 0);
      newOwner.setFrozenForBandwidth(0, 0);
    } else {
      Assert.assertEquals(0, newOwner.getEnergyFrozenBalance());
      Assert.assertEquals(oldTotalNetWeight, dynamicStore.getTotalNetWeight());
      Assert.assertEquals(oldTotalEnergyWeight - frozenBalance / TRX_PRECISION,
          dynamicStore.getTotalEnergyWeight());
      oldOwner.setFrozenForEnergy(0, 0);
      newOwner.setFrozenForEnergy(0, 0);
    }
    Assert.assertArrayEquals(oldOwner.getData(), newOwner.getData());

    return result;
  }

  private void unfreezeForSelfWithException(byte[] contractAddr, long res) throws Exception {
    freezeOrUnfreezeForSelfWithException(contractAddr, OpType.UNFREEZE, res);
  }

  private void freezeOrUnfreezeForSelfWithException(byte[] contractAddr,
                                                    OpType opType, Object... args) throws Exception {
    DynamicPropertiesStore dynamicStore = manager.getDynamicPropertiesStore();
    long oldTotalNetWeight = dynamicStore.getTotalNetWeight();
    long oldTotalEnergyWeight = dynamicStore.getTotalEnergyWeight();

    AccountStore accountStore = manager.getAccountStore();
    AccountCapsule oldOwner = accountStore.get(contractAddr);

    String methodByAddr = opType == OpType.FREEZE ? "freezeForSelf(uint256,uint256)" : "unfreezeForSelf(uint256)";
    String hexInput = AbiUtil.parseMethod(methodByAddr, Arrays.asList(args));
    TVMTestResult result = TvmTestUtils.triggerContractAndReturnTvmTestResult(
        owner, contractAddr, Hex.decode(hexInput), 0, fee, manager, null);
    Assert.assertEquals(REVERT, result.getReceipt().getResult());

    AccountCapsule newOwner = accountStore.get(contractAddr);
    Assert.assertArrayEquals(oldOwner.getData(), newOwner.getData());

    Assert.assertEquals(oldTotalNetWeight, dynamicStore.getTotalNetWeight());
    Assert.assertEquals(oldTotalEnergyWeight, dynamicStore.getTotalEnergyWeight());
  }

  private TVMTestResult freezeForOther(
      byte[] contractAddr, byte[] receiverAddr, long frozenBalance, long res) throws Exception {
    return freezeForOther(owner, contractAddr, receiverAddr, frozenBalance, res);
  }

  private TVMTestResult freezeForOther(byte[] callerAddr, byte[] contractAddr,
                                       byte[] receiverAddr, long frozenBalance, long res) throws Exception {
    DynamicPropertiesStore dynamicStore = manager.getDynamicPropertiesStore();
    long oldTotalNetWeight = dynamicStore.getTotalNetWeight();
    long oldTotalEnergyWeight = dynamicStore.getTotalEnergyWeight();

    AccountStore accountStore = manager.getAccountStore();
    AccountCapsule oldOwner = accountStore.get(contractAddr);
    Assert.assertNotNull(receiverAddr);
    AccountCapsule oldReceiver = accountStore.get(receiverAddr);
    long acquiredBalance = 0;
    if (oldReceiver != null) {
      acquiredBalance = res == 0 ? oldReceiver.getAcquiredDelegatedFrozenBalanceForBandwidth() :
          oldReceiver.getAcquiredDelegatedFrozenBalanceForEnergy();
    }

    DelegatedResourceStore delegatedResourceStore = manager.getDelegatedResourceStore();
    DelegatedResourceCapsule oldDelegatedResource = delegatedResourceStore.get(
        DelegatedResourceCapsule.createDbKey(contractAddr, receiverAddr));
    if (oldDelegatedResource == null) {
      oldDelegatedResource = new DelegatedResourceCapsule(
          ByteString.copyFrom(contractAddr),
          ByteString.copyFrom(receiverAddr));
    }

    DelegatedResourceAccountIndexStore indexStore = manager.getDelegatedResourceAccountIndexStore();
    DelegatedResourceAccountIndexCapsule oldOwnerIndex = indexStore.get(contractAddr);
    if (oldOwnerIndex == null) {
      oldOwnerIndex = new DelegatedResourceAccountIndexCapsule(ByteString.copyFrom(contractAddr));
    }
    DelegatedResourceAccountIndexCapsule oldReceiverIndex = indexStore.get(receiverAddr);
    if (oldReceiverIndex == null) {
      oldReceiverIndex = new DelegatedResourceAccountIndexCapsule(ByteString.copyFrom(receiverAddr));
    }

    TVMTestResult result = triggerFreeze(callerAddr, contractAddr, receiverAddr, frozenBalance, res, SUCCESS,
        returnValue -> Assert.assertEquals(dynamicStore.getMinFrozenTime() * FROZEN_PERIOD,
            new DataWord(returnValue).longValue() * 1000));

    AccountCapsule newOwner = accountStore.get(contractAddr);
    Assert.assertEquals(oldOwner.getBalance() - frozenBalance, newOwner.getBalance());
    Assert.assertEquals(oldOwner.getOldVotePower() + frozenBalance, newOwner.getOldVotePower());
    newOwner.setBalance(oldOwner.getBalance());
    newOwner.setOldVotePower(oldOwner.getOldVotePower());
    if (res == 0) {
      Assert.assertEquals(oldOwner.getDelegatedFrozenBalanceForBandwidth() + frozenBalance,
          newOwner.getDelegatedFrozenBalanceForBandwidth());
      oldOwner.setDelegatedFrozenBalanceForBandwidth(0);
      newOwner.setDelegatedFrozenBalanceForBandwidth(0);
    } else {
      Assert.assertEquals(oldOwner.getDelegatedFrozenBalanceForEnergy() + frozenBalance,
          newOwner.getDelegatedFrozenBalanceForEnergy());
      oldOwner.setDelegatedFrozenBalanceForEnergy(0);
      newOwner.setDelegatedFrozenBalanceForEnergy(0);
    }
    Assert.assertArrayEquals(oldOwner.getData(), newOwner.getData());

    AccountCapsule newReceiver = accountStore.get(receiverAddr);
    Assert.assertNotNull(newReceiver);
    Assert.assertEquals(acquiredBalance + frozenBalance,
        res == 0 ? newReceiver.getAcquiredDelegatedFrozenBalanceForBandwidth() :
            newReceiver.getAcquiredDelegatedFrozenBalanceForEnergy());
    if (oldReceiver != null) {
      oldReceiver.setEnergyUsage(0);
      newReceiver.setEnergyUsage(0);
      if (res == 0) {
        oldReceiver.setAcquiredDelegatedFrozenBalanceForBandwidth(0);
        newReceiver.setAcquiredDelegatedFrozenBalanceForBandwidth(0);
      } else {
        oldReceiver.setAcquiredDelegatedFrozenBalanceForEnergy(0);
        newReceiver.setAcquiredDelegatedFrozenBalanceForEnergy(0);
      }
      Assert.assertArrayEquals(oldReceiver.getData(), newReceiver.getData());
    }

    DelegatedResourceCapsule newDelegatedResource = delegatedResourceStore.get(
        DelegatedResourceCapsule.createDbKey(contractAddr, receiverAddr));
    Assert.assertNotNull(newDelegatedResource);
    if (res == 0) {
      Assert.assertEquals(frozenBalance + oldDelegatedResource.getFrozenBalanceForBandwidth(),
          newDelegatedResource.getFrozenBalanceForBandwidth());
      Assert.assertEquals(oldDelegatedResource.getFrozenBalanceForEnergy(),
          newDelegatedResource.getFrozenBalanceForEnergy());
    } else {
      Assert.assertEquals(oldDelegatedResource.getFrozenBalanceForBandwidth(),
          newDelegatedResource.getFrozenBalanceForBandwidth());
      Assert.assertEquals(frozenBalance + oldDelegatedResource.getFrozenBalanceForEnergy(),
          newDelegatedResource.getFrozenBalanceForEnergy());
    }

    DelegatedResourceAccountIndexCapsule newOwnerIndex = indexStore.get(contractAddr);
    Assert.assertNotNull(newOwnerIndex);
    Assert.assertTrue(newOwnerIndex.getToAccountsList().contains(ByteString.copyFrom(receiverAddr)));
    oldOwnerIndex.removeToAccount(ByteString.copyFrom(receiverAddr));
    newOwnerIndex.removeToAccount(ByteString.copyFrom(receiverAddr));
    Assert.assertArrayEquals(oldOwnerIndex.getData(), newOwnerIndex.getData());

    DelegatedResourceAccountIndexCapsule newReceiverIndex = indexStore.get(receiverAddr);
    Assert.assertNotNull(newReceiverIndex);
    Assert.assertTrue(newReceiverIndex.getFromAccountsList().contains(ByteString.copyFrom(contractAddr)));
    oldReceiverIndex.removeFromAccount(ByteString.copyFrom(contractAddr));
    newReceiverIndex.removeFromAccount(ByteString.copyFrom(contractAddr));
    Assert.assertArrayEquals(oldReceiverIndex.getData(), newReceiverIndex.getData());

    if (res == 0) {
      Assert.assertEquals(oldTotalNetWeight + frozenBalance / TRX_PRECISION,
          dynamicStore.getTotalNetWeight());
      Assert.assertEquals(oldTotalEnergyWeight, dynamicStore.getTotalEnergyWeight());
    } else {
      Assert.assertEquals(oldTotalNetWeight, dynamicStore.getTotalNetWeight());
      Assert.assertEquals(oldTotalEnergyWeight + frozenBalance / TRX_PRECISION,
          dynamicStore.getTotalEnergyWeight());
    }

    return result;
  }

  private void freezeForOtherWithException(
      byte[] contractAddr, byte[] receiverAddr, long frozenBalance, long res) throws Exception {
    freezeOrUnfreezeForOtherWithException(contractAddr, OpType.FREEZE, receiverAddr, frozenBalance, res);
  }

  private TVMTestResult unfreezeForOther(byte[] contractAddr, byte[] receiverAddr, long res) throws Exception {
    return unfreezeForOther(owner, contractAddr, receiverAddr, res);
  }

  private TVMTestResult unfreezeForOther(byte[] callerAddr, byte[] contractAddr,
                                         byte[] receiverAddr, long res) throws Exception {
    DynamicPropertiesStore dynamicStore = manager.getDynamicPropertiesStore();
    long oldTotalNetWeight = dynamicStore.getTotalNetWeight();
    long oldTotalEnergyWeight = dynamicStore.getTotalEnergyWeight();

    AccountStore accountStore = manager.getAccountStore();
    AccountCapsule oldOwner = accountStore.get(contractAddr);
    long delegatedBalance = res == 0 ? oldOwner.getDelegatedFrozenBalanceForBandwidth() :
        oldOwner.getDelegatedFrozenBalanceForEnergy();

    AccountCapsule oldReceiver = accountStore.get(receiverAddr);
    long acquiredBalance = 0;
    if (oldReceiver != null) {
      acquiredBalance = res == 0 ? oldReceiver.getAcquiredDelegatedFrozenBalanceForBandwidth() :
          oldReceiver.getAcquiredDelegatedFrozenBalanceForEnergy();
    }

    DelegatedResourceStore delegatedResourceStore = manager.getDelegatedResourceStore();
    DelegatedResourceCapsule oldDelegatedResource = delegatedResourceStore.get(
        DelegatedResourceCapsule.createDbKey(contractAddr, receiverAddr));
    Assert.assertNotNull(oldDelegatedResource);
    long delegatedFrozenBalance = res == 0 ? oldDelegatedResource.getFrozenBalanceForBandwidth() :
        oldDelegatedResource.getFrozenBalanceForEnergy();
    Assert.assertTrue(delegatedFrozenBalance > 0);
    Assert.assertTrue(delegatedFrozenBalance <= delegatedBalance);

    DelegatedResourceAccountIndexStore indexStore = manager.getDelegatedResourceAccountIndexStore();
    DelegatedResourceAccountIndexCapsule oldOwnerIndex = indexStore.get(contractAddr);
    Assert.assertTrue(oldOwnerIndex.getToAccountsList().contains(ByteString.copyFrom(receiverAddr)));
    DelegatedResourceAccountIndexCapsule oldReceiverIndex = indexStore.get(receiverAddr);
    Assert.assertTrue(oldReceiverIndex.getFromAccountsList().contains(ByteString.copyFrom(contractAddr)));

    TVMTestResult result = triggerUnfreeze(callerAddr, contractAddr, receiverAddr, res, SUCCESS, returnValue ->
        Assert.assertEquals("0000000000000000000000000000000000000000000000000000000000000001",
            Hex.toHexString(returnValue)));

    // check owner account
    AccountCapsule newOwner = accountStore.get(contractAddr);
    Assert.assertEquals(oldOwner.getBalance() + delegatedFrozenBalance, newOwner.getBalance());
    Assert.assertEquals(oldOwner.getOldVotePower() - delegatedFrozenBalance, newOwner.getOldVotePower());
    newOwner.setBalance(oldOwner.getBalance());
    newOwner.setOldVotePower(oldOwner.getOldVotePower());
    if (res == 0) {
      Assert.assertEquals(oldOwner.getDelegatedFrozenBalanceForBandwidth() - delegatedFrozenBalance,
          newOwner.getDelegatedFrozenBalanceForBandwidth());
      newOwner.setDelegatedFrozenBalanceForBandwidth(oldOwner.getDelegatedFrozenBalanceForBandwidth());
    } else {
      Assert.assertEquals(oldOwner.getDelegatedFrozenBalanceForEnergy() - delegatedFrozenBalance,
          newOwner.getDelegatedFrozenBalanceForEnergy());
      newOwner.setDelegatedFrozenBalanceForEnergy(oldOwner.getDelegatedFrozenBalanceForEnergy());
    }
    Assert.assertArrayEquals(oldOwner.getData(), newOwner.getData());

    // check receiver account
    AccountCapsule newReceiver = accountStore.get(receiverAddr);
    if (oldReceiver != null) {
      Assert.assertNotNull(newReceiver);
      long newAcquiredBalance = res == 0 ? newReceiver.getAcquiredDelegatedFrozenBalanceForBandwidth() :
          newReceiver.getAcquiredDelegatedFrozenBalanceForEnergy();
      Assert.assertTrue(newAcquiredBalance == 0 || acquiredBalance - newAcquiredBalance == delegatedFrozenBalance);
      newReceiver.setBalance(oldReceiver.getBalance());
      newReceiver.setNetUsage(oldReceiver.getNetUsage());
      newReceiver.setEnergyUsage(oldReceiver.getEnergyUsage());
      if (res == 0) {
        oldReceiver.setAcquiredDelegatedFrozenBalanceForBandwidth(0);
        newReceiver.setAcquiredDelegatedFrozenBalanceForBandwidth(0);
      } else {
        oldReceiver.setAcquiredDelegatedFrozenBalanceForEnergy(0);
        newReceiver.setAcquiredDelegatedFrozenBalanceForEnergy(0);
      }
      Assert.assertArrayEquals(oldReceiver.getData(), newReceiver.getData());
    } else {
      Assert.assertNull(newReceiver);
    }

    // check delegated resource store
    DelegatedResourceCapsule newDelegatedResource = delegatedResourceStore.get(
        DelegatedResourceCapsule.createDbKey(contractAddr, receiverAddr));
    Assert.assertNotNull(newDelegatedResource);
    if (res == 0) {
      Assert.assertEquals(0, newDelegatedResource.getFrozenBalanceForBandwidth());
      Assert.assertEquals(oldDelegatedResource.getFrozenBalanceForEnergy(),
          newDelegatedResource.getFrozenBalanceForEnergy());
    } else {
      Assert.assertEquals(oldDelegatedResource.getFrozenBalanceForBandwidth(),
          newDelegatedResource.getFrozenBalanceForBandwidth());
      Assert.assertEquals(0, newDelegatedResource.getFrozenBalanceForEnergy());
    }

    // check account index store
    DelegatedResourceAccountIndexCapsule newOwnerIndex = indexStore.get(contractAddr);
    Assert.assertNotNull(newOwnerIndex);
    if (newDelegatedResource.getFrozenBalanceForBandwidth() == 0 &&
        newDelegatedResource.getFrozenBalanceForEnergy() == 0) {
      Assert.assertFalse(newOwnerIndex.getToAccountsList().contains(ByteString.copyFrom(receiverAddr)));
      oldOwnerIndex.removeToAccount(ByteString.copyFrom(receiverAddr));
    }
    Assert.assertArrayEquals(oldOwnerIndex.getData(), newOwnerIndex.getData());

    DelegatedResourceAccountIndexCapsule newReceiverIndex = indexStore.get(receiverAddr);
    Assert.assertNotNull(newReceiverIndex);
    if (newDelegatedResource.getFrozenBalanceForBandwidth() == 0 &&
        newDelegatedResource.getFrozenBalanceForEnergy() == 0) {
      Assert.assertFalse(newReceiverIndex.getFromAccountsList().contains(ByteString.copyFrom(contractAddr)));
      oldReceiverIndex.removeFromAccount(ByteString.copyFrom(contractAddr));
    }
    Assert.assertArrayEquals(oldReceiverIndex.getData(), newReceiverIndex.getData());

    // check total weight
    if (res == 0) {
      Assert.assertEquals(oldTotalNetWeight - delegatedFrozenBalance / TRX_PRECISION,
          dynamicStore.getTotalNetWeight());
      Assert.assertEquals(oldTotalEnergyWeight, dynamicStore.getTotalEnergyWeight());
    } else {
      Assert.assertEquals(oldTotalNetWeight, dynamicStore.getTotalNetWeight());
      Assert.assertEquals(oldTotalEnergyWeight - delegatedFrozenBalance / TRX_PRECISION,
          dynamicStore.getTotalEnergyWeight());
    }

    return result;
  }

  private void unfreezeForOtherWithException(byte[] contractAddr, byte[] receiver, long res) throws Exception {
    freezeOrUnfreezeForOtherWithException(contractAddr, OpType.UNFREEZE, receiver, 0, res);
  }

  private void freezeOrUnfreezeForOtherWithException(
      byte[] contractAddr, OpType opType, byte[] receiverAddr, long frozenBalance, long res) throws Exception {
    DynamicPropertiesStore dynamicStore = manager.getDynamicPropertiesStore();
    long oldTotalNetWeight = dynamicStore.getTotalNetWeight();
    long oldTotalEnergyWeight = dynamicStore.getTotalEnergyWeight();

    AccountStore accountStore = manager.getAccountStore();
    AccountCapsule oldOwner = accountStore.get(contractAddr);

    String receiver = StringUtil.encode58Check(receiverAddr);
    Assert.assertNotNull(receiverAddr);
    AccountCapsule oldReceiver = accountStore.get(receiverAddr);

    DelegatedResourceStore delegatedResourceStore = manager.getDelegatedResourceStore();
    DelegatedResourceCapsule oldDelegatedResource = delegatedResourceStore.get(
        DelegatedResourceCapsule.createDbKey(contractAddr, receiverAddr));

    DelegatedResourceAccountIndexStore indexStore = manager.getDelegatedResourceAccountIndexStore();
    DelegatedResourceAccountIndexCapsule oldOwnerIndex = indexStore.get(contractAddr);
    DelegatedResourceAccountIndexCapsule oldReceiverIndex = indexStore.get(receiverAddr);

    String methodByAddr = opType == OpType.FREEZE ? "freezeForOther(address,uint256,uint256)"
        : "unfreezeForOther(address,uint256)";
    String hexInput = AbiUtil.parseMethod(methodByAddr,
        opType == OpType.FREEZE ? Arrays.asList(receiver, frozenBalance, res) :
            Arrays.asList(receiver, res));
    TVMTestResult result = TvmTestUtils.triggerContractAndReturnTvmTestResult(
        owner, contractAddr, Hex.decode(hexInput), 0, fee, manager, null);
    Assert.assertEquals(REVERT, result.getReceipt().getResult());

    AccountCapsule newOwner = accountStore.get(contractAddr);
    Assert.assertArrayEquals(oldOwner.getData(), newOwner.getData());

    AccountCapsule newReceiver = accountStore.get(receiverAddr);
    Assert.assertTrue(oldReceiver == newReceiver ||
        Arrays.equals(oldReceiver.getData(), newReceiver.getData()));

    DelegatedResourceCapsule newDelegatedResource = delegatedResourceStore.get(
        DelegatedResourceCapsule.createDbKey(contractAddr, receiverAddr));
    Assert.assertTrue(oldDelegatedResource == newDelegatedResource ||
        Arrays.equals(oldDelegatedResource.getData(), newDelegatedResource.getData()));

    DelegatedResourceAccountIndexCapsule newOwnerIndex = indexStore.get(contractAddr);
    Assert.assertTrue(oldOwnerIndex == newOwnerIndex ||
        Arrays.equals(oldOwnerIndex.getData(), newOwnerIndex.getData()));
    DelegatedResourceAccountIndexCapsule newReceiverIndex = indexStore.get(receiverAddr);
    Assert.assertTrue(oldReceiverIndex == newReceiverIndex ||
        Arrays.equals(oldReceiverIndex.getData(), newReceiverIndex.getData()));

    Assert.assertEquals(oldTotalNetWeight, dynamicStore.getTotalNetWeight());
    Assert.assertEquals(oldTotalEnergyWeight, dynamicStore.getTotalEnergyWeight());
  }

  private void suicideToBlackHole(byte[] contractAddr) throws Exception {
    DynamicPropertiesStore dynamicStore = manager.getDynamicPropertiesStore();
    long oldTotalNetWeight = dynamicStore.getTotalNetWeight();
    long oldTotalEnergyWeight = dynamicStore.getTotalEnergyWeight();

    AccountStore accountStore = manager.getAccountStore();
    AccountCapsule contract = accountStore.get(contractAddr);
    AccountCapsule oldBlackHole = accountStore.get(accountStore.getBlackholeAddress());

    DelegatedResourceAccountIndexStore indexStore = manager.getDelegatedResourceAccountIndexStore();
    DelegatedResourceAccountIndexCapsule index = indexStore.get(contractAddr);

    String methodByAddr = "destroy(address)";
    String hexInput = AbiUtil.parseMethod(methodByAddr,
        Collections.singletonList(StringUtil.encode58Check(contractAddr)));
    TVMTestResult result = TvmTestUtils.triggerContractAndReturnTvmTestResult(
        owner, contractAddr, Hex.decode(hexInput), 0, fee, manager, null);
    Assert.assertEquals(SUCCESS, result.getReceipt().getResult());

    Assert.assertNull(accountStore.get(contractAddr));
    AccountCapsule newBlackHole = accountStore.get(accountStore.getBlackholeAddress());
    Assert.assertEquals(contract.getBalance() + contract.getTronPower(),
        newBlackHole.getBalance() - oldBlackHole.getBalance() - 25500);

    DelegatedResourceStore delegatedResourceStore = manager.getDelegatedResourceStore();
    for (ByteString from : index.getFromAccountsList()) {
      Assert.assertNotNull(delegatedResourceStore.get(
          DelegatedResourceCapsule.createDbKey(from.toByteArray(), contractAddr)));
    }
    for (ByteString to : index.getToAccountsList()) {
      DelegatedResourceCapsule resourceCapsule = delegatedResourceStore.get(
          DelegatedResourceCapsule.createDbKey(contractAddr, to.toByteArray()));
      Assert.assertTrue(resourceCapsule == null ||
          (resourceCapsule.getFrozenBalanceForBandwidth() == 0 && resourceCapsule.getFrozenBalanceForEnergy() == 0));
    }

    long newTotalNetWeight = dynamicStore.getTotalNetWeight();
    long newTotalEnergyWeight = dynamicStore.getTotalEnergyWeight();
    Assert.assertEquals(contract.getFrozenBalance() + contract.getDelegatedFrozenBalanceForBandwidth(),
        (oldTotalNetWeight - newTotalNetWeight) * TRX_PRECISION);
    Assert.assertEquals(contract.getEnergyFrozenBalance() + contract.getDelegatedFrozenBalanceForEnergy(),
        (oldTotalEnergyWeight - newTotalEnergyWeight) * TRX_PRECISION);
  }

  private static class AccountState {

    byte[] accountAddr;
    AccountCapsule account;
    DelegatedResourceAccountIndexCapsule index;
    Map<ByteString, DelegatedResourceCapsule> acquiredRes = new HashMap<>();
    Map<ByteString, DelegatedResourceCapsule> delegatedRes = new HashMap<>();

    AccountState(Manager manager, byte[] accountAddr) {
      this.accountAddr = accountAddr;
      this.account = manager.getAccountStore().get(accountAddr);
      this.index = manager.getDelegatedResourceAccountIndexStore().get(accountAddr);
      if (this.index == null) {
        this.index = new DelegatedResourceAccountIndexCapsule(ByteString.copyFrom(accountAddr));
      }
      DelegatedResourceStore store = manager.getDelegatedResourceStore();
      for (ByteString sender : index.getFromAccountsList()) {
        acquiredRes.put(sender, store.get(
            DelegatedResourceCapsule.createDbKey(sender.toByteArray(), accountAddr)));
      }
      for (ByteString receiver: index.getToAccountsList()) {
        delegatedRes.put(receiver, store.get(
            DelegatedResourceCapsule.createDbKey(accountAddr, receiver.toByteArray())));
      }
    }

    long getTotalBalance() {
      return account == null ? 0 : account.getBalance() + account.getTronPower();
    }

    DelegatedResourceCapsule getAcquiredRes(ByteString from) {
      return acquiredRes.containsKey(from) ? acquiredRes.get(from) :
          new DelegatedResourceCapsule(from, ByteString.copyFrom(accountAddr));
    }

    DelegatedResourceCapsule getDelegatedRes(ByteString to) {
      return delegatedRes.containsKey(to) ? delegatedRes.get(to) :
          new DelegatedResourceCapsule(ByteString.copyFrom(accountAddr), to);
    }
  }

  private TVMTestResult suicideToAccount(byte[] contractAddr, byte[] inheritorAddr) throws Exception {
    return suicideToAccount(owner, contractAddr, inheritorAddr);
  }

  private TVMTestResult suicideToAccount(byte[] callerAddr, byte[] contractAddr,
                                         byte[] inheritorAddr) throws Exception {
    DynamicPropertiesStore dynamicStore = manager.getDynamicPropertiesStore();
    long oldTotalNetWeight = dynamicStore.getTotalNetWeight();
    long oldTotalEnergyWeight = dynamicStore.getTotalEnergyWeight();

    AccountState contractState = new AccountState(manager, contractAddr);
    AccountState oldInheritorState = new AccountState(manager, inheritorAddr);

    String methodByAddr = "destroy(address)";
    String hexInput = AbiUtil.parseMethod(methodByAddr,
        Collections.singletonList(StringUtil.encode58Check(inheritorAddr)));
    TVMTestResult result = TvmTestUtils.triggerContractAndReturnTvmTestResult(
        callerAddr, contractAddr, Hex.decode(hexInput), 0, fee, manager, null);
    Assert.assertEquals(SUCCESS, result.getReceipt().getResult());

    AccountStore accountStore = manager.getAccountStore();
    Assert.assertNull(accountStore.get(contractAddr));
    AccountState newInheritorState = new AccountState(manager, inheritorAddr);
    long txFee = 0;
    if (FastByteComparisons.isEqual(callerAddr, inheritorAddr)) {
      txFee = result.getReceipt().getEnergyFee();
    }
    Assert.assertEquals(contractState.getTotalBalance() - txFee,
        newInheritorState.getTotalBalance() - oldInheritorState.getTotalBalance());

    DelegatedResourceStore resourceStore = manager.getDelegatedResourceStore();
    for (ByteString sender : contractState.index.getFromAccountsList()) {
      DelegatedResourceCapsule senderToContractRes = resourceStore.get(
          DelegatedResourceCapsule.createDbKey(sender.toByteArray(), contractAddr));
      Assert.assertNotNull(senderToContractRes);
      Assert.assertEquals(0, senderToContractRes.getFrozenBalanceForBandwidth());
      Assert.assertEquals(0, senderToContractRes.getFrozenBalanceForEnergy());
      Assert.assertEquals(0, senderToContractRes.getExpireTimeForBandwidth());
      Assert.assertEquals(0, senderToContractRes.getExpireTimeForEnergy());
      if (!FastByteComparisons.isEqual(sender.toByteArray(), inheritorAddr)) {
        DelegatedResourceCapsule current = resourceStore.get(
            DelegatedResourceCapsule.createDbKey(sender.toByteArray(), inheritorAddr));
        Assert.assertNotNull(current);
        compare(oldInheritorState.getAcquiredRes(sender),
            contractState.getAcquiredRes(sender),
            current);
        checkIndex(sender.toByteArray(), inheritorAddr);
      } else {

      }
    }
    for (ByteString receiver : contractState.index.getToAccountsList()) {
      DelegatedResourceCapsule contractToReceiverRes = resourceStore.get(
          DelegatedResourceCapsule.createDbKey(contractAddr, receiver.toByteArray()));
      Assert.assertNotNull(contractToReceiverRes);
      Assert.assertEquals(0, contractToReceiverRes.getFrozenBalanceForBandwidth());
      Assert.assertEquals(0, contractToReceiverRes.getFrozenBalanceForEnergy());
      Assert.assertEquals(0, contractToReceiverRes.getExpireTimeForBandwidth());
      Assert.assertEquals(0, contractToReceiverRes.getExpireTimeForEnergy());
      if (!FastByteComparisons.isEqual(receiver.toByteArray(), inheritorAddr)) {
        DelegatedResourceCapsule current = resourceStore.get(
            DelegatedResourceCapsule.createDbKey(inheritorAddr, receiver.toByteArray()));
        Assert.assertNotNull(current);
        compare(oldInheritorState.getDelegatedRes(receiver),
            contractState.getDelegatedRes(receiver),
            current);
        checkIndex(inheritorAddr, receiver.toByteArray());
      } else {

      }
    }

    Assert.assertEquals(oldTotalNetWeight, dynamicStore.getTotalNetWeight());
    Assert.assertEquals(oldTotalEnergyWeight, dynamicStore.getTotalEnergyWeight());

    return result;
  }

  private void compare(DelegatedResourceCapsule origin,
                       DelegatedResourceCapsule added,
                       DelegatedResourceCapsule current) {
    long balanceOfBandwidth = origin.getFrozenBalanceForBandwidth() + added.getFrozenBalanceForBandwidth();
    if (balanceOfBandwidth != 0) {
      long expireTimeOfBandwidth = calculateNewExpireTime(
          origin.getFrozenBalanceForBandwidth(),
          origin.getExpireTimeForBandwidth(),
          added.getFrozenBalanceForBandwidth(),
          added.getExpireTimeForBandwidth());
      Assert.assertEquals(balanceOfBandwidth, current.getFrozenBalanceForBandwidth());
      Assert.assertEquals(expireTimeOfBandwidth, current.getExpireTimeForBandwidth());
    }
    long balanceOfEnergy = origin.getFrozenBalanceForEnergy() + added.getFrozenBalanceForEnergy();
    if (balanceOfEnergy != 0) {
      long expireTimeOfEnergy = calculateNewExpireTime(
          origin.getFrozenBalanceForEnergy(),
          origin.getExpireTimeForEnergy(),
          added.getFrozenBalanceForEnergy(),
          added.getExpireTimeForEnergy());
      Assert.assertEquals(balanceOfEnergy, current.getFrozenBalanceForEnergy());
      Assert.assertEquals(expireTimeOfEnergy, current.getExpireTimeForEnergy());
    }
  }

  private long calculateNewExpireTime(
      long originFrozenBalance,
      long originExpireTime,
      long addedFrozenBalance,
      long addedExpireTime) {
    long now = manager.getDynamicPropertiesStore().getLatestBlockHeaderTimestamp();
    long maxExpire = manager.getDynamicPropertiesStore().getMinFrozenTime() * Parameter.ChainConstant.FROZEN_PERIOD;
    return now +
        BigInteger.valueOf(Math.max(0, Math.min(originExpireTime - now, maxExpire)))
            .multiply(BigInteger.valueOf(originFrozenBalance))
            .add(BigInteger.valueOf(Math.max(0, Math.min(addedExpireTime - now, maxExpire)))
                .multiply(BigInteger.valueOf(addedFrozenBalance)))
            .divide(BigInteger.valueOf(Math.addExact(originFrozenBalance, addedFrozenBalance)))
            .longValue();
  }

  private void checkIndex(byte[] fromAddr, byte[] toAddr) {
    DelegatedResourceAccountIndexStore indexStore = manager.getDelegatedResourceAccountIndexStore();
    DelegatedResourceAccountIndexCapsule senderIndex = indexStore.get(fromAddr);
    Assert.assertNotNull(senderIndex);
    Assert.assertTrue(senderIndex.getToAccountsList().contains(ByteString.copyFrom(toAddr)));
    DelegatedResourceAccountIndexCapsule inheritorIndex = indexStore.get(toAddr);
    Assert.assertNotNull(inheritorIndex);
    Assert.assertTrue(inheritorIndex.getFromAccountsList().contains(ByteString.copyFrom(fromAddr)));
  }

  private void checkReceipt(TVMTestResult result, AccountCapsule caller, AccountCapsule deployer) {
    AccountStore accountStore = manager.getAccountStore();
    long callerEnergyUsage = result.getReceipt().getEnergyUsage();
    long deployerEnergyUsage = result.getReceipt().getOriginEnergyUsage();
    long burnedTrx = result.getReceipt().getEnergyFee();
    AccountCapsule newCaller = accountStore.get(caller.createDbKey());
    Assert.assertEquals(callerEnergyUsage,
        newCaller.getEnergyUsage() - caller.getEnergyUsage());
    Assert.assertEquals(deployerEnergyUsage,
        accountStore.get(deployer.createDbKey()).getEnergyUsage() - deployer.getEnergyUsage());
    Assert.assertEquals(burnedTrx,
        caller.getBalance() - accountStore.get(caller.createDbKey()).getBalance());
  }

  @Test
  public void buildDataBase() throws Exception {
    //byte[] contract = Commons.decode58Check("27jfgGFn9zr9kjUTfrurj8GeFeZzVHXwuyk");
    byte[] contract = deployContract("TestFreeze", CONTRACT_CODE);
    System.out.println(Hex.toHexString(contract));
    triggerFreeze(owner, contract, contract, 1_000_000, 0, SUCCESS, null);
    triggerFreeze(owner, contract, contract, 1_000_000, 1, SUCCESS, null);
    VMActuator.record = true;
    for (int i = 0; i < 10_000; i++) {
      if (i != 0 && i % 1_000 == 0) {
        System.out.println(i);
        System.out.println(String.format("%d %d %d", VMActuator.cnt, VMActuator.time,
            VMActuator.time / VMActuator.cnt / 1000));
        VMActuator.cnt = 0;
        VMActuator.time = 0;
      }
      byte[] receiverAddr = getRandomAddr();
      triggerFreeze(owner, contract, receiverAddr, 1_000_000, 0, SUCCESS, null);
      triggerFreeze(owner, contract, receiverAddr, 1_000_000, 1, SUCCESS, null);
    }
  }

  @Test
  public void benchmark() throws Exception {
    byte[] contract = Commons.decode58Check("27jfgGFn9zr9kjUTfrurj8GeFeZzVHXwuyk");
    //byte[] contract = Hex.decode("a0a8a8da30183c675744677f70762a612175739a9d");
    byte[] factory = deployContract("FactoryContract", FACTORY_CODE);
    //byte[] factory = Hex.decode("a0c15a4754f0ce02a5b330c44a28d8628d7ba6afa5");
    System.out.println(Hex.toHexString(factory));
    VMActuator.record = true;
    for (int i = 0; i < 200; i++) {
//      if (i != 0 && i % 1000 == 0) {
//        System.out.println(String.format("%d %d %d", VMActuator.cnt, VMActuator.time,
//            VMActuator.time / VMActuator.cnt / 1000));
//      }
      if (VMActuator.cnt != 0) {
        System.out.println(String.format("%d %d %d", VMActuator.cnt, VMActuator.time,
            VMActuator.time / VMActuator.cnt / 1000));
      }
      byte[] create2Addr = deployCreate2Contract(factory, i);
      String methodByAddr = "destroy(address)";
      String hexInput = AbiUtil.parseMethod(methodByAddr,
          Collections.singletonList(StringUtil.encode58Check(create2Addr)));
      TVMTestResult result = TvmTestUtils.triggerContractAndReturnTvmTestResult(
          owner, contract, Hex.decode(hexInput), 0, fee, manager, null);
      Assert.assertEquals(SUCCESS, result.getReceipt().getResult());
      contract = create2Addr;
    }
  }

  private byte[] getRandomAddr() {
    Random random = new Random(System.currentTimeMillis());
    byte[] addr = new BigInteger(21 * 8, random).toByteArray();
    addr[0] = (byte) 0xa0;
    return Arrays.copyOfRange(addr, 0, 21);
  }

  @After
  public void destroy() {
    Args.clearParam();
    context.destroy();
//    if (FileUtil.deleteDir(new File(dbPath))) {
//      logger.info("Release resources successful.");
//    } else {
//      logger.error("Release resources failure.");
//    }
  }
}
