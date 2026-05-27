package org.tron.common.runtime.vm;

import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bouncycastle.util.encoders.Hex;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.runtime.TVMTestResult;
import org.tron.common.runtime.TvmTestUtils;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.ReceiptCheckErrException;
import org.tron.core.exception.VMIllegalException;
import org.tron.core.vm.config.VMConfig;
import org.tron.protos.Protocol.AccountType;

public class SuicideTokenSweepBaselineTest extends VMTestBase {

  private static final long FEE_LIMIT = 1_000_000_000L;
  private static final long TOKEN_ID_BASE = 10_000_000L;
  private static final int[] ASSET_COUNTS = new int[] {
      0, 1, 100, 1_000, 5_000, 10_000, 20_000
  };
  private static final int WARMUP_ROUNDS =
      Integer.getInteger("suicideSweep.warmupRounds", 2);
  private static final int MEASUREMENT_ROUNDS =
      Integer.getInteger("suicideSweep.measurementRounds", 5);

  private int recipientSequence;
  private int deploySequence;

  @Override
  @Before
  public void init() throws IOException {
    super.init();
    manager.getDynamicPropertiesStore().saveAllowTvmTransferTrc10(1);
    manager.getDynamicPropertiesStore().saveAllowTvmSolidity059(1);
    manager.getDynamicPropertiesStore().saveAllowTvmSelfdestructRestriction(1);
    manager.getDynamicPropertiesStore().setAllowAssetOptimization(1);
    manager.getDynamicPropertiesStore().saveAllowSameTokenName(1);
    manager.getDynamicPropertiesStore().saveMaxCpuTimeOfOneTx(80);
  }

  @Override
  @After
  public void destroy() {
    VMConfig.initAllowTvmTransferTrc10(0);
    VMConfig.initAllowTvmSolidity059(0);
    VMConfig.initAllowTvmSelfdestructRestriction(0);
    super.destroy();
  }

  @Test
  public void selfdestructTokenSweepBaseline()
      throws ContractExeException, ReceiptCheckErrException, VMIllegalException,
      ContractValidateException {
    for (int round = 0; round < WARMUP_ROUNDS; round++) {
      for (int assetCount : orderedAssetCounts(round)) {
        measureSelfdestruct(assetCount, false);
      }
    }

    Map<Integer, List<Measurement>> measurements = new LinkedHashMap<>();
    for (int assetCount : ASSET_COUNTS) {
      measurements.put(assetCount, new ArrayList<>());
    }

    Long baselineEnergy = null;
    for (int round = 0; round < MEASUREMENT_ROUNDS; round++) {
      for (int assetCount : orderedAssetCounts(round)) {
        Measurement measurement = measureSelfdestruct(assetCount, true);
        if (baselineEnergy == null) {
          baselineEnergy = measurement.energy;
        }
        Assert.assertEquals(baselineEnergy.longValue(), measurement.energy);
        measurements.get(assetCount).add(measurement);
      }
    }

    System.out.println("SELFDESTRUCT_TRC10_SWEEP_BASELINE");
    System.out.println("warmupRounds=" + WARMUP_ROUNDS
        + ",measurementRounds=" + MEASUREMENT_ROUNDS);
    System.out.println("assetCount,samples,energy,minMs,medianMs,p95Ms,maxMs,meanMs");
    for (int assetCount : ASSET_COUNTS) {
      Summary summary = summarize(measurements.get(assetCount));
      System.out.printf(Locale.ROOT, "%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f%n",
          assetCount, summary.samples, summary.energy, nsToMs(summary.minNs),
          nsToMs(summary.medianNs), nsToMs(summary.p95Ns), nsToMs(summary.maxNs),
          nsToMs(summary.meanNs));
    }
  }

  private int[] orderedAssetCounts(int round) {
    int[] ordered = ASSET_COUNTS.clone();
    if ((round & 1) == 1) {
      for (int i = 0, j = ordered.length - 1; i < j; i++, j--) {
        int tmp = ordered[i];
        ordered[i] = ordered[j];
        ordered[j] = tmp;
      }
    }
    return ordered;
  }

  private Summary summarize(List<Measurement> measurements) {
    Assert.assertEquals(MEASUREMENT_ROUNDS, measurements.size());
    List<Long> samples = new ArrayList<>(measurements.size());
    long energy = measurements.get(0).energy;
    long total = 0;
    for (Measurement measurement : measurements) {
      Assert.assertEquals(energy, measurement.energy);
      samples.add(measurement.elapsedNs);
      total += measurement.elapsedNs;
    }
    Collections.sort(samples);
    int medianIndex = samples.size() / 2;
    int p95Index = Math.min(samples.size() - 1,
        (int) Math.ceil(samples.size() * 0.95) - 1);
    return new Summary(measurements.size(), energy, samples.get(0), samples.get(medianIndex),
        samples.get(p95Index), samples.get(samples.size() - 1), total / samples.size());
  }

  private double nsToMs(long elapsedNs) {
    return elapsedNs / 1_000_000.0;
  }

