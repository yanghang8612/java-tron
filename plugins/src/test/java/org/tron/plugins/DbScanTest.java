package org.tron.plugins;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.Sha256Hash;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;
import picocli.CommandLine;

public class DbScanTest {

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  private final CommandLine cli = new CommandLine(new Toolkit());

  @Test
  public void helpExitsZero() {
    Assert.assertEquals(0, cli.execute("db", "scan", "-h"));
  }

  @Test
  public void missingRequiredArgsExitsNonZero() {
    int code = cli.execute("db", "scan");
    Assert.assertNotEquals(0, code);
  }

  private static final byte[] TARGET = base58("TWjkoz18Y48SgWoxEeGG11ezCCzee8wo1A");
  private static final byte[] CALLER = base58("TLLM21wteSPs4hKjbxgmH1L6poyMjeTbHm");

  private static byte[] base58(String addr) {
    return org.tron.plugins.utils.Base58Check.decode58Check(addr);
  }

  private static Protocol.Transaction trigger(
      byte[] caller, byte[] contractAddress, byte[] data, long callValue,
      Protocol.Transaction.Result.contractResult ret) {
    TriggerSmartContract inner = TriggerSmartContract.newBuilder()
        .setOwnerAddress(ByteString.copyFrom(caller))
        .setContractAddress(ByteString.copyFrom(contractAddress))
        .setData(ByteString.copyFrom(data))
        .setCallValue(callValue)
        .build();
    Protocol.Transaction.Contract c = Protocol.Transaction.Contract.newBuilder()
        .setType(Protocol.Transaction.Contract.ContractType.TriggerSmartContract)
        .setParameter(Any.pack(inner))
        .build();
    return Protocol.Transaction.newBuilder()
        .setRawData(Protocol.Transaction.raw.newBuilder().addContract(c))
        .addRet(Protocol.Transaction.Result.newBuilder().setContractRet(ret))
        .build();
  }

  @Test
  public void matchesSuccessfulTriggerToTarget() {
    Protocol.Transaction tx = trigger(CALLER, TARGET, new byte[] {0x12, 0x34}, 7L,
        Protocol.Transaction.Result.contractResult.SUCCESS);
    DbScan.Match m = DbScan.matchTransaction(tx, TARGET);
    Assert.assertNotNull(m);
    Assert.assertArrayEquals(CALLER, m.caller);
    Assert.assertEquals(7L, m.callValue);
    Assert.assertArrayEquals(new byte[] {0x12, 0x34}, m.data);
  }

  @Test
  public void skipsRevertedTrigger() {
    Protocol.Transaction tx = trigger(CALLER, TARGET, new byte[0], 0L,
        Protocol.Transaction.Result.contractResult.REVERT);
    Assert.assertNull(DbScan.matchTransaction(tx, TARGET));
  }

  @Test
  public void skipsTriggerToDifferentContract() {
    byte[] other = Arrays.copyOf(TARGET, TARGET.length);
    other[20] ^= 0x01;
    Protocol.Transaction tx = trigger(CALLER, other, new byte[0], 0L,
        Protocol.Transaction.Result.contractResult.SUCCESS);
    Assert.assertNull(DbScan.matchTransaction(tx, TARGET));
  }

  @Test
  public void skipsNonTriggerContractType() {
    Protocol.Transaction.Contract c = Protocol.Transaction.Contract.newBuilder()
        .setType(Protocol.Transaction.Contract.ContractType.TransferContract)
        .build();
    Protocol.Transaction tx = Protocol.Transaction.newBuilder()
        .setRawData(Protocol.Transaction.raw.newBuilder().addContract(c))
        .addRet(Protocol.Transaction.Result.newBuilder()
            .setContractRet(Protocol.Transaction.Result.contractResult.SUCCESS))
        .build();
    Assert.assertNull(DbScan.matchTransaction(tx, TARGET));
  }

