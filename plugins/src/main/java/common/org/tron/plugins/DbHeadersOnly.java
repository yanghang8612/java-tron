package org.tron.plugins;

import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.tron.common.utils.FastSyncStorage;
import org.tron.common.utils.Sha256Hash;
import org.tron.plugins.utils.DBUtils;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;

/** Offline conversion for fast-sync-stats; account and contract databases are not rewritten. */
@CommandLine.Command(name = "headers-only", mixinStandardHelpOptions = true,
    description = "Keep 65536 recent headers and empty transaction databases. Stop the node first.")
public class DbHeadersOnly implements Callable<Integer> {

  private static final List<String> STORES = Arrays.asList("block", "block-index", "trans",
      "transactionRetStore", "transactionHistoryStore", "trans-cache", "recent-transaction");
  private static final byte[] HEAD_NUMBER = bytes("latest_block_header_number");
  private static final byte[] HEAD_HASH = bytes("latest_block_header_hash");
  private static final byte[] GENESIS_ID = bytes(FastSyncStorage.GENESIS_ID_KEY);

  @CommandLine.Option(names = "--database", required = true,
      description = "Stopped node's output-directory/database directory.")
  private Path database;

  @CommandLine.Option(names = "--execute", required = true,
      description = "Apply the conversion; retain original stores in .header-only-backup.")
  private boolean execute;

  @Override
  public Integer call() throws Exception {
    if (!execute) {
      throw new IllegalArgumentException("--execute is required");
    }
    migrate(database, FastSyncStorage.HEADER_RETENTION_BLOCKS);
    return 0;
  }

