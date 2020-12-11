package org.tron.core.vm;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EnergyCost {

  private static EnergyCost instance = null;
  /* backwards compatibility, remove eventually */
  public final int STEP = 1;
  /* backwards compatibility, remove eventually */
  public final static int SSTORE = 300;
  public final static int ZEROSTEP = 0;
  public final static int QUICKSTEP = 2;
  public final static int FASTESTSTEP = 3;
  public final static int FASTSTEP = 5;
  public final static int MIDSTEP = 8;
  public final static int SLOWSTEP = 10;
  public final static int EXTSTEP = 20;
  public final static int GENESISENERGYLIMIT = 1000000;
  public final static int MINENERGYLIMIT = 125000;
  public final static int BALANCE = 20;
  public final static int SHA3 = 30;
  public final static int SHA3_WORD = 6;
  public final static int SLOAD = 50;
  public final static int STOP = 0;
  public final static int SUICIDE = 0;
  public final static int CLEAR_SSTORE = 5000;
  public final static int SET_SSTORE = 20000;
  public final static int RESET_SSTORE = 5000;
  public final static int REFUND_SSTORE = 15000;
  public final static int CREATE = 32000;
  public final static int JUMPDEST = 1;
  public final static int CREATE_DATA_BYTE = 5;
  public final static int CALL = 40;
  public final static int STIPEND_CALL = 2300;
  public final static int VT_CALL = 9000;  //value transfer call
  public final static int NEW_ACCT_CALL = 25000;  //new account call
  public final static int MEMORY = 3;
  public final static int SUICIDE_REFUND = 24000;
  public final static int QUAD_COEFF_DIV = 512;
  public final static int CREATE_DATA = 200;
  public final static int TX_NO_ZERO_DATA = 68;
  public final static int TX_ZERO_DATA = 4;
  public final static int TRANSACTION = 21000;
  public final static int TRANSACTION_CREATE_CONTRACT = 53000;
  public final static int LOG_ENERGY = 375;
  public final static int LOG_DATA_ENERGY = 8;
  public final static int LOG_TOPIC_ENERGY = 375;
  public final static int COPY_ENERGY = 3;
  public final static int EXP_ENERGY = 10;
  public final static int EXP_BYTE_ENERGY = 10;
  public final static int IDENTITY = 15;
  public final static int IDENTITY_WORD = 3;
  public final static int RIPEMD160 = 600;
  public final static int RIPEMD160_WORD = 120;
  public final static int SHA256 = 60;
  public final static int SHA256_WORD = 12;
  public final static int EC_RECOVER = 3000;
  public final static int EXT_CODE_SIZE = 20;
  public final static int EXT_CODE_COPY = 20;
  public final static int EXT_CODE_HASH = 400;
  public final static int NEW_ACCT_SUICIDE = 0;
  public final static int STAKE_UNSTAKE = 35000;
  public final static int WITHDRAW_REWARD = 25000;
  public final static int TOKEN_ISSUE = 25000;
  public final static int UPDATE_ASSET = 5000;

  public static EnergyCost getInstance() {
    if (instance == null) {
      instance = new EnergyCost();
    }

    return instance;
  }

  public int getSTEP() {
    return STEP;
  }

  public int getSSTORE() {
    return SSTORE;
  }

  public int getZEROSTEP() {
    return ZEROSTEP;
  }

  public int getQUICKSTEP() {
    return QUICKSTEP;
  }

  public int getFASTESTSTEP() {
    return FASTESTSTEP;
  }

  public int getFASTSTEP() {
    return FASTSTEP;
  }

  public int getMIDSTEP() {
    return MIDSTEP;
  }

  public int getSLOWSTEP() {
    return SLOWSTEP;
  }

  public int getEXTSTEP() {
    return EXTSTEP;
  }

  public int getGENESISENERGYLIMIT() {
    return GENESISENERGYLIMIT;
  }

  public int getMINENERGYLIMIT() {
    return MINENERGYLIMIT;
  }

  public int getBALANCE() {
    return BALANCE;
  }

  public int getSHA3() {
    return SHA3;
  }

  public int getSHA3_WORD() {
    return SHA3_WORD;
  }

  public int getSLOAD() {
    return SLOAD;
  }

  public int getSTOP() {
    return STOP;
  }

  public int getSUICIDE() {
    return SUICIDE;
  }

  public int getCLEAR_SSTORE() {
    return CLEAR_SSTORE;
  }

  public int getSET_SSTORE() {
    return SET_SSTORE;
  }

  public int getRESET_SSTORE() {
    return RESET_SSTORE;
  }

  public int getREFUND_SSTORE() {
    return REFUND_SSTORE;
  }

  public int getCREATE() {
    return CREATE;
  }

  public int getJUMPDEST() {
    return JUMPDEST;
  }

  public int getCREATE_DATA_BYTE() {
    return CREATE_DATA_BYTE;
  }

  public int getCALL() {
    return CALL;
  }

  public int getSTIPEND_CALL() {
    return STIPEND_CALL;
  }

  public int getVT_CALL() {
    return VT_CALL;
  }

  public int getNEW_ACCT_CALL() {
    return NEW_ACCT_CALL;
  }

  public int getNEW_ACCT_SUICIDE() {
    return NEW_ACCT_SUICIDE;
  }

  public int getMEMORY() {
    return MEMORY;
  }

  public int getSUICIDE_REFUND() {
    return SUICIDE_REFUND;
  }

  public int getQUAD_COEFF_DIV() {
    return QUAD_COEFF_DIV;
  }

  public int getCREATE_DATA() {
    return CREATE_DATA;
  }

  public int getTX_NO_ZERO_DATA() {
    return TX_NO_ZERO_DATA;
  }

  public int getTX_ZERO_DATA() {
    return TX_ZERO_DATA;
  }

  public int getTRANSACTION() {
    return TRANSACTION;
  }

  public int getTRANSACTION_CREATE_CONTRACT() {
    return TRANSACTION_CREATE_CONTRACT;
  }

  public int getLOG_ENERGY() {
    return LOG_ENERGY;
  }

  public int getLOG_DATA_ENERGY() {
    return LOG_DATA_ENERGY;
  }

  public int getLOG_TOPIC_ENERGY() {
    return LOG_TOPIC_ENERGY;
  }

  public int getCOPY_ENERGY() {
    return COPY_ENERGY;
  }

  public int getEXP_ENERGY() {
    return EXP_ENERGY;
  }

  public int getEXP_BYTE_ENERGY() {
    return EXP_BYTE_ENERGY;
  }

  public int getIDENTITY() {
    return IDENTITY;
  }

  public int getIDENTITY_WORD() {
    return IDENTITY_WORD;
  }

  public int getRIPEMD160() {
    return RIPEMD160;
  }

  public int getRIPEMD160_WORD() {
    return RIPEMD160_WORD;
  }

  public int getSHA256() {
    return SHA256;
  }

  public int getSHA256_WORD() {
    return SHA256_WORD;
  }

  public int getEC_RECOVER() {
    return EC_RECOVER;
  }

  public int getEXT_CODE_SIZE() {
    return EXT_CODE_SIZE;
  }

  public int getEXT_CODE_COPY() {
    return EXT_CODE_COPY;
  }

  public int getEXT_CODE_HASH() {
    return EXT_CODE_HASH;
  }

  public int getStakeAndUnstake() {
    return STAKE_UNSTAKE;
  }

  public int getWithdrawReward() {
    return WITHDRAW_REWARD;
  }

  public int getTokenIssue() {
    return TOKEN_ISSUE;
  }

  public int getUpdateAsset() {
    return UPDATE_ASSET;
  }

  public static int[] costs = new int[64];
  static {
    costs[62] = 3;
  }

  public static void main(String[] args){
    int i;
    long ts;
    long res = 0;
    EnergyCost cost = EnergyCost.getInstance();
    int total = 1000000000;

    int[] _costs = EnergyCost.costs;
    for (res = 0, i = total, ts = System.nanoTime(); i > 0; i--){
      res = res + _costs[62];
    }
    logger.warn("2.0 result {}, timer {}", res, (System.nanoTime() - ts) / 1000_000.0);

    for (res = 0, i = total, ts = System.nanoTime(); i > 0; i--){
      res = res + _costs[62];
    }
    logger.warn("2.1 result {}, timer {}", res, (System.nanoTime() - ts) / 1000_000.0);


    for (res = 0, i = total, ts = System.nanoTime(); i > 0; i--){
      res = res + cost.getIDENTITY_WORD();
    }

    logger.warn("1.0 result {}, timer {}", res, (System.nanoTime() - ts) / 1000_000.0);

    for (res = 0, i = total, ts = System.nanoTime(); i > 0; i--){
      res = res + cost.getIDENTITY_WORD();
    }
    logger.warn("1.1 result {}, timer {}", res, (System.nanoTime() - ts) / 1000_000.0);


    for (res = 0, i = total, ts = System.nanoTime(); i > 0; i--){
      res = res + _costs[62];
    }
    logger.warn("2.2 result {}, timer {}", res, (System.nanoTime() - ts) / 1000_000.0);

    for (res = 0, i = total, ts = System.nanoTime(); i > 0; i--){
      res = res + _costs[62];
    }
    logger.warn("2.3 result {}, timer {}", res, (System.nanoTime() - ts) / 1000_000.0);

    for (res = 0, i = total, ts = System.nanoTime(); i > 0; i--){
      res = res + cost.getIDENTITY_WORD();
    }

    logger.warn("1.2 result {}, timer {}", res, (System.nanoTime() - ts) / 1000_000.0);

    for (res = 0, i = total, ts = System.nanoTime(); i > 0; i--){
      res = res + cost.getIDENTITY_WORD();
    }
    logger.warn("1.3 result {}, timer {}", res, (System.nanoTime() - ts) / 1000_000.0);

  }
}
