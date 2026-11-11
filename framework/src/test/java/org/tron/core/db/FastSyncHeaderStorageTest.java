package org.tron.core.db;

import com.google.protobuf.ByteString;
import org.junit.Assert;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.ChainBaseManager.NodeType;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db2.ISession;
import org.tron.core.exception.P2pException;
import org.tron.core.exception.TronError;
import org.tron.core.net.TronNetDelegate;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.Inventory.InventoryType;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.contract.BalanceContract.TransferContract;

public class FastSyncHeaderStorageTest extends BaseMethodTest {

  private BlockCapsule block(long number) {
    Protocol.Transaction transaction = Protocol.Transaction.newBuilder()
        .setRawData(Protocol.Transaction.raw.newBuilder().setData(ByteString.copyFromUtf8("body")))
        .build();
    Protocol.BlockHeader header = Protocol.BlockHeader.newBuilder()
        .setRawData(Protocol.BlockHeader.raw.newBuilder().setNumber(number).setTimestamp(3000L))
        .build();
    BlockCapsule block = new BlockCapsule(Protocol.Block.newBuilder().setBlockHeader(header)
        .addTransactions(transaction).build());
    block.setMerkleRoot();
    return block;
  }

  @Test
  public void headerWritesAndExpirationRevokeTogether() throws Exception {
    BlockCapsule expired = block(10);
    BlockCapsule boundary = block(11);
    dbManager.saveBlockHeader(expired);
    dbManager.saveBlockHeader(boundary);
    BlockCapsule next = block(10 + Manager.HEADER_RETENTION_BLOCKS);
    try (ISession ignored = dbManager.getRevokingStore().buildSession()) {
      dbManager.saveBlockHeader(next);
      Assert.assertNull(dbManager.getBlockStore().getUnchecked(expired.getBlockId().getBytes()));
      Assert.assertFalse(dbManager.getBlockIndexStore().has(ByteArray.fromLong(10)));
      Assert.assertNotNull(dbManager.getBlockStore()
          .getUnchecked(boundary.getBlockId().getBytes()));
      Assert.assertEquals(1, dbManager.getBlockStore().getBlockByLatestNum(1).size());
      Assert.assertEquals(next.getNum(),
          dbManager.getBlockStore().getBlockByLatestNum(1).get(0).getNum());
    }
    Assert.assertNotNull(dbManager.getBlockStore().getUnchecked(expired.getBlockId().getBytes()));
    Assert.assertTrue(dbManager.getBlockIndexStore().has(ByteArray.fromLong(10)));
    Assert.assertNull(dbManager.getBlockStore().getUnchecked(next.getBlockId().getBytes()));
    try (ISession session = dbManager.getRevokingStore().buildSession()) {
      dbManager.saveBlockHeader(next);
      session.merge();
    }
    BlockCapsule stored = dbManager.getBlockStore().get(next.getBlockId().getBytes());
    Assert.assertTrue(stored.getTransactions().isEmpty());
    Assert.assertEquals(next.getInstance().getBlockHeader(), stored.getInstance().getBlockHeader());
    Assert.assertEquals(next.getBlockId(), stored.getBlockId());
    Assert.assertEquals(1, next.getTransactions().size());
    Assert.assertFalse(dbManager.getBlockIndexStore().has(ByteArray.fromLong(10)));
  }

  @Test
  public void genesisIdentityAllowsRestartWithoutGenesisHeader() {
    byte[] genesis = chainBaseManager.getGenesisBlockId().getBytes();
    dbManager.getBlockStore().delete(genesis);
    dbManager.getBlockIndexStore().delete(ByteArray.fromLong(0));
    BlockCapsule latest = block(Manager.HEADER_RETENTION_BLOCKS);
    dbManager.saveBlockHeader(latest);
    dbManager.getDynamicPropertiesStore().saveLatestBlockHeaderNumber(latest.getNum());
    dbManager.initGenesis();
    Assert.assertEquals(latest.getNum(), chainBaseManager.getHeadBlockNum());
    chainBaseManager.getTrackerStore().saveHeaderOnlyGenesisId(Sha256Hash.ZERO_HASH.getBytes());
    Assert.assertThrows(TronError.class, () -> dbManager.initGenesis());
    chainBaseManager.getTrackerStore().saveHeaderOnlyGenesisId(genesis);
  }

  @Test
  public void advertisedHeaderBoundaryFollowsRevokedHead() {
    long initial = chainBaseManager.getLowestBlockNum();
    try (ISession ignored = dbManager.getRevokingStore().buildSession()) {
      dbManager.getDynamicPropertiesStore()
          .saveLatestBlockHeaderNumber(Manager.HEADER_RETENTION_BLOCKS + 10);
      Assert.assertEquals(11, chainBaseManager.getLowestBlockNum());
      Assert.assertEquals(NodeType.LITE, chainBaseManager.getNodeType());
    }
    Assert.assertEquals(initial, chainBaseManager.getLowestBlockNum());
  }

  @Test
  public void transferUpdatesStateWithoutPersistingTransactionHistory() throws Exception {
    ByteString owner = ByteString.copyFrom(new ECKey().getAddress());
    ByteString recipient = ByteString.copyFrom(new ECKey().getAddress());
    dbManager.getAccountStore().put(owner.toByteArray(), new AccountCapsule(
        Protocol.Account.newBuilder().setAddress(owner).setBalance(100_000_000L).build()));
    dbManager.getAccountStore().put(recipient.toByteArray(), new AccountCapsule(
        Protocol.Account.newBuilder().setAddress(recipient).build()));
    TransactionCapsule transaction = new TransactionCapsule(TransferContract.newBuilder()
        .setOwnerAddress(owner).setToAddress(recipient).setAmount(1000).build(),
        ContractType.TransferContract);
    transaction.setBlockNum(1);
    BlockCapsule block = new BlockCapsule(1, chainBaseManager.getGenesisBlockId(),
        chainBaseManager.getGenesisBlock().getTimeStamp() + 3000, owner);
    Assert.assertNotNull(dbManager.processTransaction(transaction, block));
    Assert.assertEquals(1000,
        dbManager.getAccountStore().get(recipient.toByteArray()).getBalance());
    Assert.assertFalse(dbManager.getTransactionStore()
        .has(transaction.getTransactionId().getBytes()));
    Assert.assertFalse(dbManager.getTransactionRetStore().has(ByteArray.fromLong(1)));
    Assert.assertFalse(dbManager.getTransactionHistoryStore()
        .has(transaction.getTransactionId().getBytes()));
  }

  @Test
  public void doesNotServeStrippedHeaderAsCompleteBlock() throws Exception {
    BlockCapsule nonempty = block(100);
    dbManager.saveBlockHeader(nonempty);
    TronNetDelegate delegate = context.getBean(TronNetDelegate.class);
    Assert.assertThrows(P2pException.class,
        () -> delegate.getData(nonempty.getBlockId(), InventoryType.BLOCK));
    BlockCapsule empty = new BlockCapsule(101, Sha256Hash.ZERO_HASH, 3000, ByteString.EMPTY);
    empty.setMerkleRoot();
    dbManager.saveBlockHeader(empty);
    Assert.assertNotNull(delegate.getData(empty.getBlockId(), InventoryType.BLOCK));
  }
}
