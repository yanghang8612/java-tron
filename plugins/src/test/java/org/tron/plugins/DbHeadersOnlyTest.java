package org.tron.plugins;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.tron.common.arch.Arch;
import org.tron.common.utils.FastSyncStorage;
import org.tron.common.utils.Sha256Hash;
import org.tron.plugins.utils.DBUtils;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DbTool;
import org.tron.plugins.utils.db.DbTool.DbType;
import org.tron.protos.Protocol;

@RunWith(Parameterized.class)
public class DbHeadersOnlyTest {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Parameterized.Parameter
  public DbType engine;

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> engines() {
    List<Object[]> engines = new ArrayList<>();
    engines.add(new Object[]{DbType.RocksDB});
    if (!Arch.isArm64()) {
      engines.add(new Object[]{DbType.LevelDB});
    }
    return engines;
  }

  @After
  public void close() {
    DbTool.close();
  }

  private DBInterface db(Path source, String name) throws Exception {
    Files.createDirectories(source);
    return DbTool.getDB(source.toString(), name, engine);
  }

  private Protocol.Block block(long number, byte[] parent) {
    Protocol.Transaction transaction = Protocol.Transaction.newBuilder()
        .setRawData(Protocol.Transaction.raw.newBuilder()
            .setData(ByteString.copyFromUtf8("transaction-" + number))).build();
    return Protocol.Block.newBuilder().addTransactions(transaction)
        .setBlockHeader(Protocol.BlockHeader.newBuilder()
            .setRawData(Protocol.BlockHeader.raw.newBuilder().setNumber(number)
                .setParentHash(ByteString.copyFrom(parent)).setTimestamp(number * 3000)
                .setTxTrieRoot(Sha256Hash.of(true, transaction.getRawData().toByteArray())
                    .getByteString())))
        .build();
  }

  private byte[] id(Protocol.Block block) {
    byte[] hash = Sha256Hash.of(true, block.getBlockHeader().getRawData().toByteArray()).getBytes();
    System.arraycopy(Longs.toByteArray(block.getBlockHeader().getRawData().getNumber()),
        0, hash, 0, Long.BYTES);
    return hash;
  }

  private Path fixture() throws Exception {
    Path source = temporaryFolder.newFolder().toPath();
    for (String name : Arrays.asList("block", "block-index", "trans", "transactionRetStore",
        "transactionHistoryStore", "trans-cache", "recent-transaction", "properties", "tracker",
        "account")) {
      db(source, name);
    }
    byte[] parent = new byte[32];
    for (long number = 0; number <= 8; number++) {
      Protocol.Block block = block(number, parent);
      parent = id(block);
      db(source, "block").put(parent, block.toByteArray());
      db(source, "block-index").put(Longs.toByteArray(number), parent);
    }
    db(source, "properties").put(bytes("latest_block_header_number"), Longs.toByteArray(8));
    db(source, "properties").put(bytes("latest_block_header_hash"), parent);
    for (String name : Arrays.asList("trans", "transactionRetStore", "transactionHistoryStore",
        "trans-cache", "recent-transaction")) {
      db(source, name).put(bytes("old-transaction"), bytes("payload"));
    }
    db(source, "account").put(bytes("sentinel"), bytes("unchanged-state"));
    DbTool.close();
    return source;
  }

  @Test
  public void keepsOnlyRecentHeadersAndEmptiesHistory() throws Exception {
    Path source = fixture();
    byte[] genesis = db(source, "block-index").get(Longs.toByteArray(0));
    DbTool.close();
    DbHeadersOnly.migrate(source, 4);
    Assert.assertEquals(4, db(source, "block").size());
    Assert.assertEquals(4, db(source, "block-index").size());
    Assert.assertNull(db(source, "block-index").get(Longs.toByteArray(4)));
    for (long number = 5; number <= 8; number++) {
      byte[] blockId = db(source, "block-index").get(Longs.toByteArray(number));
      Assert.assertEquals(0,
          Protocol.Block.parseFrom(db(source, "block").get(blockId)).getTransactionsCount());
    }
    for (String name : Arrays.asList("trans", "transactionRetStore", "transactionHistoryStore",
        "trans-cache", "recent-transaction")) {
      Assert.assertEquals(0, db(source, name).size());
    }
    Assert.assertArrayEquals(genesis,
        db(source, "tracker").get(bytes(FastSyncStorage.GENESIS_ID_KEY)));
    Assert.assertArrayEquals(bytes("unchanged-state"),
        db(source, "account").get(bytes("sentinel")));
    Assert.assertFalse(Files.exists(source.resolve(FastSyncStorage.MIGRATION_PENDING_FILE)));
    Assert.assertEquals(9, db(source.resolve(".header-only-backup"), "block").size());
  }

