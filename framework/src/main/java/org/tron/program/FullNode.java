package org.tron.program;

import com.beust.jcommander.JCommander;
import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.tron.api.GrpcAPI;
import org.tron.common.application.Application;
import org.tron.common.application.ApplicationFactory;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.exit.ExitManager;
import org.tron.common.log.LogService;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.prometheus.Metrics;
import org.tron.common.utils.Base58;
import org.tron.common.utils.StringUtil;
import org.tron.core.ChainBaseManager;
import org.tron.core.Constant;
import org.tron.core.Wallet;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.protos.Protocol;

@Slf4j(topic = "app")
public class FullNode {

  /**
   * Start the FullNode.
   */
  public static void main(String[] args) {
    ExitManager.initExceptionHandler();
    logger.info("Full node running.");
    Args.setParam(args, Constant.TESTNET_CONF);
    CommonParameter parameter = Args.getInstance();

    LogService.load(parameter.getLogbackPath());

    if (parameter.isHelp()) {
      JCommander jCommander = JCommander.newBuilder().addObject(Args.PARAMETER).build();
      jCommander.parse(args);
      Args.printHelp(jCommander);
      return;
    }

    if (Args.getInstance().isDebug()) {
      logger.info("in debug mode, it won't check energy time");
    } else {
      logger.info("not in debug mode, it will check energy time");
    }

    // init metrics first
    Metrics.init();

    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.setAllowCircularReferences(false);
    TronApplicationContext context =
        new TronApplicationContext(beanFactory);
    context.register(DefaultConfig.class);
    context.refresh();
//    Application appT = ApplicationFactory.create(context);
//    context.registerShutdownHook();
//    appT.startup();
//    appT.blockUntilShutdown();

    DynamicPropertiesStore dps = ChainBaseManager.getInstance().getDynamicPropertiesStore();
    Wallet wallet = context.getBean(Wallet.class);

    long startNum = 68000000L;
    long endNum = dps.getLatestBlockHeaderNumber();
    String[] contractsStr = new String[]{
        "TWTcYYHvRAkhFHR7Spgnh4Pb5ofboroVZK",
        "TUGzN12obTXAmFBALRHDHim7WwATfdUmmV",
        "TXzhj9Xh8xfzerjinRyM5TfoBL7Cw5hk5d",
        "TSB7M6eBpBD4RUrqdgPrTLkfzzYV39QTBo",
        "TNuBMH3A628d9UAhgkBFpgqb3gHy2sbnF5",
        "TMhsJiXUrT5eueuH1cq6SdvQEBt4YjKLfx",
        "TVsQxikpttN15u7vcjXKeVrtYRWbrqgPbH",
        "TQrq2p1aoAkNK94q3Q69ubJcv5nQ9y675R",
        "TEMgm1RKGY3uP1UTCDqNXJ7DgSqsKMBhiy",
        "TUMBP4f47fyu9neED1UYjT78J42eSf6xjB",
        "TMk8sVbkfsuyJUHxkJ4oCS1JQPEp4PNB5g",
        "TMtCbYmfc6zQ1LSKm3eWZntjzYUVaxCzr2",
        "TTMuf1VNGVHBaZSBU54FK3tki8y5XoqvCX",
        "TJKFX3vEybixnauT67ko4nh2DzwT9SPWiG",
        "TBJQzdwg3bFM5kjrFeYwQLzGAG9RvF7MBh",
        "TD6HorqmFL3M4xL14icjNzXPNCPca4d32f",
        "TDiM14q5nNVjjsodA4rXmhxSv7oXtT8mPD",
        "TMvvzNcnREy7qofJyqbNsoL2op7SvaaEey",
        "TH9Y8aB8E8hiXYGbmXAM5qMTUxBe4vzb1u",
        "TP9ZvM6kRk9kpVhpuLVvGZ1w2GzPCu7JbR",
        "TQ2uLRqqHWb3LD5gfqBwj32uThNjFoSweV",
        "TYyC3kxzMYC1sGKpui79VAzwn992jCA6fN",
        "TCdgsgwyka6LG2VzSA65wdscPauxgFg9Wv"};
    Set<ByteString> contracts = new HashSet<>();
    for (String str : contractsStr) {
      byte[] address = Arrays.copyOfRange(Base58.decode(str), 1, 21);
      contracts.add(ByteString.copyFrom(address));
    }
    ByteString relyTopic =
        ByteString.copyFrom(Hex.decode("dd0e34038ac38b2a1ce960229778ac48a8719bc900b6c4f8d0475c6e8b385a60"));
    ByteString denyTopic =
        ByteString.copyFrom(Hex.decode("184450df2e323acec0ed3b5c7531b81f9b4cdef7914dfd4c0a4317416bb5251b"));
    ByteString authorityTopic =
        ByteString.copyFrom(Hex.decode("1abebea81bfa2637f28358c371278fb15ede7ea8dd28d2e03b112ff6d936ada4"));
    ByteString ownerTopic =
        ByteString.copyFrom(Hex.decode("ce241d7ca1f669fee44b6fc00b8eba2df3bb514eed0f6f668f8f89096e81ed94"));
    ByteString hopeTopic =
        ByteString.copyFrom(Hex.decode("3a21b662999d3fc0ceca48751a22bf61a806dcf3631e136271f02f7cb981fd43"));
    ByteString nopeTopic =
        ByteString.copyFrom(Hex.decode("9cd85b2ca76a06c46be663a514e012af1aea8954b0e53f42146cd9b1ebb21ebc"));
    ByteString mateTopic =
        ByteString.copyFrom(Hex.decode("e25de3b40ce055247fe4ef6c00f96c8c3b6530536701ba1c48296b30b4bb0d95"));
    ByteString hateTopic =
        ByteString.copyFrom(Hex.decode("04942e12b9e2310f85c952df158815306377bb9f797dc3677d03be357427c53b"));
    ByteString kissTopic =
        ByteString.copyFrom(Hex.decode("6ffc0fabf0709270e42087e84a3bfc36041d3b281266d04ae1962185092fb244"));
    ByteString dissTopic =
        ByteString.copyFrom(Hex.decode("12fdafd291eb287a54e3416070923d22aa5072f5ee04c4fb8361615e7508a37c"));

    Map<ByteString, String> topics = new HashMap<>();
    topics.put(relyTopic, "Rely");
    topics.put(denyTopic, "Deny");
    topics.put(authorityTopic, "LogSetAuthority");
    topics.put(ownerTopic, "LogSetOwner");
    topics.put(hopeTopic, "Hope");
    topics.put(nopeTopic, "Nope");
    topics.put(mateTopic, "Mate");
    topics.put(hateTopic, "Hate");
    topics.put(kissTopic, "Kiss");
    topics.put(dissTopic, "Diss");

    logger.info("This scripts is using to scan Rely(address) topics for the given contract.");
    logger.info("Start block num is {}, End block num is {}", startNum, endNum);
    for (long i = startNum; i <= endNum; i++) {
      GrpcAPI.TransactionInfoList txList = wallet.getTransactionInfoByBlockNum(i);
      for (Protocol.TransactionInfo info : txList.getTransactionInfoList()) {
        for (Protocol.TransactionInfo.Log log : info.getLogList()) {
          if (contracts.contains(log.getAddress()) &&
              log.getTopicsCount() > 0 && topics.containsKey(log.getTopics(0))) {
            byte[] contract = new byte[21];
            System.arraycopy(log.getAddress().toByteArray(), 0, contract, 1, 20);
            contract[0] = 0x41;

            byte[] address;
            if (log.getTopicsCount() > 1) {
              address = Arrays.copyOfRange(log.getTopics(1).toByteArray(), 11, 32);
            } else {
              address = Arrays.copyOfRange(log.getData().toByteArray(), 11, 32);
            }
            address[0] = 0x41;

            logger.info("Found concerned topic - {} {} {} {}",
                StringUtil.encode58Check(contract),
                topics.get(log.getTopics(0)),
                StringUtil.encode58Check(address),
                Hex.toHexString(info.getId().toByteArray()));
          }
        }
      }

      if (i % 100_000 == 0) {
        logger.info("Current block num is {}", i);
      }
    }
    logger.info("Finish scanning.");

  }
}
