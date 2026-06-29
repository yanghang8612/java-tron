package org.tron.core.services.http;

import org.tron.common.utils.JsonUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.bouncycastle.util.encoders.Hex;
import org.eclipse.jetty.util.StringUtil;
import org.tron.common.runtime.InternalTransaction;
import org.tron.common.runtime.vm.DataWord;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.store.StoreFactory;
import org.tron.core.vm.JumpTable;
import org.tron.core.vm.Operation;
import org.tron.core.vm.OperationRegistry;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.program.invoke.ProgramInvoke;
import org.tron.core.vm.program.invoke.ProgramInvokeFactory;
import org.tron.core.vm.program.invoke.ProgramInvokeMockImpl;
import org.tron.core.vm.repository.Repository;
import org.tron.core.vm.repository.RepositoryImpl;
import org.tron.protos.Protocol;

import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

@Slf4j(topic = "api")
public abstract class OpServlet extends RateLimiterServlet{

    private static final String RANDOM_CONTRACT = "randomContract";
    protected String opConfig;

    protected String fileName;

    protected int round;

    protected FileWriter fileWriter;

    protected List ops;

    final Random random = new Random();

    protected final JumpTable jumpTable = OperationRegistry.getTable();

    protected long cost;

    protected long maxCost;

    protected long minCost;

    protected List<Long> costList;

    byte[] randomAddress;

    private long lastCost;

    protected List<String> addressList;

    protected List<String> storageKeyList;

    private int curIndex = 0;

    protected List<String> contractList;

    protected void parseConfig(HttpServletRequest request) throws IOException {
        opConfig = request.getParameter("op_config");
        if (StringUtil.isBlank(opConfig)) {
            opConfig = "op";
        }
        File file=new File(opConfig + ".json");
        String content= FileUtils.readFileToString(file,"UTF-8");
        Map params = JsonUtil.json2Obj(content, Map.class);

        round = ((Number) params.get("round")).intValue();
        fileName = (String) params.get("fileName");
        File folder = new File("benchmark/");
        if (!folder.exists()) {
            folder.mkdir();
        }

        if (fileName == null) {
            fileName = "benchmark/output_" + opConfig + ".txt";
        }
        else {
            fileName = "benchmark/" + fileName + ".txt";
        }
        fileWriter = new FileWriter(fileName, true);
        ops = (List)params.get("ops");
    }

    protected byte[] generateAddress() {
        byte[] result = new byte[32];
        random.nextBytes(result);
        for (int i = 0; i < 11; i++) {
            result[i] = 0x00;
        }
        result[11] = 0x41;
        return result;
    }

    protected byte[] getBytecodes(Map<String, Object> map) {
        String bytecodes = (String) map.get("bytecodes");
        return Hex.decode(bytecodes);
    }

    protected String getCodeAddress(Map<String, Object> map) {
        return (String) map.get("codeAddress");
    }

    protected byte[] codeAddressToByte(String codeAddress) {
        if (codeAddress == null) {
            return new byte[0];
        }

        if (RANDOM_CONTRACT.equals(codeAddress)) {
            if (contractList == null) {
                loadContractAddressFile();
            }

            return Hex.decode(contractList.get(random.nextInt(contractList.size())));
        }
        return Hex.decode(codeAddress);
    }


    protected List<String> getStacks(Map<String, Object> map) {
        if (map.containsKey("stacks")) {
            return (List)map.get("stacks");
        }
        return Collections.emptyList();
    }

    protected List<String> getMemory(Map<String, Object> map) {
        if (map.containsKey("memory")) {
            return (List)map.get("memory");
        }
        return Collections.emptyList();
    }

