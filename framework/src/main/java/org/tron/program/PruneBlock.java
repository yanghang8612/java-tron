package org.tron.program;

import com.google.common.primitives.Longs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db.BlockIndexStore;
import org.tron.core.db.BlockStore;
import org.tron.core.db.NewBlockIndexStore;
import org.tron.core.db.NewBlockStore;
import org.tron.core.db.NewTransactionStore;
import org.tron.core.db.TransactionStore;
import org.tron.core.exception.BadItemException;
import org.tron.core.exception.ItemNotFoundException;
import org.tron.core.store.DynamicPropertiesStore;

@Component
@Slf4j(topic = "DB")
public class PruneBlock {

  public static final long MIN = (1<<16) + 1000;

  @Autowired
  BlockStore blockStore;
  @Autowired
  NewBlockStore newBlockStore;
  @Autowired
  BlockIndexStore blockIndexStore;
  @Autowired
  NewBlockIndexStore newBlockIndexStore;
  @Autowired
  TransactionStore transactionStore;
  @Autowired
  NewTransactionStore newTransactionStore;

  @Autowired
  DynamicPropertiesStore dynamicPropertiesStore;

  public void pruneAll() {
    long blockNumber = dynamicPropertiesStore.getLatestBlockHeaderNumber();
    if (blockNumber == 0) {
      BlockCapsule blockCapsule = blockStore.getBlockByLatestNum(1).get(0);
      dynamicPropertiesStore.saveLatestBlockHeaderNumber(blockCapsule.getNum());
      dynamicPropertiesStore.saveLatestBlockHeaderHash(blockCapsule.getBlockId().getByteString());
      dynamicPropertiesStore.saveLatestBlockHeaderTimestamp(blockCapsule.getTimeStamp());
      blockNumber = blockCapsule.getNum();
    }

    long min = blockNumber - MIN;

    for (long index = 0; index <= blockNumber; index++) {
      if (index < 0) {
        continue;
      }

      try {
        BlockCapsule.BlockId blockId = blockIndexStore.get(index);
        BlockCapsule block = blockStore.get(blockId.getBytes());
        newBlockStore.put(blockId.getBytes(), block);
        newBlockIndexStore.put(blockId);
        block.getTransactions().forEach(t -> newTransactionStore.put(t.getTransactionId().getBytes(), t));
        logger.info("prune block {}, {} done", index, blockId);
      } catch (ItemNotFoundException | BadItemException e) {
        logger.error(e.getMessage());
      }

      if (index == 0) {
        index = min;
      }
    }
  }

  public void prune(BlockCapsule block) {
    long blockNumber = block.getNum();
    long min = blockNumber - MIN;
    if (min <= 0) {
      return;
    }

    try {
      BlockCapsule.BlockId blockId = blockIndexStore.get(min);
      BlockCapsule minBlock = blockStore.get(blockId.getBytes());
      delete(minBlock);
      logger.info("prune block {}, {} done", min, blockId);
    } catch (ItemNotFoundException | BadItemException e) {
      logger.error(e.getMessage());
    }
  }

  public void delete(BlockCapsule block) {
    if (block == null) {
      return;
    }

    for (TransactionCapsule transaction : block.getTransactions()) {
      Sha256Hash transactionId = transaction.getTransactionId();
      transactionStore.delete(transactionId.getBytes());
    }

    blockStore.delete(block.getBlockId().getBytes());

    blockIndexStore.delete(Longs.toByteArray(block.getNum()));
  }

}