  static void migrate(Path directory, long retention) throws Exception {
    if (retention <= 0) {
      throw new IllegalArgumentException("retention must be positive");
    }
    Path source = directory.toRealPath();
    Path stage = source.resolve(".header-only-stage");
    Path backup = source.resolve(".header-only-backup");
    Path pending = source.resolve(FastSyncStorage.MIGRATION_PENDING_FILE);
    if (Files.exists(stage) || Files.exists(backup) || Files.exists(pending)) {
      throw new IllegalStateException("Existing migration files require review or restoration");
    }
    for (String name : Arrays.asList("block", "block-index", "properties", "tracker")) {
      if (!Files.isRegularFile(source.resolve(name).resolve("CURRENT"))
          || Files.isSymbolicLink(source.resolve(name))) {
        throw new IllegalArgumentException("Missing or symlinked database: " + name);
      }
    }

    try {
      // Opening the source stores acquires their database locks before any source mutation.
      for (String name : STORES) {
        if (Files.isSymbolicLink(source.resolve(name))) {
          throw new IllegalArgumentException("Symlinked database: " + name);
        }
        if (Files.isDirectory(source.resolve(name))) {
          DbTool.getDB(source.toString(), name);
        }
      }
      DBInterface properties = DbTool.getDB(source.toString(), "properties");
      DBInterface tracker = DbTool.getDB(source.toString(), "tracker");
      List<Path> checkpoints = checkpoints(source);
      Map<String, Map<ByteString, byte[]>> overlay = new HashMap<>();
      List<Path> active = activeCheckpoints(checkpoints);
      for (Path checkpoint : active) {
        collectCheckpoint(checkpoint, overlay);
      }
      byte[] headBytes = lookup(properties, overlay, "properties", HEAD_NUMBER);
      byte[] headHash = lookup(properties, overlay, "properties", HEAD_HASH);
      if (headBytes == null || headBytes.length != Long.BYTES || headHash == null
          || headHash.length != 32) {
        throw new IllegalStateException("Missing chain head metadata");
      }
      long head = Longs.fromByteArray(headBytes);
      if (head < 0) {
        throw new IllegalStateException("Negative chain head");
      }
      DBInterface index = DbTool.getDB(source.toString(), "block-index");
      DBInterface blocks = DbTool.getDB(source.toString(), "block");
      byte[] genesis = lookup(index, overlay, "block-index", Longs.toByteArray(0));
      if (genesis == null) {
        genesis = lookup(tracker, overlay, "tracker", GENESIS_ID);
      }
      if (genesis == null || genesis.length != 32
          || Longs.fromByteArray(Arrays.copyOf(genesis, Long.BYTES)) != 0) {
        throw new IllegalStateException("Missing genesis identity; refusing conversion");
      }
      long first = head >= retention ? head - retention + 1 : 0;
      Files.createDirectory(stage);
      for (String name : STORES) {
        if (Files.isDirectory(source.resolve(name))) {
          DbTool.getDB(source.toString(), stage.toString(), name);
        }
      }
      DBInterface newBlocks = DbTool.getDB(source.toString(), stage.toString(), "block");
      DBInterface newIndex = DbTool.getDB(source.toString(), stage.toString(), "block-index");
      byte[] previous = null;
      for (long number = first; number <= head; number++) {
        byte[] key = Longs.toByteArray(number);
        byte[] id = lookup(index, overlay, "block-index", key);
        if (id == null || id.length != 32) {
          throw new IllegalStateException("Missing block index at " + number);
        }
        byte[] value = lookup(blocks, overlay, "block", id);
        if (value == null) {
          throw new IllegalStateException("Missing block at " + number);
        }
        Protocol.Block block = Protocol.Block.parseFrom(value);
        validateHeader(block, number, id);
        if (previous != null
            && !Arrays.equals(previous, block.getBlockHeader().getRawData()
                .getParentHash().toByteArray())) {
          throw new IllegalStateException("Broken header chain at " + number);
        }
        newBlocks.put(id, headerOnly(block));
        newIndex.put(key, id);
        previous = id;
      }
      if (!Arrays.equals(previous, headHash)) {
        throw new IllegalStateException("Chain head hash does not match the retained headers");
      }
      verify(stage, first, head);

      // Leave this marker on every failure after this point. FullNode refuses to start with it.
      Files.write(pending, bytes("Restore .header-only-backup before restarting after a failure\n"),
          StandardOpenOption.CREATE_NEW);
      Files.createDirectory(backup);
      DbTool.close();
      for (String name : Arrays.asList("tracker", "checkpoint", "tmp")) {
        if (Files.isDirectory(source.resolve(name))) {
          copyDirectory(source.resolve(name), backup.resolve(name));
        }
      }
      for (Path checkpoint : checkpoints) {
        clearHistoryCheckpoint(checkpoint);
      }
      DbTool.getDB(source.toString(), "tracker").put(GENESIS_ID, genesis);
      DbTool.close();
      for (String name : STORES) {
        if (Files.isDirectory(source.resolve(name))) {
          Files.move(source.resolve(name), backup.resolve(name));
          Files.move(stage.resolve(name), source.resolve(name));
        }
      }
      verify(source, first, head);
      DbTool.close();
      Files.delete(stage);
      Files.delete(pending);
      System.out.println("Header-only migration complete: first=" + first + ", head=" + head
          + ", headers=" + (head - first + 1) + ", backup=" + backup);
    } finally {
      DbTool.close();
    }
  }

