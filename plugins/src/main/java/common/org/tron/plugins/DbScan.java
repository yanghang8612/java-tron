package org.tron.plugins;

import com.google.protobuf.InvalidProtocolBufferException;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.tron.plugins.utils.Base58Check;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.Sha256Hash;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;
import picocli.CommandLine;

@Slf4j(topic = "db-scan")
@CommandLine.Command(name = "scan",
    description = "Scan a stopped fullnode DB and dump all successful TriggerSmartContract"
        + " calls to a target contract as CSV.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "1:Internal error, see toolkit.log",
        "2:Bad arguments"})
public class DbScan implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;

  @CommandLine.Option(names = {"--db-path"}, required = true,
      description = "Path to the node's database directory, e.g. output-directory/database")
  private Path dbPath;

  @CommandLine.Option(names = {"--contract"}, required = true,
      description = "Target contract address in base58check, e.g. TWjkoz...")
  private String contract;

  @CommandLine.Option(names = {"--start"}, defaultValue = "0",
      description = "Start block number, inclusive. Default: ${DEFAULT-VALUE}")
  private long start;

  @CommandLine.Option(names = {"--end"}, defaultValue = "-1",
      description = "End block number, inclusive. -1 means auto-detect head from block-index.")
  private long end;

  @CommandLine.Option(names = {"--out"},
      description = "Output CSV path. Default: scan-<contract>-<yyyymmdd-HHmmss>.csv")
  private Path out;

  @CommandLine.Option(names = {"-h", "--help"}, help = true,
      description = "display a help message")
  private boolean help;

  @Override
  public Integer call() {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }

    byte[] target;
    try {
      target = Base58Check.decode58Check(contract);
    } catch (IllegalArgumentException e) {
      err("invalid --contract: " + e.getMessage());
      return 2;
    }
    if (target.length != 21) {
      err("invalid --contract: expected 21-byte address, got " + target.length);
      return 2;
    }
    if (!dbPath.toFile().isDirectory()) {
      err("--db-path does not exist: " + dbPath);
      return 2;
    }
    if (!dbPath.resolve("block-index").toFile().isDirectory()) {
      err("--db-path is missing block-index sub-directory: " + dbPath);
      return 2;
    }
    if (!dbPath.resolve("block").toFile().isDirectory()) {
      err("--db-path is missing block sub-directory: " + dbPath);
      return 2;
    }
    if (out == null) {
      String stamp = LocalDateTime.now()
          .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
      out = Paths.get("scan-" + contract + "-" + stamp + ".csv");
    }

    long startNum = start;
    long endNum = end;

    try (DBInterface blockIndex = DbTool.getDB(dbPath, "block-index");
         DBInterface blocks = DbTool.getDB(dbPath, "block");
         DBInterface history = DbTool.getDB(dbPath, "transactionHistoryStore")) {

      long head = resolveHead(blockIndex);
      if (endNum < 0) {
        if (head < 0) {
          err("block-index is empty; cannot auto-derive --end");
          return 2;
        }
        endNum = head;
      } else if (head >= 0 && endNum > head) {
        logger.warn("--end={} exceeds head={}; will skip {} blocks above head",
            endNum, head, endNum - head);
      }
      if (startNum < 0 || startNum > endNum) {
        err("invalid range: start=" + startNum + " end=" + endNum);
        return 2;
      }

      java.nio.file.Path parent = out.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      try (BufferedWriter csv = new BufferedWriter(
          new OutputStreamWriter(Files.newOutputStream(out), StandardCharsets.UTF_8))) {
        csv.write("block_num,timestamp,txid,caller,call_value,call_token_value,token_id,"
            + "data_hex,energy_usage_total,energy_fee,net_usage,receipt_result");
        csv.newLine();
        long hits = scanRange(blockIndex, blocks, history, target, startNum, endNum, csv);
        csv.flush();
        logger.info("done blocks={} hits={} output={}",
            endNum - startNum + 1, hits, out);
      }
    } catch (Exception e) {
      logger.error("scan failed", e);
      return 1;
    } finally {
      try {
        DbTool.close();
      } catch (Exception ignore) {
        // best-effort
      }
    }
    return 0;
  }

  private long scanRange(DBInterface blockIndex, DBInterface blocks, DBInterface history,
                         byte[] target, long startNum, long endNum, BufferedWriter csv)
      throws IOException {
    long hits = 0;
    long t0 = System.currentTimeMillis();
    for (long num = startNum; num <= endNum; num++) {
      byte[] idBytes = blockIndex.get(ByteArray.fromLong(num));
      if (idBytes == null) {
        logger.warn("block-index missing for num={}, skipping", num);
        continue;
      }
      byte[] blockBytes = blocks.get(idBytes);
      if (blockBytes == null) {
        logger.warn("block missing for num={}, skipping", num);
        continue;
      }
      Protocol.Block block;
      try {
        block = Protocol.Block.parseFrom(blockBytes);
      } catch (InvalidProtocolBufferException e) {
        logger.error("block {} failed to parse, skipping: {}", num, e.getMessage());
        continue;
      }
      long ts = block.getBlockHeader().getRawData().getTimestamp();
      for (Protocol.Transaction tx : block.getTransactionsList()) {
        Match m = matchTransaction(tx, target);
        if (m == null) {
          continue;
        }
        byte[] txid = Sha256Hash.hash(true, tx.getRawData().toByteArray());
        writeRow(csv, num, ts, txid, m, lookupInfo(history, txid));
        hits++;
      }
      if (num % 10_000L == 0L && num > startNum) {
        long elapsedMs = System.currentTimeMillis() - t0;
        logger.info("scanned={}/{} hit={} elapsed={}ms", num, endNum, hits, elapsedMs);
      }
    }
    return hits;
  }

  private static Protocol.TransactionInfo lookupInfo(DBInterface history, byte[] txid) {
    byte[] raw = history.get(txid);
    if (raw == null) {
      return null;
    }
    try {
      return Protocol.TransactionInfo.parseFrom(raw);
    } catch (InvalidProtocolBufferException e) {
      logger.warn("malformed TransactionInfo for txid {}: {}",
          ByteArray.toHexString(txid), e.getMessage());
      return null;
    }
  }

  private static void writeRow(BufferedWriter csv, long num, long ts, byte[] txid,
                               Match m, Protocol.TransactionInfo info) throws IOException {
    String energyUsageTotal = info == null ? ""
        : Long.toString(info.getReceipt().getEnergyUsageTotal());
    String energyFee = info == null ? "" : Long.toString(info.getReceipt().getEnergyFee());
    String netUsage = info == null ? "" : Long.toString(info.getReceipt().getNetUsage());
    String receiptResult = info == null ? "" : info.getReceipt().getResult().name();
    csv.write(num + "," + ts + "," + ByteArray.toHexString(txid) + ","
        + Base58Check.encode58Check(m.caller) + ","
        + m.callValue + "," + m.callTokenValue + "," + m.tokenId + ","
        + ByteArray.toHexString(m.data) + ","
        + energyUsageTotal + "," + energyFee + "," + netUsage + ","
        + receiptResult);
    csv.newLine();
  }

  private long resolveHead(DBInterface blockIndex) throws IOException {
    try (DBIterator it = blockIndex.iterator()) {
      it.seekToLast();
      if (!it.valid()) {
        return -1L;
      }
      byte[] key = it.getKey();
      if (key == null || key.length != 8) {
        return -1L;
      }
      long n = 0L;
      for (int i = 0; i < 8; i++) {
        n = (n << 8) | (key[i] & 0xffL);
      }
      return n;
    }
  }

  private void err(String msg) {
    spec.commandLine().getErr().println(
        spec.commandLine().getColorScheme().errorText(msg));
  }

  /** Value object: one successful Trigger row before TransactionInfo enrichment. */
  static final class Match {
    final byte[] caller;
    final long callValue;
    final long callTokenValue;
    final long tokenId;
    final byte[] data;

    Match(byte[] caller, long callValue, long callTokenValue, long tokenId, byte[] data) {
      this.caller = caller;
      this.callValue = callValue;
      this.callTokenValue = callTokenValue;
      this.tokenId = tokenId;
      this.data = data;
    }
  }

  /**
   * Iterates Contract entries in order, skipping non-TriggerSmartContract types and Triggers
   * whose contract_address differs from {@code target}. On the first Trigger whose address
   * matches: returns a Match if its ret entry is SUCCESS, otherwise returns null. Returns
   * null if no Trigger matching {@code target} exists.
   */
  static Match matchTransaction(Protocol.Transaction tx, byte[] target) {
    if (tx.getRawData().getContractCount() == 0) {
      return null;
    }
    for (int i = 0; i < tx.getRawData().getContractCount(); i++) {
      Protocol.Transaction.Contract c = tx.getRawData().getContract(i);
      if (c.getType() != Protocol.Transaction.Contract.ContractType.TriggerSmartContract) {
        continue;
      }
      TriggerSmartContract trigger;
      try {
        trigger = c.getParameter().unpack(TriggerSmartContract.class);
      } catch (InvalidProtocolBufferException e) {
        logger.warn("malformed TriggerSmartContract parameter, skipping: {}", e.getMessage());
        return null;
      }
      if (!Arrays.equals(trigger.getContractAddress().toByteArray(), target)) {
        continue;
      }
      if (i >= tx.getRetCount()) {
        return null;
      }
      if (tx.getRet(i).getContractRet()
          != Protocol.Transaction.Result.contractResult.SUCCESS) {
        return null;
      }
      return new Match(
          trigger.getOwnerAddress().toByteArray(),
          trigger.getCallValue(),
          trigger.getCallTokenValue(),
          trigger.getTokenId(),
          trigger.getData().toByteArray());
    }
    return null;
  }
}