  @Test
  public void skipsTriggerWithoutRet() {
    TriggerSmartContract inner = TriggerSmartContract.newBuilder()
        .setOwnerAddress(ByteString.copyFrom(CALLER))
        .setContractAddress(ByteString.copyFrom(TARGET))
        .build();
    Protocol.Transaction.Contract c = Protocol.Transaction.Contract.newBuilder()
        .setType(Protocol.Transaction.Contract.ContractType.TriggerSmartContract)
        .setParameter(Any.pack(inner))
        .build();
    Protocol.Transaction tx = Protocol.Transaction.newBuilder()
        .setRawData(Protocol.Transaction.raw.newBuilder().addContract(c))
        .build();
    Assert.assertNull(DbScan.matchTransaction(tx, TARGET));
  }

  /** Build a 32-byte BlockId: first 8 bytes are big-endian num, last 24 bytes are hash tail. */
  private static byte[] blockId(long num, byte[] hash32) {
    byte[] id = new byte[32];
    for (int i = 7; i >= 0; i--) {
      id[i] = (byte) (num & 0xff);
      num >>>= 8;
    }
    System.arraycopy(hash32, 8, id, 8, 24);
    return id;
  }

  private static Protocol.Block buildBlock(long num, long timestamp,
                                           List<Protocol.Transaction> txs) {
    Protocol.BlockHeader.raw rawHeader = Protocol.BlockHeader.raw.newBuilder()
        .setNumber(num)
        .setTimestamp(timestamp)
        .build();
    Protocol.Block.Builder b = Protocol.Block.newBuilder()
        .setBlockHeader(Protocol.BlockHeader.newBuilder().setRawData(rawHeader));
    for (Protocol.Transaction tx : txs) {
      b.addTransactions(tx);
    }
    return b.build();
  }

  @Test
  public void scanEmitsOneRowPerSuccessfulTrigger() throws Exception {
    File root = folder.newFolder();
    File database = Paths.get(root.getPath(), "database").toFile();
    Assert.assertTrue(database.mkdirs());

    Protocol.Transaction hit = trigger(CALLER, TARGET, new byte[] {0x01, 0x02}, 5L,
        Protocol.Transaction.Result.contractResult.SUCCESS);
    Protocol.Transaction miss = trigger(CALLER, TARGET, new byte[0], 0L,
        Protocol.Transaction.Result.contractResult.REVERT);

    Protocol.Block block123 = buildBlock(123L, 1_700_000_000_000L,
        java.util.Arrays.asList(hit, miss));
    byte[] block123Bytes = block123.toByteArray();
    byte[] hashTail = Sha256Hash.hash(true, block123Bytes);
    byte[] id123 = blockId(123L, hashTail);

    try (DBInterface blockIndex = DbTool.getDB(database.toPath(), "block-index");
         DBInterface blocks = DbTool.getDB(database.toPath(), "block");
         DBInterface history = DbTool.getDB(database.toPath(),
             "transactionHistoryStore")) {
      blockIndex.put(ByteArray.fromLong(123L), id123);
      blocks.put(id123, block123Bytes);
    }

    File outFile = new File(folder.getRoot(), "scan.csv");
    int code = cli.execute("db", "scan",
        "--db-path", database.toString(),
        "--contract", "TWjkoz18Y48SgWoxEeGG11ezCCzee8wo1A",
        "--start", "123",
        "--end", "123",
        "--out", outFile.getAbsolutePath());

    Assert.assertEquals(0, code);
    Assert.assertTrue(outFile.exists());
    String content = new String(java.nio.file.Files.readAllBytes(outFile.toPath()),
        StandardCharsets.UTF_8);
    String[] lines = content.split("\n");
    Assert.assertEquals(
        "block_num,timestamp,txid,caller,call_value,call_token_value,token_id,"
            + "data_hex,energy_usage_total,energy_fee,net_usage,receipt_result",
        lines[0]);
    Assert.assertEquals(2, lines.length);
    String[] cols = lines[1].split(",");
    Assert.assertEquals("123", cols[0]);
    Assert.assertEquals("1700000000000", cols[1]);
    Assert.assertEquals("TLLM21wteSPs4hKjbxgmH1L6poyMjeTbHm", cols[3]);
    Assert.assertEquals("5", cols[4]);
    Assert.assertEquals("0102", cols[7]);
  }