  private static void verify(Path directory, long first, long head) throws Exception {
    DBInterface blocks = DbTool.getDB(directory.toString(), "block");
    DBInterface index = DbTool.getDB(directory.toString(), "block-index");
    if (blocks.size() != head - first + 1 || index.size() != head - first + 1) {
      throw new IllegalStateException("Unexpected retained header count");
    }
    try (DBIterator iterator = index.iterator()) {
      for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
        long number = Longs.fromByteArray(iterator.getKey());
        Protocol.Block block = Protocol.Block.parseFrom(blocks.get(iterator.getValue()));
        validateHeader(block, number, iterator.getValue());
        if (number < first || number > head || block.getTransactionsCount() != 0) {
          throw new IllegalStateException("Unexpected retained block at " + number);
        }
      }
    }
    for (String name : STORES.subList(2, STORES.size())) {
      if (Files.isDirectory(directory.resolve(name))
          && DbTool.getDB(directory.toString(), name).size() != 0) {
        throw new IllegalStateException("Transaction database is not empty: " + name);
      }
    }
  }

  private static void collectCheckpoint(Path path,
      Map<String, Map<ByteString, byte[]>> overlay) throws Exception {
    DBInterface checkpoint = DbTool.getDB(path.getParent().toString(),
        path.getFileName().toString());
    try (DBIterator iterator = checkpoint.iterator()) {
      for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
        byte[] key = iterator.getKey();
        String name = DBUtils.simpleDecode(key);
        if (!Arrays.asList("block", "block-index", "properties", "tracker").contains(name)) {
          continue;
        }
        byte[] realKey = Arrays.copyOfRange(key, Integer.BYTES + bytes(name).length, key.length);
        byte[] raw = iterator.getValue();
        byte[] value = raw.length == 1 ? null : Arrays.copyOfRange(raw, 1, raw.length);
        if (raw.length == 1 && raw[0] != DBUtils.Operator.DELETE.getValue()) {
          value = new byte[0];
        }
        if ("block".equals(name) && value != null) {
          value = headerOnly(Protocol.Block.parseFrom(value));
        }
        overlay.computeIfAbsent(name, ignored -> new HashMap<>())
            .put(ByteString.copyFrom(realKey), value);
      }
    }
  }

  private static byte[] lookup(DBInterface source,
      Map<String, Map<ByteString, byte[]>> overlay, String name, byte[] key) {
    Map<ByteString, byte[]> entries = overlay.get(name);
    ByteString wrapped = ByteString.copyFrom(key);
    return entries != null && entries.containsKey(wrapped) ? entries.get(wrapped) : source.get(key);
  }

  private static List<Path> checkpoints(Path source) throws IOException {
    List<Path> result = new ArrayList<>();
    if (Files.isDirectory(source.resolve("checkpoint"))) {
      try (Stream<Path> files = Files.list(source.resolve("checkpoint"))) {
        result.addAll(files.filter(Files::isDirectory)
            .sorted(Comparator.comparingLong(path -> Long.parseLong(path.getFileName().toString())))
            .collect(Collectors.toList()));
      }
    }
    if (Files.isDirectory(source.resolve("tmp"))) {
      result.add(source.resolve("tmp"));
    }
    return result;
  }

  private static List<Path> activeCheckpoints(List<Path> all) {
    List<Path> v2 = all.stream().filter(path -> !"tmp".equals(path.getFileName().toString()))
        .collect(Collectors.toList());
    if (v2.isEmpty()) {
      return all;
    }
    long latest = Long.parseLong(v2.get(v2.size() - 1).getFileName().toString());
    return v2.stream()
        .filter(path -> latest - Long.parseLong(path.getFileName().toString()) <= 120_000L)
        .collect(Collectors.toList());
  }

  private static void clearHistoryCheckpoint(Path path) throws Exception {
    DBInterface checkpoint = DbTool.getDB(path.getParent().toString(),
        path.getFileName().toString());
    List<byte[]> remove = new ArrayList<>();
    try (DBIterator iterator = checkpoint.iterator()) {
      for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
        byte[] key = iterator.getKey();
        String name = DBUtils.simpleDecode(key);
        byte[] realKey = Arrays.copyOfRange(key, Integer.BYTES + bytes(name).length, key.length);
        if (STORES.contains(name)
            || ("tracker".equals(name) && Arrays.equals(realKey, GENESIS_ID))) {
          remove.add(Arrays.copyOf(key, key.length));
        }
      }
    }
    for (byte[] key : remove) {
      checkpoint.delete(key);
    }
  }

  private static void copyDirectory(Path source, Path target) throws IOException {
    try (Stream<Path> files = Files.walk(source)) {
      for (Path path : files.collect(Collectors.toList())) {
        if (Files.isSymbolicLink(path)) {
          throw new IOException("Symlink in checkpoint backup: " + path);
        }
        Path destination = target.resolve(source.relativize(path));
        if (Files.isDirectory(path)) {
          Files.createDirectory(destination);
        } else {
          Files.copy(path, destination);
        }
      }
    }
  }

  private static void validateHeader(Protocol.Block block, long number, byte[] id) {
    byte[] expected = Sha256Hash.of(true,
        block.getBlockHeader().getRawData().toByteArray()).getBytes();
    System.arraycopy(Longs.toByteArray(number), 0, expected, 0, Long.BYTES);
    if (!block.hasBlockHeader() || block.getBlockHeader().getRawData().getNumber() != number
        || !Arrays.equals(id, expected)) {
      throw new IllegalStateException("Invalid header at " + number);
    }
  }

  private static byte[] headerOnly(Protocol.Block block) {
    return Protocol.Block.newBuilder().setBlockHeader(block.getBlockHeader()).build().toByteArray();
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