  private byte[] checkpointKey(String name, byte[] key) {
    return Bytes.concat(Ints.toByteArray(bytes(name).length), bytes(name), key);
  }

  private void checkpointPut(DBInterface checkpoint, String name, byte[] key, byte[] value) {
    checkpoint.put(checkpointKey(name, key),
        Bytes.concat(new byte[]{DBUtils.Operator.PUT.getValue()}, value));
  }

  @Test
  public void preservesStateRecoveryAndDoesNotRestoreTransactionPayloads() throws Exception {
    assertCheckpointRecovery(true);
  }

  @Test
  public void preservesV1CheckpointStateWithoutRestoringHistory() throws Exception {
    assertCheckpointRecovery(false);
  }

  private void assertCheckpointRecovery(boolean v2) throws Exception {
    Path source = fixture();
    Path checkpointParent = v2 ? source.resolve("checkpoint") : source;
    String checkpointName = v2 ? "1000000" : "tmp";
    byte[] parent = db(source, "block-index").get(Longs.toByteArray(8));
    Protocol.Block next = block(9, parent);
    DBInterface checkpoint = db(checkpointParent, checkpointName);
    checkpointPut(checkpoint, "properties", bytes("latest_block_header_number"),
        Longs.toByteArray(9));
    checkpointPut(checkpoint, "properties", bytes("latest_block_header_hash"), id(next));
    checkpointPut(checkpoint, "block", id(next), next.toByteArray());
    checkpointPut(checkpoint, "block-index", Longs.toByteArray(9), id(next));
    checkpointPut(checkpoint, "transactionRetStore", bytes("old-ret"), bytes("receipt"));
    checkpointPut(checkpoint, "trans", bytes("old-tx"), bytes("body"));
    checkpointPut(checkpoint, "account", bytes("sentinel"), bytes("recovered-state"));
    DbTool.close();

    DbHeadersOnly.migrate(source, 4);
    Assert.assertArrayEquals(id(next), db(source, "block-index").get(Longs.toByteArray(9)));
    Assert.assertEquals(0, Protocol.Block.parseFrom(db(source, "block").get(id(next)))
        .getTransactionsCount());
    checkpoint = db(checkpointParent, checkpointName);
    Assert.assertNull(checkpoint.get(checkpointKey("block", id(next))));
    Assert.assertNull(checkpoint.get(checkpointKey("transactionRetStore", bytes("old-ret"))));
    Assert.assertNull(checkpoint.get(checkpointKey("trans", bytes("old-tx"))));
    Assert.assertArrayEquals(Bytes.concat(new byte[]{DBUtils.Operator.PUT.getValue()},
        bytes("recovered-state")), checkpoint.get(checkpointKey("account", bytes("sentinel"))));
    Assert.assertArrayEquals(bytes("unchanged-state"),
        db(source, "account").get(bytes("sentinel")));
    Assert.assertEquals(8, Longs.fromByteArray(db(source, "properties")
        .get(bytes("latest_block_header_number"))));
  }

  @Test
  public void refusesMissingRecentHeaderBeforeChangingSource() throws Exception {
    Path source = fixture();
    db(source, "block-index").delete(Longs.toByteArray(7));
    DbTool.close();
    Assert.assertThrows(IllegalStateException.class, () -> DbHeadersOnly.migrate(source, 4));
    Assert.assertEquals(9, db(source, "block").size());
    Assert.assertEquals(1, db(source, "transactionRetStore").size());
    Assert.assertFalse(Files.exists(source.resolve(FastSyncStorage.MIGRATION_PENDING_FILE)));
    Assert.assertFalse(Files.exists(source.resolve(".header-only-backup")));
  }

  @Test
  public void refusesLiveDatabaseLock() throws Exception {
    // RocksDB permits a second open within the same process on macOS; the node is separate.
    Assume.assumeTrue(engine == DbType.LevelDB);
    Path source = fixture();
    try (DBInterface ignored = DbTool.getDB(source, "block")) {
      Assert.assertThrows(Exception.class, () -> DbHeadersOnly.migrate(source, 4));
    }
    Assert.assertFalse(Files.exists(source.resolve(".header-only-stage")));
    Assert.assertFalse(Files.exists(source.resolve(FastSyncStorage.MIGRATION_PENDING_FILE)));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
