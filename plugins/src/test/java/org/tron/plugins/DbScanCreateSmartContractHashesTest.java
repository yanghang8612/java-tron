package org.tron.plugins;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tron.plugins.DbScanCreateSmartContractHashes.ScanStats;
import org.tron.plugins.utils.DBUtils;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol.Block;
import org.tron.protos.Protocol.BlockHeader;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.contract.BalanceContract.TransferContract;
import org.tron.protos.contract.SmartContractOuterClass.CreateSmartContract;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;
import picocli.CommandLine;

public class DbScanCreateSmartContractHashesTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void scanBlockClassifiesHashFields() {
    Block block = Block.newBuilder()
        .setBlockHeader(BlockHeader.newBuilder()
            .setRawData(BlockHeader.raw.newBuilder().setNumber(123)))
        .addTransactions(transaction(regularContract()))
        .addTransactions(transaction(createContract(new byte[0], new byte[0])))
        .addTransactions(transaction(createContract(new byte[] {1}, new byte[0])))
        .addTransactions(transaction(createContract(new byte[0], new byte[] {2})))
        .addTransactions(transaction(createContract(new byte[] {3}, new byte[] {4})))
        .addTransactions(transaction(malformedCreateContract()))
        .build();
    StringWriter report = new StringWriter();
    ScanStats stats = new ScanStats();

    DbScanCreateSmartContractHashes.scanBlock(block, new PrintWriter(report), stats);

    Assert.assertEquals(6, stats.transactions.sum());
    Assert.assertEquals(5, stats.createContracts.sum());
    Assert.assertEquals(1, stats.bothEmpty.sum());
    Assert.assertEquals(1, stats.trxHashOnly.sum());
    Assert.assertEquals(1, stats.codeHashOnly.sum());
    Assert.assertEquals(1, stats.bothSet.sum());
    Assert.assertEquals(1, stats.malformedContracts.sum());
    Assert.assertTrue(report.toString().contains("\tfalse\t\tfalse\t\tboth_empty\t"));
    Assert.assertTrue(report.toString().contains("\ttrue\t01\tfalse\t\ttrx_hash_only\t"));
    Assert.assertTrue(report.toString().contains("\tfalse\t\ttrue\t02\tcode_hash_only\t"));
    Assert.assertTrue(report.toString().contains("\ttrue\t03\ttrue\t04\tboth_set\t"));
    Assert.assertTrue(report.toString().contains("\tparse_error\t"));
  }

  @Test
  public void commandScansRocksDbAndWritesReport() throws Exception {
    File databaseDirectory = temporaryFolder.newFolder("database");
    Path report = temporaryFolder.getRoot().toPath().resolve("report.tsv");
    Block block = Block.newBuilder()
        .setBlockHeader(BlockHeader.newBuilder()
            .setRawData(BlockHeader.raw.newBuilder().setNumber(456)))
        .addTransactions(transaction(createContract(new byte[] {1}, new byte[] {2})))
        .build();
    try (DBInterface database = DbTool.getDB(databaseDirectory.getPath(), "block",
        DbTool.DbType.RocksDB)) {
      for (int i = 0; i < 50; i++) {
        database.put(new byte[] {(byte) i}, block.toByteArray());
      }
    }

    StringWriter commandError = new StringWriter();
    CommandLine commandLine = new CommandLine(new Toolkit())
        .setErr(new PrintWriter(commandError));
    int exitCode = commandLine.execute("db", "scan-create-contract-hashes",
        databaseDirectory.getPath(), "--threads", "4", "--progress", "0",
        "--output", report.toString());

    Assert.assertEquals(0, exitCode);
    String output = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
    Assert.assertTrue(output.startsWith("block_number\ttransaction_id\tcontract_index"));
    Assert.assertTrue(output.contains("456\t"));
    Assert.assertTrue(output.contains("\ttrue\t01\ttrue\t02\tboth_set\t"));
    Assert.assertTrue(output.matches(
        "(?s).*\\tT[1-9A-HJ-NP-Za-km-z]{33}\\t41[0-9a-f]{40}\\ttrue\\t01.*"));
    Assert.assertEquals(51, Files.readAllLines(report, StandardCharsets.UTF_8).size());
    Assert.assertTrue(commandError.toString().contains("trx_hash_present=50"));
    Assert.assertTrue(commandError.toString().contains("code_hash_present=50"));
    Assert.assertTrue(commandError.toString().contains("either_hash_present=50"));
    Assert.assertEquals(DBUtils.ROCKSDB,
        org.tron.plugins.utils.FileUtils.readProperty(
            databaseDirectory.toPath().resolve("block").resolve(DBUtils.FILE_ENGINE).toString(),
            DBUtils.KEY_ENGINE));
  }

  private static Transaction transaction(Contract contract) {
    return Transaction.newBuilder()
        .setRawData(Transaction.raw.newBuilder().addContract(contract))
        .build();
  }

  private static Contract regularContract() {
    return Contract.newBuilder()
        .setType(ContractType.TransferContract)
        .setParameter(Any.pack(TransferContract.getDefaultInstance()))
        .build();
  }

  private static Contract createContract(byte[] trxHash, byte[] codeHash) {
    SmartContract smartContract = SmartContract.newBuilder()
        .setTrxHash(ByteString.copyFrom(trxHash))
        .setCodeHash(ByteString.copyFrom(codeHash))
        .build();
    CreateSmartContract createContract = CreateSmartContract.newBuilder()
        .setOwnerAddress(ByteString.copyFrom(new byte[] {
            0x41, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}))
        .setNewContract(smartContract)
        .build();
    return Contract.newBuilder()
        .setType(ContractType.CreateSmartContract)
        .setParameter(Any.pack(createContract))
        .build();
  }

  private static Contract malformedCreateContract() {
    return Contract.newBuilder()
        .setType(ContractType.CreateSmartContract)
        .setParameter(Any.newBuilder()
            .setTypeUrl("type.googleapis.com/protocol.CreateSmartContract")
            .setValue(ByteString.copyFrom(new byte[] {(byte) 0x80})))
        .build();
  }
}