  private Measurement measureSelfdestruct(int assetCount, boolean verifyRecipientAssets)
      throws ContractExeException, ReceiptCheckErrException, VMIllegalException,
      ContractValidateException {
    byte[] contractAddress = deploySuicideContract();
    seedContractAssets(contractAddress, assetCount);

    String recipientParam = nextRecipientParam();
    byte[] recipientAddress = createEmptyRecipient(recipientParam);

    byte[] triggerData = TvmTestUtils.parseAbi("suicide(address)",
        "000000000000000000000000" + recipientParam);
    long started = System.nanoTime();
    TVMTestResult result = TvmTestUtils.triggerContractAndReturnTvmTestResult(
        Hex.decode(OWNER_ADDRESS), contractAddress, triggerData, 0, FEE_LIMIT, manager, null);
    long elapsedNs = System.nanoTime() - started;

    Assert.assertNull(result.getRuntime().getRuntimeError());
    Assert.assertFalse(result.getRuntime().getResult().isRevert());
    Assert.assertNull(result.getRuntime().getResult().getException());
    if (verifyRecipientAssets) {
      Assert.assertEquals(assetCount,
          manager.getAccountStore().get(recipientAddress).getAssetMapV2().size());
    }
    return new Measurement(result.getReceipt().getEnergyUsageTotal(), elapsedNs);
  }

  private void seedContractAssets(byte[] contractAddress, int assetCount) {
    AccountCapsule contractAccount = manager.getAccountStore().get(contractAddress);
    Assert.assertNotNull(contractAccount);
    contractAccount.clearAsset();
    if (assetCount > 0) {
      Map<String, Long> assets = new HashMap<>(assetCount);
      for (int i = 0; i < assetCount; i++) {
        assets.put(String.valueOf(TOKEN_ID_BASE + i), 1L);
      }
      contractAccount.addAssetMapV2(assets);
    }
    manager.getAccountStore().put(contractAddress, contractAccount);

    if (assetCount > 0) {
      assertAssetBalanceInStore(contractAddress, TOKEN_ID_BASE);
      assertAssetBalanceInStore(contractAddress, TOKEN_ID_BASE + assetCount - 1);
    }
  }

  private void assertAssetBalanceInStore(byte[] accountAddress, long tokenId) {
    byte[] tokenIdBytes = String.valueOf(tokenId).getBytes(StandardCharsets.US_ASCII);
    byte[] key = new byte[accountAddress.length + tokenIdBytes.length];
    System.arraycopy(accountAddress, 0, key, 0, accountAddress.length);
    System.arraycopy(tokenIdBytes, 0, key, accountAddress.length, tokenIdBytes.length);
    Assert.assertArrayEquals(Longs.toByteArray(1L), manager.getAccountAssetStore().get(key));
  }

  private byte[] deploySuicideContract()
      throws ContractExeException, ReceiptCheckErrException, ContractValidateException,
      VMIllegalException {
    String abi = "[{\"constant\":false,\"inputs\":[{\"name\":\"toAddress\",\"type\":\"address\"}]"
        + ",\"name\":\"suicide\",\"outputs\":[],\"payable\":true,"
        + "\"stateMutability\":\"payable\",\"type\":\"function\"},{\"inputs\":[],"
        + "\"payable\":true,\"stateMutability\":\"payable\",\"type\":\"constructor\"},"
        + "{\"payable\":true,\"stateMutability\":\"payable\",\"type\":\"fallback\"}]";
    String code = "6080604052608a8060116000396000f300608060405260043610603e5763ffffffff"
        + "7c0100000000000000000000000000000000000000000000000000000000600035041663"
        + "dbc1f22681146040575b005b603e60043573ffffffffffffffffffffffffffffffffffffffff"
        + "1680ff00a165627a7a72305820e382f1dabb1c53705abe0c3e99497025ffbf78b73c079"
        + "471d8984a745b3218720029";

    TVMTestResult result = TvmTestUtils.deployContractAndReturnTvmTestResult(
        "SuicideTokenSweepBaseline" + (++deploySequence), Hex.decode(OWNER_ADDRESS), abi, code,
        1_000L, FEE_LIMIT, 0, null, manager, null);
    Assert.assertNull(result.getRuntime().getRuntimeError());
    return result.getContractAddress();
  }

  private String nextRecipientParam() {
    recipientSequence++;
    return String.format(Locale.ROOT, "%040x", recipientSequence);
  }

  private byte[] createEmptyRecipient(String recipientParam) {
    byte[] recipientAddress = Hex.decode(OWNER_ADDRESS.substring(0, 2) + recipientParam);
    Assert.assertNull(manager.getAccountStore().get(recipientAddress));
    AccountCapsule account = new AccountCapsule(ByteString.copyFrom(recipientAddress),
        ByteString.copyFromUtf8("recipient-" + recipientSequence), AccountType.Normal);
    manager.getAccountStore().put(recipientAddress, account);
    Assert.assertNotNull(manager.getAccountStore().get(recipientAddress));
    return recipientAddress;
  }

  private static class Measurement {
    private final long energy;
    private final long elapsedNs;

    private Measurement(long energy, long elapsedNs) {
      this.energy = energy;
      this.elapsedNs = elapsedNs;
    }
  }

  private static class Summary {
    private final int samples;
    private final long energy;
    private final long minNs;
    private final long medianNs;
    private final long p95Ns;
    private final long maxNs;
    private final long meanNs;

    private Summary(int samples, long energy, long minNs, long medianNs, long p95Ns,
        long maxNs, long meanNs) {
      this.samples = samples;
      this.energy = energy;
      this.minNs = minNs;
      this.medianNs = medianNs;
      this.p95Ns = p95Ns;
      this.maxNs = maxNs;
      this.meanNs = meanNs;
    }
  }
}
