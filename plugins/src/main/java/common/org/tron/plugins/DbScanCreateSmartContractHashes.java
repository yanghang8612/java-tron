package org.tron.plugins;

import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.fusesource.leveldbjni.JniDBFactory;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.ReadOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;
import org.tron.common.crypto.Hash;
import org.tron.plugins.utils.Base58Check;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.DBUtils;
import org.tron.plugins.utils.Sha256Hash;
import org.tron.protos.Protocol.Block;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.contract.SmartContractOuterClass.CreateSmartContract;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;
import picocli.CommandLine;

@CommandLine.Command(name = "scan-create-contract-hashes",
    description = "Scan every transaction in the block database and report whether "
        + "CreateSmartContract.new_contract.trx_hash/code_hash have values.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "1:Invalid arguments or database error",
        "2:Scan completed, but malformed block/contract records were found"})
public class DbScanCreateSmartContractHashes implements Callable<Integer> {

  private static final String BLOCK_DB = "block";
  private static final String BLOCK_INDEX_DB = "block-index";
  private static final String STDOUT = "-";
  private static final String REPORT_HEADER = "block_number\ttransaction_id\tcontract_index\t"
      + "contract_address_base58\tcontract_address_hex\ttrx_hash_present\ttrx_hash\t"
      + "code_hash_present\tcode_hash\tstate\tparse_error";
  private static final int MAX_AUTO_THREADS = 8;
  private static final int MAX_SCAN_THREADS = 256;
  private static final AtomicInteger WORKER_ID = new AtomicInteger();

  @CommandLine.Spec
  private CommandLine.Model.CommandSpec spec;

  @CommandLine.Parameters(index = "0", defaultValue = "output-directory/database",
      description = "Database parent path containing the block database. "
          + "Default: ${DEFAULT-VALUE}")
  private Path databaseDirectory;

  @CommandLine.Option(names = {"-o", "--output"}, defaultValue = STDOUT,
      description = "TSV report path, or '-' for stdout. Default: ${DEFAULT-VALUE}")
  private String output;

  @CommandLine.Option(names = {"--progress", "--progress-interval-seconds"},
      defaultValue = "10",
      description = "Print progress every N seconds; 0 disables it. "
          + "Default: ${DEFAULT-VALUE}")
  private int progressIntervalSeconds;

  @CommandLine.Option(names = "--threads", defaultValue = "0",
      description = "Number of independent height-range iterators; 0 selects up to 8 based on "
          + "available CPUs. Default: ${DEFAULT-VALUE}")
  private int requestedThreads;

  private long scanStartMillis;
  private long nextProgressMillis;
  private long expectedHeights;
  private int scanThreads;
  private int activeShards;