  @Test
  public void scanEmitsHeaderOnlyForEmptyRange() throws Exception {
    File root = folder.newFolder();
    File database = Paths.get(root.getPath(), "database").toFile();
    Assert.assertTrue(database.mkdirs());
    try (DBInterface blockIndex = DbTool.getDB(database.toPath(), "block-index");
         DBInterface blocks = DbTool.getDB(database.toPath(), "block");
         DBInterface history = DbTool.getDB(database.toPath(),
             "transactionHistoryStore")) {
      // empty — just create the DBs
    }
    File outFile = new File(folder.getRoot(), "empty.csv");
    int code = cli.execute("db", "scan",
        "--db-path", database.toString(),
        "--contract", "TWjkoz18Y48SgWoxEeGG11ezCCzee8wo1A",
        "--start", "0", "--end", "0",
        "--out", outFile.getAbsolutePath());
    Assert.assertEquals(0, code);
    String content = new String(java.nio.file.Files.readAllBytes(outFile.toPath()),
        StandardCharsets.UTF_8);
    Assert.assertTrue(content.startsWith("block_num,timestamp,txid"));
    Assert.assertEquals(1, content.trim().split("\n").length);
  }

  @Test
  public void scanFillsReceiptColumnsWhenHistoryPresent() throws Exception {
    File root = folder.newFolder();
    File database = Paths.get(root.getPath(), "database").toFile();
    Assert.assertTrue(database.mkdirs());

    Protocol.Transaction hit = trigger(CALLER, TARGET, new byte[] {0x55}, 0L,
        Protocol.Transaction.Result.contractResult.SUCCESS);
    Protocol.Block block = buildBlock(7L, 1_700_000_000_000L,
        java.util.Collections.singletonList(hit));
    byte[] blockBytes = block.toByteArray();
    byte[] id7 = blockId(7L, Sha256Hash.hash(true, blockBytes));
    byte[] txid = Sha256Hash.hash(true, hit.getRawData().toByteArray());

    Protocol.TransactionInfo info = Protocol.TransactionInfo.newBuilder()
        .setReceipt(Protocol.ResourceReceipt.newBuilder()
            .setEnergyUsageTotal(31415L)
            .setEnergyFee(271L)
            .setNetUsage(42L)
            .setResult(Protocol.Transaction.Result.contractResult.SUCCESS))
        .build();

    try (DBInterface blockIndex = DbTool.getDB(database.toPath(), "block-index");
         DBInterface blocks = DbTool.getDB(database.toPath(), "block");
         DBInterface history = DbTool.getDB(database.toPath(),
             "transactionHistoryStore")) {
      blockIndex.put(ByteArray.fromLong(7L), id7);
      blocks.put(id7, blockBytes);
      history.put(txid, info.toByteArray());
    }

    File outFile = new File(folder.getRoot(), "receipt.csv");
    int code = cli.execute("db", "scan",
        "--db-path", database.toString(),
        "--contract", "TWjkoz18Y48SgWoxEeGG11ezCCzee8wo1A",
        "--start", "7", "--end", "7",
        "--out", outFile.getAbsolutePath());

    Assert.assertEquals(0, code);
    String content = new String(java.nio.file.Files.readAllBytes(outFile.toPath()),
        StandardCharsets.UTF_8);
    String[] lines = content.trim().split("\n");
    Assert.assertEquals(2, lines.length);
    String[] cols = lines[1].split(",");
    Assert.assertEquals("31415", cols[8]);
    Assert.assertEquals("271", cols[9]);
    Assert.assertEquals("42", cols[10]);
    Assert.assertEquals("SUCCESS", cols[11]);
  }

  @Test
  public void scanRejectsDbPathMissingBlockIndex() throws Exception {
    File root = folder.newFolder();
    File database = Paths.get(root.getPath(), "database").toFile();
    Assert.assertTrue(database.mkdirs());
    // Intentionally do NOT create block-index/ or block/ sub-directories.
    File outFile = new File(folder.getRoot(), "should-not-exist.csv");
    int code = cli.execute("db", "scan",
        "--db-path", database.toString(),
        "--contract", "TWjkoz18Y48SgWoxEeGG11ezCCzee8wo1A",
        "--start", "0", "--end", "0",
        "--out", outFile.getAbsolutePath());
    Assert.assertEquals(2, code);
    Assert.assertFalse(outFile.exists());
  }
}
