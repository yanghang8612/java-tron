package org.tron.program;

import com.beust.jcommander.JCommander;
import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.tron.api.GrpcAPI;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.exit.ExitManager;
import org.tron.common.log.LogService;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.prometheus.Metrics;
import org.tron.common.utils.Commons;
import org.tron.common.utils.StringUtil;
import org.tron.core.ChainBaseManager;
import org.tron.core.Constant;
import org.tron.core.Wallet;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.store.ContractStore;
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

    ByteString owner1 = ByteString.copyFrom(Commons.decodeFromBase58Check("TXHDjs83UhE2MeSfy3TGMobdzR1KEFPySR"));
    ByteString owner2 = ByteString.copyFrom(Commons.decodeFromBase58Check("TPEY23WJpcf76oVFhTUgoNQmSm3VtckDcH"));

    ContractStore contractStore = ChainBaseManager.getInstance().getContractStore();
    DynamicPropertiesStore dps = ChainBaseManager.getInstance().getDynamicPropertiesStore();
    Wallet wallet = context.getBean(Wallet.class);

    long startNum = 68000000L;
    long endNum = dps.getLatestBlockHeaderNumber();
    String[] contractsStr = new String[]{
        "T9yEgaPQT9Z6jsF1Rd2nisf1bpwpXNAFqE",
        "TAdMMCxAtNR6425AbnbktyBvZazQkp8aAZ",
        "TBjGXKxTcDmmP6RQaXSvVqqa3H3ZmumQYo",
        "TBQLNFv8cfxdw8ruukDRKR3b3DRMr8RHBt",
        "TBXW4hS5KYjjbJXDpnrPf4zhkLwrpUjbyz",
        "TCwYKcDj8c5Te9hjj3UokcxhpY6skFoXnG",
        "TDDWjmQaquEtUn1Pa8wCd8dfWFPdQLGPYL",
        "TDLgCe2SatVew6PxSwYmqsc3zfahqXwuqX",
        "TDRjh5u9YSxcW53bLzFbS4xRwWNLJmkGbc",
        "TDUkQbjrXs6xUbxGCLknWwJHxVTdysXBhy",
        "TE19XD8DPc9D3z3SMWeS14xWC6Mt3wSJKu",
        "TE6RxGgQuD6J1faw9mZxtbHGDqcKh8DvKU",
        "TEk9usYZsunkc5oYijyMte6sGurspik2Js",
        "TEMgm1RKGY3uP1UTCDqNXJ7DgSqsKMBhiy",
        "TFhJ87cWQVbDC451k5tAHn87GWfpYCaqmj",
        "TFLT469zSDwfUEyXsKZQq9nqjUSahYunHs",
        "TFpBqyujDPPBuNi33LvWSetLFtq1anh7vv",
        "TFSFz8GYowZwqAfnCnURBiUeLrHm6agYXz",
        "TFuumskYzYBLZ7EDNpADFTSi1cgcgd7Wwa",
        "TGKJKQY9Bh5yVjW1WL5NbfmcGXD2z9Jj4q",
        "TGQKnHDQNyc3QeHJ7YxH8wggdg89UVXyvX",
        "TH5dhX7o39afSbfDT2e3c9k4itWjNKD4D9",
        "THuAY3Dqaf5EMdUPcQtmFew85ZSTEENAYk",
        "THuVWkvAikvSqmoZXHMUQJAcocsgFr4wuk",
        "TJ1VWPvFVq7sVsN7J7dWJVZz4SLT14qRUr",
        "TK9Ng6QqNVvyhWcWAiEasZ1HqE7bxgNayA",
        "TKvNF7aJtU2gUncR64MCdDoaWqrnHpriSL",
        "TLmm1SbAs9WFqKyaLpqTiRVFj6kTN7WeBp",
        "TLNs9UZyd7jN3y8RzyFng88TkgEhtRXF8W",
        "TM8Zpv6badmQnw6uo3jLhnRtYD5zoNY4Cj",
        "TMhsJiXUrT5eueuH1cq6SdvQEBt4YjKLfx",
        "TMUPT9tb5MknynKDVWwy6LWdABCjyGD3A8",
        "TMZTbwpvs7VjTJ7qjwh4EMkB5ahZ5tUJeM",
        "TNq6E9XsQfrzqVwam67LApZQx1omsj8dyW",
        "TNqH7NFMDrHj5FSTGeGkepcEG5Fav9e6F6",
        "TNuBMH3A628d9UAhgkBFpgqb3gHy2sbnF5",
        "TP2eYpkrgk7sLAts5tvzsFxHiDH8PmQcuH",
        "TPUPPLTYLdbW4jxwD5g2T7ystxsR9HL2mt",
        "TPW3wnwGaqB6ivYTo7hxb3rpHEoWtSNz2M",
        "TQmQgCP1ZNhMyq898purwhEz3DCEZiZAmg",
        "TQrq2p1aoAkNK94q3Q69ubJcv5nQ9y675R",
        "TQTAohrmgSyyeAS7jo1W4dUysnpmi5UbDv",
        "TQUpHbLMoYjJtcdp1CAn95b6B3jwroN2s4",
        "TR49CRc4fVrQ1Kz8MMypyzFJA4RdahkTr1",
        "TRhCAd6BsCHEBKiCCRgatgcT2gWW6vyiHW",
        "TRqNuQhyEGCLSuFL9xx2LsL7CgiAKKU4Jq",
        "TSB7M6eBpBD4RUrqdgPrTLkfzzYV39QTBo",
        "TSUYvQ5tdd3DijCD1uGunGLpftHuSZ12sQ",
        "TTdDA16hnGXvVmiek7goij5a8B4QDxTT3v",
        "TU8Z8CeUd7pnXSMHTNqRgK6Qxxxyzsba1n",
        "TUajR7CbXU6hX8n3XtNkitFAD25JvP99K6",
        "TUGzN12obTXAmFBALRHDHim7WwATfdUmmV",
        "TUMBP4f47fyu9neED1UYjT78J42eSf6xjB",
        "TVDxsCLXr9zakfMP439UcjAi639HwFkYDF",
        "TVg52CxgE67F1DJHSQnqDrVgW8TF3gUHDv",
        "TVKZKa1LDadTPm2AoAW7hp9BtdScK4gSk8",
        "TVnDFKq13XGTmdNcQnvRouaqG5dPpb48Yp",
        "TVsQxikpttN15u7vcjXKeVrtYRWbrqgPbH",
        "TVtZdz2zC51e8PUsdxnkFm9JCFHAq7cgnA",
        "TWmmZ44tN6UBAD4iEsoZo5qSAZ64E4HDZZ",
        "TWTcYYHvRAkhFHR7Spgnh4Pb5ofboroVZK",
        "TWttvCqVmiLip7PL8Aut2Hi37swqv7EmYd",
        "TWudpwxPHoJkcWG4yNU7LoYKQ3FTLcy6S8",
        "TWXVYfD7GFinLnQpv7evwMzenzXgQ8MqNg",
        "TX6CM8K1FgS2nnTEjLsKW6TAVSipFXHh5C",
        "TXDk8mbtRbXeYuMNS83CfKPaYYT8XWv9Hz",
        "TXhepqfva4WvcK6HapedmzwDjdZv8KoY6p",
        "TXLfZmQtLtLxWYNL2fxhw34JNGHB2EKeSU",
        "TXzhj9Xh8xfzerjinRyM5TfoBL7Cw5hk5d",
        "TXZMGAc3zLtkiBZZrBUyYuyQMpfcmvHokn",
        "TJv6ccth8CjNpUceKsTGieyYhcd8UrCGGH",
        "TGkXUV2Yz9zfs2cBrMuNunnKiZTV6DHihh",
        "TPgAw7EQ9LefVi9fb1xJ1cAam6i3RvHmXq",
        "TXdYNjXaHn3c1whomRpzCkaFbjfCffMFGf",
        "TPXWuDhSHWm2cTmEdKJzMBajSDh77Js2qw",
        "TH2iieRStHtzDMTPXdFcgixQLBuhrtq6p9",
        "TFf1nkfcR9vwgRcPmKAsXkMwN1FBPYJkYM",
        "TLTLSMR3aHQQkGm2cSFRHpFy2t98dhkriD",
        "TE3SKCKwukQmRU7138vnHkVNMQMb885gQg",
        "TG27ivYppJDcjwpjLdpP18xP3ZMGTYyNs3",
        "TMk8sVbkfsuyJUHxkJ4oCS1JQPEp4PNB5g",
        "TKha7zcAXZMaaWzoVmUHtvVFqr9GeiChgJ",
        "TPHvQqTXUon4oWdYaQJZvyy4YXzzSefRrQ",
        "TBdfUMrVUMkDtbtsF48KK7QdUrD9izHuES",
        "TMtCbYmfc6zQ1LSKm3eWZntjzYUVaxCzr2",
        "TTMuf1VNGVHBaZSBU54FK3tki8y5XoqvCX",
        "TZE8EcPoBMrgPy5B9ADGpd7BbRBzB6wc2a",
        "TPaBEMqiRVit72PU9res9AppMt6vDan3LZ",
        "TJKFX3vEybixnauT67ko4nh2DzwT9SPWiG",
        "TAEpKsuYsi7L4YfcAvUKtf2GogNsaSyziu",
        "TNDg1bCTeBpEP2C6mBkW5BedvbfKyY5RcX",
        "TBoiVTmcp4RQUyAp5crf3Sty4NuX42CTCB",
        "TCUtzKL7vR6KjzwRmy2uyEdUWpmiLdvJut",
        "TQGWCjq5STuPwK66UhQv5aAc13N3Y5ZCzD",
        "TWhXqbjPK2YPmeuFWkvLFq7mzyQmr5YSwH",
        "TWxWHoFnfcTccMSHgYBLjC9SyCKZ7kQHQW",
        "TDiM14q5nNVjjsodA4rXmhxSv7oXtT8mPD",
        "TBJQzdwg3bFM5kjrFeYwQLzGAG9RvF7MBh",
        "TMvvzNcnREy7qofJyqbNsoL2op7SvaaEey",
        "TD6HorqmFL3M4xL14icjNzXPNCPca4d32f",
        "TP9ZvM6kRk9kpVhpuLVvGZ1w2GzPCu7JbR",
        "TH9Y8aB8E8hiXYGbmXAM5qMTUxBe4vzb1u",
        "TJHgbEQY1pQf5KgGgxfdicj1Vr7U1mTXjB",
        "TPWRtoePcFLQNwrazxnP5LKJaajrGHq13Q",
        "TA9APgeaUEbBL4WoVxJZdPLeYXUy7EcS6n",
        "TDuva7ha2DWVtz9ELmQDAsEnBRse8VFqxV",
        "TYyC3kxzMYC1sGKpui79VAzwn992jCA6fN",
        "TQ2uLRqqHWb3LD5gfqBwj32uThNjFoSweV",
        "TPDak1rUSrTL4UHSTSLAwF8Y22Pw1cf1PE",
        "THXhuaW5wDP559oDZaQJpHVteA92qfR5Dx",
        "TNDZAf8SpiCjamZQUndBa7dw1STNkmbpvr",
        "TT2TwKeywFo5qQRiXUTTGtLchqLa4dGcAs",
        "TCdgsgwyka6LG2VzSA65wdscPauxgFg9Wv"};
    Set<ByteString> contracts = new HashSet<>();
    for (String str : contractsStr) {
      byte[] address = Arrays.copyOfRange(Commons.decodeFromBase58Check(str), 1, 21);
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
    ByteString ownershipTopic =
        ByteString.copyFrom(Hex.decode("8be0079c531659141344cd1fd0a4f28419497f9722a3daafe3b4186f6b6457e0"));

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
    topics.put(ownershipTopic, "Ownership");

    logger.info("This scripts is using to scan Rely(address) topics for the given contract.");
    logger.info("Start block num is {}, End block num is {}", startNum, endNum);

    for (int c = 0; c < 8; c++) {
      long start = startNum + (endNum - startNum) / 8 * c;
      long end = startNum + (endNum - startNum) / 8 * (c + 1) - 1;
      int thread = c;
      new Thread(() -> {
        for (long i = start; i <= end; i++) {
          Protocol.Block block = wallet.getBlockByNum(i);
          GrpcAPI.TransactionInfoList txList = wallet.getTransactionInfoByBlockNum(i);
          for (int j = 0; j < block.getTransactionsCount(); j++) {
            Protocol.Transaction tx = block.getTransactions(j);
            TransactionCapsule txCapsule = new TransactionCapsule(tx);
            ByteString owner = ByteString.copyFrom(txCapsule.getOwnerAddress());

            if (owner1.equals(owner) || owner2.equals(owner)) {
              Protocol.TransactionInfo txInfo = txList.getTransactionInfo(j);
              if (txCapsule.isCreate()) {
                logger.info("{} {} {}",
                    owner1.equals(owner) ? "Owner1" : "Owner2",
                    StringUtil.encode58Check(txInfo.getContractAddress().toByteArray()),
                    contractStore.get(txInfo.getContractAddress().toByteArray()).getInstance().getName());
              }

              for (Protocol.InternalTransaction it : txInfo.getInternalTransactionsList()) {
                if (it.getNote().equals(ByteString.copyFrom("create".getBytes()))) {
                  logger.info("{} {} {}",
                      owner1.equals(owner) ? "Owner1" : "Owner2",
                      StringUtil.encode58Check(txInfo.getContractAddress().toByteArray()),
                      "CreateByContract");
                }
              }
            }
          }

//      for (Protocol.TransactionInfo info : txList.getTransactionInfoList()) {
//        for (Protocol.TransactionInfo.Log log : info.getLogList()) {
//          if (contracts.contains(log.getAddress()) &&
//              log.getTopicsCount() > 0 && topics.containsKey(log.getTopics(0))) {
//            byte[] contract = new byte[21];
//            System.arraycopy(log.getAddress().toByteArray(), 0, contract, 1, 20);
//            contract[0] = 0x41;
//
//            byte[] address;
//            if (log.getTopicsCount() > 1) {
//              address = Arrays.copyOfRange(log.getTopics(1).toByteArray(), 11, 32);
//            } else {
//              address = Arrays.copyOfRange(log.getData().toByteArray(), 11, 32);
//            }
//            address[0] = 0x41;
//
//            logger.info("Found concerned topic - {} {} {} {}",
//                StringUtil.encode58Check(contract),
//                topics.get(log.getTopics(0)),
//                StringUtil.encode58Check(address),
//                Hex.toHexString(info.getId().toByteArray()));
//          }
//        }
//      }

          if (i % 10_000 == 0) {
            logger.info("{} Thread, Current block num is {}", thread, i);
          }
        }
      }).start();
    }

    logger.info("Finish scanning.");

  }
}