  @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true,
      description = "Display a help message")
  private boolean help;

  @Override
  public Integer call() {
    Path blockDbPath = databaseDirectory.resolve(BLOCK_DB);
    Path blockIndexDbPath = databaseDirectory.resolve(BLOCK_INDEX_DB);
    if (!Files.isDirectory(blockDbPath)) {
      return fail(String.format("Block database does not exist: %s", blockDbPath));
    }
    if (!Files.isDirectory(blockIndexDbPath)) {
      return fail(String.format("Block index database does not exist: %s", blockIndexDbPath));
    }
    if (progressIntervalSeconds < 0) {
      return fail("--progress must be greater than or equal to 0 seconds");
    }
    if (requestedThreads < 0 || requestedThreads > MAX_SCAN_THREADS) {
      return fail(String.format("--threads must be between 0 and %d", MAX_SCAN_THREADS));
    }
    scanThreads = requestedThreads == 0
        ? Math.max(1, Math.min(MAX_AUTO_THREADS, Runtime.getRuntime().availableProcessors()))
        : requestedThreads;

    try {
      DbEngine engine = detectEngine(blockDbPath);
      DbEngine indexEngine = detectEngine(blockIndexDbPath);
      if (engine != indexEngine) {
        return fail(String.format("Database engine mismatch: %s=%s, %s=%s",
            blockDbPath, engine, blockIndexDbPath, indexEngine));
      }
      ScanStats stats;
      if (STDOUT.equals(output)) {
        PrintWriter writer = spec.commandLine().getOut();
        stats = scan(blockDbPath, blockIndexDbPath, engine, writer);
        writer.flush();
      } else {
        Path reportPath = Paths.get(output).toAbsolutePath().normalize();
        Path parent = reportPath.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(reportPath,
            StandardCharsets.UTF_8))) {
          stats = scan(blockDbPath, blockIndexDbPath, engine, writer);
        }
        spec.commandLine().getErr().format("Report: %s%n", reportPath);
      }
      printSummary(stats, engine, blockDbPath);
      return stats.hasParseErrors() ? 2 : 0;
    } catch (Exception e) {
      return fail(String.format("Scan failed: %s", rootMessage(e)));
    }
  }

  private ScanStats scan(Path blockDbPath, Path blockIndexDbPath, DbEngine engine,
      PrintWriter writer) throws Exception {
    writer.println(REPORT_HEADER);
    writer.flush();
    ScanStats stats;
    switch (engine) {
      case LEVELDB:
        stats = scanLevelDb(blockDbPath, blockIndexDbPath, engine, writer);
        break;
      case ROCKSDB:
        stats = scanRocksDb(blockDbPath, blockIndexDbPath, engine, writer);
        break;
      default:
        throw new IllegalStateException("Unsupported database engine: " + engine);
    }
    if (writer.checkError()) {
      throw new IOException("Failed to write the report");
    }
    return stats;
  }

  private ScanStats scanLevelDb(Path blockDbPath, Path blockIndexDbPath, DbEngine engine,
      PrintWriter writer) throws Exception {
    org.iq80.leveldb.Options blockOptions = DBUtils.newDefaultLevelDbOptions()
        .createIfMissing(false);
    org.iq80.leveldb.Options indexOptions = DBUtils.newDefaultLevelDbOptions()
        .createIfMissing(false);
    try (DB blockDatabase = JniDBFactory.factory.open(blockDbPath.toFile(), blockOptions);
        DB indexDatabase = JniDBFactory.factory.open(blockIndexDbPath.toFile(), indexOptions)) {
      HeightRange fullRange = levelDbHeightRange(indexDatabase);
      return scanShards(fullRange, engine, blockDbPath, blockIndexDbPath, writer,
          (range, stats, failure) -> scanLevelDbShard(indexDatabase, blockDatabase, range,
              writer, stats, failure));
    }
  }

  private ScanStats scanRocksDb(Path blockDbPath, Path blockIndexDbPath, DbEngine engine,
      PrintWriter writer) throws Exception {
    RocksDB.loadLibrary();
    try (Options blockOptions = DBUtils.newDefaultRocksDbOptions(false, BLOCK_DB)
            .setCreateIfMissing(false);
        Options indexOptions = DBUtils.newDefaultRocksDbOptions(false, BLOCK_INDEX_DB)
            .setCreateIfMissing(false);
        RocksDB blockDatabase = RocksDB.openReadOnly(blockOptions, blockDbPath.toString());
        RocksDB indexDatabase = RocksDB.openReadOnly(indexOptions, blockIndexDbPath.toString())) {
      HeightRange fullRange = rocksDbHeightRange(indexDatabase);
      return scanShards(fullRange, engine, blockDbPath, blockIndexDbPath, writer,
          (range, stats, failure) -> scanRocksDbShard(indexDatabase, blockDatabase, range,
              writer, stats, failure));
    }
  }

  private ScanStats scanShards(HeightRange fullRange, DbEngine engine, Path blockDbPath,
      Path blockIndexDbPath, PrintWriter writer, ShardScanner scanner) throws Exception {
    ScanStats stats = new ScanStats();
    if (fullRange == null) {
      activeShards = 0;
      expectedHeights = 0;
      startProgress(engine, blockDbPath, blockIndexDbPath, null);
      return stats;
    }
    List<HeightRange> ranges = splitHeightRange(fullRange, scanThreads);
    activeShards = ranges.size();
    expectedHeights = fullRange.size();
    startProgress(engine, blockDbPath, blockIndexDbPath, fullRange);
    for (int i = 0; i < ranges.size(); i++) {
      HeightRange range = ranges.get(i);
      spec.commandLine().getErr().format("Shard %d/%d: start_height=%,d, end_height=%,d%n",
          i + 1, ranges.size(), range.start, range.end);
    }

    ThreadPoolExecutor executor = newScanExecutor();
    AtomicReference<Throwable> workerFailure = new AtomicReference<>();
    try {
      for (HeightRange range : ranges) {
        executor.execute(() -> {
          if (workerFailure.get() != null) {
            return;
          }
          try {
            scanner.scan(range, stats, workerFailure);
            stats.completedShards.increment();
          } catch (Throwable t) {
            workerFailure.compareAndSet(null, t);
          }
        });
      }
      return awaitWorkers(executor, workerFailure, writer, stats);
    } catch (Exception | Error e) {
      executor.shutdownNow();
      throw e;
    }
  }

  private ThreadPoolExecutor newScanExecutor() {
    int queueCapacity = Math.max(16, activeShards);
    ThreadFactory threadFactory = task -> {
      Thread thread = new Thread(task, "create-contract-scan-" + WORKER_ID.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
    return new ThreadPoolExecutor(activeShards, activeShards, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(queueCapacity), threadFactory,
        new ThreadPoolExecutor.CallerRunsPolicy());
  }

  private void scanLevelDbShard(DB indexDatabase, DB blockDatabase, HeightRange range,
      PrintWriter writer, ScanStats stats, AtomicReference<Throwable> workerFailure)
      throws Exception {
    ReadOptions indexReadOptions = new ReadOptions().fillCache(false);
    ReadOptions blockReadOptions = new ReadOptions().fillCache(false);
    try (DBIterator iterator = indexDatabase.iterator(indexReadOptions)) {
      iterator.seek(ByteArray.fromLong(range.start));
      while (iterator.hasNext() && workerFailure.get() == null) {
        Map.Entry<byte[], byte[]> entry = iterator.next();
        long height = decodeHeight(entry.getKey());
        if (height > range.end) {
          break;
        }
        scanIndexedBlock(height, entry.getValue(), blockDatabase.get(entry.getValue(),
            blockReadOptions), writer, stats);
      }
    }
  }

  private void scanRocksDbShard(RocksDB indexDatabase, RocksDB blockDatabase,
      HeightRange range, PrintWriter writer, ScanStats stats,
      AtomicReference<Throwable> workerFailure) throws Exception {
    try (org.rocksdb.ReadOptions indexReadOptions =
             new org.rocksdb.ReadOptions().setFillCache(false);
        org.rocksdb.ReadOptions blockReadOptions =
            new org.rocksdb.ReadOptions().setFillCache(false);
        RocksIterator iterator = indexDatabase.newIterator(indexReadOptions)) {
      for (iterator.seek(ByteArray.fromLong(range.start));
          iterator.isValid() && workerFailure.get() == null; iterator.next()) {
        byte[] heightKey = iterator.key();
        long height = decodeHeight(heightKey);
        if (height > range.end) {
          break;
        }
        byte[] blockId = iterator.value();
        scanIndexedBlock(height, blockId, blockDatabase.get(blockReadOptions, blockId),
            writer, stats);
      }
    }
  }

  private static HeightRange levelDbHeightRange(DB indexDatabase) throws IOException {
    try (DBIterator iterator = indexDatabase.iterator(new ReadOptions().fillCache(false))) {
      iterator.seekToFirst();
      if (!iterator.hasNext()) {
        return null;
      }
      long start = decodeHeight(iterator.peekNext().getKey());
      iterator.seekToLast();
      if (!iterator.hasNext()) {
        return null;
      }
      return new HeightRange(start, decodeHeight(iterator.peekNext().getKey()));
    }
  }

  private static HeightRange rocksDbHeightRange(RocksDB indexDatabase) throws IOException {
    try (org.rocksdb.ReadOptions readOptions =
             new org.rocksdb.ReadOptions().setFillCache(false);
        RocksIterator iterator = indexDatabase.newIterator(readOptions)) {
      iterator.seekToFirst();
      if (!iterator.isValid()) {
        return null;
      }
      long start = decodeHeight(iterator.key());
      iterator.seekToLast();
      if (!iterator.isValid()) {
        return null;
      }
      return new HeightRange(start, decodeHeight(iterator.key()));
    }
  }

  private static long decodeHeight(byte[] key) throws IOException {
    if (key == null || key.length != Long.BYTES) {
      throw new IOException(String.format("Invalid block-index key length: %d",
          key == null ? 0 : key.length));
    }
    return ByteArray.toLong(key);
  }

  private static List<HeightRange> splitHeightRange(HeightRange fullRange, int threads) {
    long total = fullRange.size();
    int shardCount = (int) Math.min(total, threads);
    long baseSize = total / shardCount;
    long remainder = total % shardCount;
    List<HeightRange> ranges = new ArrayList<>(shardCount);
    long start = fullRange.start;
    for (int i = 0; i < shardCount; i++) {
      long size = baseSize + (i < remainder ? 1 : 0);
      long end = start + size - 1;
      ranges.add(new HeightRange(start, end));
      start = end + 1;
    }
    return ranges;
  }

  private static void scanIndexedBlock(long height, byte[] blockId, byte[] blockValue,
      PrintWriter writer, ScanStats stats) {
    stats.databaseEntries.increment();
    if (blockValue == null) {
      stats.missingBlocks.increment();
      System.err.format("Missing block record: height=%d, block_id=%s%n", height,
          ByteArray.toHexString(blockId));
      return;
    }
    scanEntry(blockId, blockValue, writer, stats);
  }

  private ScanStats awaitWorkers(ThreadPoolExecutor executor,
      AtomicReference<Throwable> workerFailure, PrintWriter writer, ScanStats stats)
      throws Exception {
    executor.shutdown();
    while (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
      reportProgress(stats, writer);
      if (workerFailure.get() != null) {
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        throwIfWorkerFailed(workerFailure);
      }
    }
    throwIfWorkerFailed(workerFailure);
    return stats;
  }

  private static void throwIfWorkerFailed(AtomicReference<Throwable> workerFailure)
      throws Exception {
    Throwable failure = workerFailure.get();
    if (failure == null) {
      return;
    }
    if (failure instanceof Exception) {
      throw (Exception) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    throw new RuntimeException(failure);
  }

  private void reportProgress(ScanStats stats, PrintWriter writer) throws IOException {
    if (progressIntervalSeconds == 0) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now >= nextProgressMillis) {
      synchronized (writer) {
        writer.flush();
        if (writer.checkError()) {
          throw new IOException("Failed to write the report");
        }
      }
      double elapsedSeconds = elapsedSeconds(now);
      double entriesPerSecond = stats.databaseEntries.sum() / elapsedSeconds;
      long bothSet = stats.bothSet.sum();
      long trxHashOnly = stats.trxHashOnly.sum();
      long codeHashOnly = stats.codeHashOnly.sum();
      long trxHashPresent = bothSet + trxHashOnly;
      long codeHashPresent = bothSet + codeHashOnly;
      long eitherHashPresent = bothSet + trxHashOnly + codeHashOnly;
      spec.commandLine().getErr().format(Locale.ROOT,
          "Progress: elapsed=%.1fs, entries=%,d/%,d, shards_completed=%,d/%,d, "
              + "blocks=%,d, transactions=%,d, "
              + "create_contracts=%,d, trx_hash_present=%,d, code_hash_present=%,d, "
              + "either_hash_present=%,d, both_set=%,d, trx_hash_only=%,d, "
              + "code_hash_only=%,d, both_empty=%,d, rate=%,.1f entries/s%n",
          elapsedSeconds, stats.databaseEntries.sum(), expectedHeights,
          stats.completedShards.sum(), activeShards, stats.blocks.sum(),
          stats.transactions.sum(), stats.createContracts.sum(), trxHashPresent,
          codeHashPresent, eitherHashPresent, bothSet, trxHashOnly, codeHashOnly,
          stats.bothEmpty.sum(), entriesPerSecond);
      nextProgressMillis = now + progressIntervalSeconds * 1000L;
    }
  }

  private void startProgress(DbEngine engine, Path blockDbPath, Path blockIndexDbPath,
      HeightRange fullRange) {
    scanStartMillis = System.currentTimeMillis();
    nextProgressMillis = scanStartMillis + progressIntervalSeconds * 1000L;
    String heights = fullRange == null ? "empty"
        : String.format(Locale.ROOT, "%,d..%,d", fullRange.start, fullRange.end);
    spec.commandLine().getErr().format(
        "Scan started: engine=%s, block_db=%s, block_index_db=%s, height_range=%s, "
            + "shards=%d, progress_interval=%ds%n",
        engine, blockDbPath, blockIndexDbPath, heights, activeShards,
        progressIntervalSeconds);
  }

  private double elapsedSeconds(long nowMillis) {
    return Math.max(1L, nowMillis - scanStartMillis) / 1000.0;
  }

  static void scanEntry(byte[] key, byte[] value, PrintWriter writer, ScanStats stats) {
    try {
      Block block = Block.parseFrom(value);
      stats.blocks.increment();
      scanBlock(block, writer, stats);
    } catch (InvalidProtocolBufferException e) {
      stats.malformedBlocks.increment();
      System.err.format("Cannot parse block record key=%s: %s%n",
          ByteArray.toHexString(key), sanitize(e.getMessage()));
    }
  }

  static void scanBlock(Block block, PrintWriter writer, ScanStats stats) {
    long blockNumber = block.getBlockHeader().getRawData().getNumber();
    for (Transaction transaction : block.getTransactionsList()) {
      stats.transactions.increment();
      String transactionId = null;
      Sha256Hash transactionHash = null;
      for (int i = 0; i < transaction.getRawData().getContractCount(); i++) {
        Contract contract = transaction.getRawData().getContract(i);
        if (contract.getType() != ContractType.CreateSmartContract) {
          continue;
        }
        if (transactionId == null) {
          transactionHash = DBUtils.getTransactionId(transaction);
          transactionId = transactionHash.toString();
        }
        stats.createContracts.increment();
        try {
          CreateSmartContract createContract = contract.getParameter()
              .unpack(CreateSmartContract.class);
          SmartContract smartContract = createContract.getNewContract();
          byte[] contractAddress = generateContractAddress(transactionHash.getBytes(),
              createContract.getOwnerAddress().toByteArray());
          boolean hasTrxHash = !smartContract.getTrxHash().isEmpty();
          boolean hasCodeHash = !smartContract.getCodeHash().isEmpty();
          String state = stats.recordState(hasTrxHash, hasCodeHash);
          printReportRow(writer, blockNumber, transactionId, i,
              Base58Check.encode58Check(contractAddress), ByteArray.toHexString(contractAddress),
              hasTrxHash,
              ByteArray.toHexString(smartContract.getTrxHash().toByteArray()), hasCodeHash,
              ByteArray.toHexString(smartContract.getCodeHash().toByteArray()), state, "");
        } catch (InvalidProtocolBufferException e) {
          stats.malformedContracts.increment();
          printReportRow(writer, blockNumber, transactionId, i, "", "", false, "", false,
              "", "parse_error", sanitize(e.getMessage()));
        }
      }
    }
  }

  private static void printReportRow(PrintWriter writer, long blockNumber,
      String transactionId, int contractIndex, String contractAddressBase58,
      String contractAddressHex, boolean hasTrxHash, String trxHash, boolean hasCodeHash,
      String codeHash, String state, String parseError) {
    synchronized (writer) {
      writer.format(Locale.ROOT, "%d\t%s\t%d\t%s\t%s\t%b\t%s\t%b\t%s\t%s\t%s%n",
          blockNumber, transactionId, contractIndex, contractAddressBase58,
          contractAddressHex, hasTrxHash, trxHash, hasCodeHash, codeHash, state, parseError);
    }
  }

  private static byte[] generateContractAddress(byte[] transactionHash, byte[] ownerAddress) {
    byte[] combined = new byte[transactionHash.length + ownerAddress.length];
    System.arraycopy(transactionHash, 0, combined, 0, transactionHash.length);
    System.arraycopy(ownerAddress, 0, combined, transactionHash.length, ownerAddress.length);
    return Hash.sha3omit12(combined);
  }

  private void printSummary(ScanStats stats, DbEngine engine, Path blockDbPath) {
    double elapsedSeconds = elapsedSeconds(System.currentTimeMillis());
    double entriesPerSecond = stats.databaseEntries.sum() / elapsedSeconds;
    long bothSet = stats.bothSet.sum();
    long trxHashOnly = stats.trxHashOnly.sum();
    long codeHashOnly = stats.codeHashOnly.sum();
    long trxHashPresent = bothSet + trxHashOnly;
    long codeHashPresent = bothSet + codeHashOnly;
    long eitherHashPresent = bothSet + trxHashOnly + codeHashOnly;
    spec.commandLine().getErr().format(Locale.ROOT,
        "Summary: engine=%s, block_db=%s, elapsed=%.1fs, rate=%,.1f entries/s, "
            + "entries=%,d, blocks=%,d, transactions=%,d, create_contracts=%,d, "
            + "trx_hash_present=%,d, code_hash_present=%,d, either_hash_present=%,d, "
            + "both_set=%,d, trx_hash_only=%,d, code_hash_only=%,d, both_empty=%,d, "
            + "missing_blocks=%,d, malformed_blocks=%,d, malformed_contracts=%,d, "
            + "shards_completed=%,d/%,d%n",
        engine, blockDbPath, elapsedSeconds, entriesPerSecond, stats.databaseEntries.sum(),
        stats.blocks.sum(), stats.transactions.sum(), stats.createContracts.sum(),
        trxHashPresent, codeHashPresent, eitherHashPresent, bothSet, trxHashOnly,
        codeHashOnly, stats.bothEmpty.sum(), stats.missingBlocks.sum(),
        stats.malformedBlocks.sum(), stats.malformedContracts.sum(),
        stats.completedShards.sum(), activeShards);
  }

  private int fail(String message) {
    spec.commandLine().getErr().println(spec.commandLine().getColorScheme().errorText(message));
    return 1;
  }

  private static DbEngine detectEngine(Path blockDbPath) throws IOException {
    Path engineFile = blockDbPath.resolve(DBUtils.FILE_ENGINE);
    if (!Files.exists(engineFile)) {
      return DbEngine.LEVELDB;
    }
    Properties properties = new Properties();
    try (java.io.Reader reader = Files.newBufferedReader(engineFile, StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    String value = properties.getProperty(DBUtils.KEY_ENGINE, "").trim();
    if (DBUtils.LEVELDB.equalsIgnoreCase(value)) {
      return DbEngine.LEVELDB;
    }
    if (DBUtils.ROCKSDB.equalsIgnoreCase(value)) {
      return DbEngine.ROCKSDB;
    }
    throw new IOException(String.format("Unknown database engine '%s' in %s", value, engineFile));
  }

  private static String sanitize(String value) {
    if (value == null) {
      return "";
    }
    return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
  }

  private static String rootMessage(Throwable throwable) {
    Throwable root = throwable;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String message = root.getMessage();
    return message == null || message.isEmpty() ? root.getClass().getSimpleName() : message;
  }

  private enum DbEngine {
    LEVELDB,
    ROCKSDB
  }

  @FunctionalInterface
  private interface ShardScanner {
    void scan(HeightRange range, ScanStats stats, AtomicReference<Throwable> workerFailure)
        throws Exception;
  }

  static class HeightRange {
    final long start;
    final long end;

    HeightRange(long start, long end) {
      if (start < 0 || end < start) {
        throw new IllegalArgumentException(String.format("Invalid height range: %d..%d",
            start, end));
      }
      this.start = start;
      this.end = end;
    }

    long size() {
      return end - start + 1;
    }
  }

  static class ScanStats {
    final LongAdder databaseEntries = new LongAdder();
    final LongAdder blocks = new LongAdder();
    final LongAdder transactions = new LongAdder();
    final LongAdder createContracts = new LongAdder();
    final LongAdder bothSet = new LongAdder();
    final LongAdder trxHashOnly = new LongAdder();
    final LongAdder codeHashOnly = new LongAdder();
    final LongAdder bothEmpty = new LongAdder();
    final LongAdder completedShards = new LongAdder();
    final LongAdder missingBlocks = new LongAdder();
    final LongAdder malformedBlocks = new LongAdder();
    final LongAdder malformedContracts = new LongAdder();

    private String recordState(boolean hasTrxHash, boolean hasCodeHash) {
      if (hasTrxHash && hasCodeHash) {
        bothSet.increment();
        return "both_set";
      }
      if (hasTrxHash) {
        trxHashOnly.increment();
        return "trx_hash_only";
      }
      if (hasCodeHash) {
        codeHashOnly.increment();
        return "code_hash_only";
      }
      bothEmpty.increment();
      return "both_empty";
    }

    private boolean hasParseErrors() {
      return missingBlocks.sum() > 0 || malformedBlocks.sum() > 0
          || malformedContracts.sum() > 0;
    }
  }
}
