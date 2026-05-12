package org.tron.plugins;

import com.google.protobuf.InvalidProtocolBufferException;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.tron.plugins.utils.Base58Check;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;

@Slf4j(topic = "db-approve")
@CommandLine.Command(name = "approve",
    description = "Scan a stopped fullnode DB for TRC20 Approval events on a token whose"
        + " indexed spender matches the given address; emit unique owners with their latest"
        + " allowance as CSV.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "1:Internal error, see toolkit.log",
        "2:Bad arguments"})
public class DbApprove implements Callable<Integer> {

  // keccak256("Approval(address,address,uint256)")
  private static final byte[] APPROVAL_TOPIC0 = ByteArray.fromHexString(
      "8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925");

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;

  @CommandLine.Option(names = {"--db-path"}, required = true,
      description = "Path to the node's database directory, e.g. output-directory/database")
  private Path dbPath;

  @CommandLine.Option(names = {"--token"}, required = true,
      description = "TRC20 token contract address in base58check, e.g."
          + " TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t for USDT")
  private String token;

  @CommandLine.Option(names = {"--spender"}, required = true,
      description = "Spender address in base58check whose Approval events to collect")
  private String spender;

  @CommandLine.Option(names = {"--start"}, defaultValue = "0",
      description = "Start block number, inclusive. Default: ${DEFAULT-VALUE}")
  private long start;

  @CommandLine.Option(names = {"--end"}, defaultValue = "-1",
      description = "End block number, inclusive. -1 means auto-detect head from"
          + " transactionRetStore.")
  private long end;

  @CommandLine.Option(names = {"--include-zero"},
      description = "Include owners whose latest approval is 0 (revoked).")
  private boolean includeZero;

  @CommandLine.Option(names = {"--out"},
      description = "Output CSV path. Default: approvers-<spender>-<yyyymmdd-HHmmss>.csv")
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

    byte[] tokenAddr20 = decode20(token, "--token");
    if (tokenAddr20 == null) {
      return 2;
    }
    byte[] spenderAddr20 = decode20(spender, "--spender");
    if (spenderAddr20 == null) {
      return 2;
    }
    if (!dbPath.toFile().isDirectory()) {
      err("--db-path does not exist: " + dbPath);
      return 2;
    }
    if (!dbPath.resolve("transactionRetStore").toFile().isDirectory()) {
      err("--db-path is missing transactionRetStore sub-directory: " + dbPath);
      return 2;
    }
    if (out == null) {
      String stamp = LocalDateTime.now()
          .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
      out = Paths.get("approvers-" + spender + "-" + stamp + ".csv");
    }

    Map<String, Approval> latestByOwner = new HashMap<>();
    long startNum = start;
    long endNum = end;