    protected void runOp(byte[] bytecodes, String codeAddressStr, List<String> stackValues, List<String> memoryValues) throws ContractValidateException, IOException {
        maxCost = Long.MIN_VALUE;
        minCost = Long.MAX_VALUE;
        lastCost = Long.MIN_VALUE;
        for (int i = 0; i < round; i++) {
            byte[] codeAddress = codeAddressToByte(codeAddressStr);
            ProgramInvokeMockImpl invoke0 = new ProgramInvokeMockImpl(StoreFactory.getInstance(), bytecodes, codeAddress);
            Protocol.Transaction trx = Protocol.Transaction.getDefaultInstance();
            InternalTransaction interTrx =
                    new InternalTransaction(trx, InternalTransaction.TrxType.TRX_UNKNOWN_TYPE);
            long vmStartInUs = System.nanoTime() / 1000;
            Repository rootRepository = RepositoryImpl.createRoot(StoreFactory.getInstance());

            ProgramInvoke invoke = ProgramInvokeFactory.createProgramInvoke(
                    new Program(bytecodes, codeAddress, invoke0, interTrx), new DataWord(codeAddress),
                    new DataWord(codeAddress),
                    DataWord.ZERO(),
                    DataWord.ZERO(),
                    DataWord.ZERO(),
                    0, new byte[0], rootRepository,
                    false,
                    false, vmStartInUs, vmStartInUs + 1_000_000_000L, 100_000_000L);

            Program program = new Program(bytecodes, codeAddress, invoke, interTrx);
            program.setAdjustedCallEnergy(new DataWord(1_000_000_000));
            program.setRootTransactionId(generateAddress());
            for (String value : stackValues) {
                DataWord stackValue = getDataWord(value);
                program.stackPush(stackValue);

            }
            int addr = 0;
            for (String value : memoryValues) {
                DataWord memoryValue = getDataWord(value);
                program.memorySave(addr, memoryValue.getData());
                addr += DataWord.WORD_SIZE;
            }
            testSingleOpration(program);
        }
        addressList = null;
        contractList = null;
    }

    private DataWord getDataWord(String value) throws IOException {
        if (value.equals("randomAddress")) {
            randomAddress = generateAddress();
            return new DataWord(randomAddress.clone());
        }
        else if (value.equals("accountAddress")) {
            if (addressList == null) {
                readFile();
            }
            if (curIndex == addressList.size()) {
                curIndex = 0;
            }
            return new DataWord(addressList.get(curIndex));
        } else if (value.equals("randomAccount")) {
            if (addressList == null) {
                readFile();
            }
            return new DataWord(addressList.get(random.nextInt(addressList.size())));
        } else if (value.equals("randomKey")) {
            if (storageKeyList == null) {
                readStorageKeys();
            }
            return new DataWord(storageKeyList.get(random.nextInt(storageKeyList.size())));
        } else if (value.equals(RANDOM_CONTRACT)) {
            if (contractList == null) {
                loadContractAddressFile();
            }
            return new DataWord(contractList.get(random.nextInt(contractList.size())));
        }
        else {
            return new DataWord(value);
        }
    }

    private void readFile() throws IOException {
        String fileName = "accountAddress.txt";
        addressList = new ArrayList();
        Files.lines(Paths.get(fileName)).forEach(line -> {
            addressList.add(line.trim());
        });
        if (curIndex >= addressList.size()) {
            curIndex = 0;
        }
    }

    private void readStorageKeys() throws IOException {
        String fileName = "storageKeys.txt";
        storageKeyList = new ArrayList();
        Files.lines(Paths.get(fileName)).forEach(line -> {
            storageKeyList.add(line.trim());
        });

    }

    @SneakyThrows
    private void loadContractAddressFile() {
        String fileName = "contractAddress.txt";
        contractList = new ArrayList<>();
        Files.lines(Paths.get(fileName)).forEach(line -> {
            contractList.add(line.trim());
        });

    }

    protected void testSingleOpration(Program program) {
        Operation op = jumpTable.get(program.getCurrentOpIntValue());
        if (!op.isEnabled()) {
            throw Program.Exception.invalidOpCode(program.getCurrentOp());
        }
        program.setLastOp((byte) op.getOpcode());
        program.verifyStackSize(op.getRequire());
        program.verifyStackOverflow(op.getRequire(), op.getRet());
        long start = System.nanoTime();

        op.execute(program);


        long end = System.nanoTime();
        long curCost = end - start;
        if (costList != null) {
            costList.add(curCost);
        }
        maxCost = Math.max(maxCost, curCost);
        minCost = Math.min(minCost, curCost);
        cost += curCost;
        program.setPreviouslyExecutedOp((byte) op.getOpcode());
    }

}