    try (DBInterface retStore = DbTool.getDB(dbPath, "transactionRetStore")) {
      long head = resolveHead(retStore);
      if (endNum < 0) {
        if (head < 0) {
          err("transactionRetStore is empty; cannot auto-derive --end");
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

      long scanned = scanRange(retStore, tokenAddr20, spenderAddr20,
          startNum, endNum, latestByOwner);
      logger.info("scanned blocks={} unique owners (incl. zero)={}",
          scanned, latestByOwner.size());

      Path parent = out.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      writeCsv(latestByOwner);
      logger.info("output={}", out);
    } catch (Exception e) {
      logger.error("approve scan failed", e);
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

  private long scanRange(DBInterface retStore, byte[] tokenAddr20, byte[] spenderAddr20,
                         long startNum, long endNum, Map<String, Approval> latestByOwner) {
    long t0 = System.currentTimeMillis();
    long scanned = 0;
    for (long num = startNum; num <= endNum; num++) {
      byte[] raw = retStore.get(ByteArray.fromLong(num));
      scanned++;
      if (raw == null) {
        continue;
      }
      Protocol.TransactionRet ret;
      try {
        ret = Protocol.TransactionRet.parseFrom(raw);
      } catch (InvalidProtocolBufferException e) {
        logger.warn("block {} TransactionRet malformed, skipping: {}", num, e.getMessage());
        continue;
      }
      for (Protocol.TransactionInfo info : ret.getTransactioninfoList()) {
        if (info.getResult() != Protocol.TransactionInfo.code.SUCESS) {
          continue;
        }
        List<Protocol.TransactionInfo.Log> logs = info.getLogList();
        for (int i = 0; i < logs.size(); i++) {
          Protocol.TransactionInfo.Log log = logs.get(i);
          if (!matches(log, tokenAddr20, spenderAddr20)) {
            continue;
          }
          byte[] ownerTopic = log.getTopics(1).toByteArray();
          byte[] ownerAddr20 = Arrays.copyOfRange(ownerTopic, 12, 32);
          String ownerKey = ByteArray.toHexString(ownerAddr20);
          BigInteger amount = new BigInteger(1, log.getData().toByteArray());
          Approval prev = latestByOwner.get(ownerKey);
          if (prev == null || num > prev.block
              || (num == prev.block && i > prev.logIndex)) {
            latestByOwner.put(ownerKey, new Approval(
                ownerAddr20, num, i, info.getId().toByteArray(), amount));
          }
        }
      }
      if (num % 100_000L == 0L && num > startNum) {
        long elapsedMs = System.currentTimeMillis() - t0;
        logger.info("scanned={}/{} owners={} elapsed={}ms",
            num, endNum, latestByOwner.size(), elapsedMs);
      }
    }
    return scanned;
  }

  private static boolean matches(Protocol.TransactionInfo.Log log,
                                 byte[] tokenAddr20, byte[] spenderAddr20) {
    if (!Arrays.equals(log.getAddress().toByteArray(), tokenAddr20)) {
      return false;
    }
    if (log.getTopicsCount() != 3) {
      return false;
    }
    if (!Arrays.equals(log.getTopics(0).toByteArray(), APPROVAL_TOPIC0)) {
      return false;
    }
    byte[] spenderTopic = log.getTopics(2).toByteArray();
    if (spenderTopic.length != 32) {
      return false;
    }
    for (int i = 0; i < 12; i++) {
      if (spenderTopic[i] != 0) {
        return false;
      }
    }
    for (int i = 0; i < 20; i++) {
      if (spenderTopic[12 + i] != spenderAddr20[i]) {
        return false;
      }
    }
    return true;
  }

  private void writeCsv(Map<String, Approval> latestByOwner) throws IOException {
    List<Approval> rows = latestByOwner.values().stream()
        .filter(a -> includeZero || a.amount.signum() != 0)
        .sorted((a, b) -> Long.compare(b.block, a.block))
        .collect(Collectors.toList());
    try (BufferedWriter csv = new BufferedWriter(
        new OutputStreamWriter(Files.newOutputStream(out), StandardCharsets.UTF_8))) {
      csv.write("owner_tron,owner_hex,latest_block,latest_txid,latest_amount");
      csv.newLine();
      for (Approval a : rows) {
        byte[] tronAddr21 = new byte[21];
        tronAddr21[0] = 0x41;
        System.arraycopy(a.ownerAddr20, 0, tronAddr21, 1, 20);
        csv.write(Base58Check.encode58Check(tronAddr21) + ","
            + "0x" + ByteArray.toHexString(a.ownerAddr20) + ","
            + a.block + ","
            + ByteArray.toHexString(a.txid) + ","
            + a.amount.toString());
        csv.newLine();
      }
      csv.flush();
    }
    logger.info("rows emitted={} (filter zero={})", rows.size(), !includeZero);
  }

  private byte[] decode20(String addr, String optName) {
    byte[] decoded;
    try {
      decoded = Base58Check.decode58Check(addr);
    } catch (IllegalArgumentException e) {
      err("invalid " + optName + ": " + e.getMessage());
      return null;
    }
    if (decoded.length != 21) {
      err("invalid " + optName + ": expected 21-byte address, got " + decoded.length);
      return null;
    }
    return Arrays.copyOfRange(decoded, 1, 21);
  }

  private long resolveHead(DBInterface retStore) throws IOException {
    try (DBIterator it = retStore.iterator()) {
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

  private static final class Approval {
    final byte[] ownerAddr20;
    final long block;
    final int logIndex;
    final byte[] txid;
    final BigInteger amount;

    Approval(byte[] ownerAddr20, long block, int logIndex, byte[] txid, BigInteger amount) {
      this.ownerAddr20 = ownerAddr20;
      this.block = block;
      this.logIndex = logIndex;
      this.txid = txid;
      this.amount = amount;
    }
  }
}
