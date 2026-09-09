package org.tron.core.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.tron.core.utils.ProposalUtil.ProposalType.ALLOW_OPTIMIZE_TVM;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Resource;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.tron.common.BaseTest;
import org.tron.common.TestConstants;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.ForkController;
import org.tron.core.ChainBaseManager;
import org.tron.core.Wallet;
import org.tron.core.capsule.ProposalCapsule;
import org.tron.core.config.Parameter.ForkBlockVersionEnum;
import org.tron.core.config.args.Args;
import org.tron.core.consensus.ProposalService;
import org.tron.core.db2.ISession;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.store.StoreFactory;
import org.tron.core.utils.ProposalUtil;
import org.tron.core.vm.config.ConfigLoader;
import org.tron.core.vm.config.VMConfig;
import org.tron.protos.Protocol.Proposal;

public class Bn128ProposalTest extends BaseTest {

  @Resource
  private Wallet wallet;

  private VMConfig.Snapshot savedGlobal;
  private boolean savedLoaderDisabled;
  private boolean savedEnergyFork;

  @BeforeClass
  public static void init() {
    Args.setParam(new String[]{"-d", dbPath()}, TestConstants.TEST_CONF);
  }

  @Before
  public void saveConfig() {
    savedGlobal = (VMConfig.Snapshot) ReflectionTestUtils.getField(VMConfig.class, "globalSnapshot");
    savedLoaderDisabled = ConfigLoader.disable;
    savedEnergyFork = CommonParameter.ENERGY_LIMIT_HARD_FORK;
    ConfigLoader.disable = false;
    VMConfig.clearLocalSnapshot();
  }

  @After
  public void restoreConfig() {
    VMConfig.setGlobalSnapshot(savedGlobal);
    ConfigLoader.disable = savedLoaderDisabled;
    CommonParameter.ENERGY_LIMIT_HARD_FORK = savedEnergyFork;
  }

  @Test
  public void proposalValidationActivationWalletAndRollback() throws Exception {
    DynamicPropertiesStore store = dbManager.getDynamicPropertiesStore();
    ForkController fork = mock(ForkController.class);
    long code = ALLOW_OPTIMIZE_TVM.getCode();
    try (ISession outer = dbManager.getRevokingStore().buildSession()) {
      store.delete("ALLOW_OPTIMIZE_TVM".getBytes(StandardCharsets.US_ASCII));
      assertEquals(0, store.getAllowOptimizeTvm());
      ConfigLoader.load(StoreFactory.getInstance(), false);
      assertFalse(VMConfig.allowOptimizeTvm());
      assertWalletValue(0L);
      assertThrows(ContractValidateException.class,
          () -> ProposalUtil.validator(store, fork, code, 1));
      when(fork.pass(ForkBlockVersionEnum.VERSION_4_8_2_2)).thenReturn(true);
      for (long value : new long[]{Long.MIN_VALUE, -1, 0, 2, Long.MAX_VALUE}) {
        assertThrows(ContractValidateException.class,
            () -> ProposalUtil.validator(store, fork, code, value));
      }
      ProposalUtil.validator(store, fork, code, 1);
      try (ISession activation = dbManager.getRevokingStore().buildSession()) {
        assertTrue(ProposalService.process(dbManager,
            new ProposalCapsule(Proposal.newBuilder().putParameters(code, 1).build())));
        assertEquals(1, store.getAllowOptimizeTvm());
        ConfigLoader.load(StoreFactory.getInstance(), false);
        assertTrue(VMConfig.allowOptimizeTvm());
        assertWalletValue(1L);
        assertThrows(ContractValidateException.class,
            () -> ProposalUtil.validator(store, fork, code, 1));
        assertThrows(ContractValidateException.class,
            () -> ProposalUtil.validator(store, fork, code, 0));
      }
      assertEquals(0, store.getAllowOptimizeTvm());
      ConfigLoader.load(StoreFactory.getInstance(), false);
      assertFalse("a revoked activation must not remain cached", VMConfig.allowOptimizeTvm());
      assertWalletValue(0L);
    }
  }

  @Test
  public void historicalConfigLoadDoesNotChangeHeadBackend() throws Exception {
    DynamicPropertiesStore store = dbManager.getDynamicPropertiesStore();
    try (ISession session = dbManager.getRevokingStore().buildSession()) {
      store.saveAllowOptimizeTvm(1);
      ConfigLoader.load(StoreFactory.getInstance(), false);
      assertTrue(VMConfig.allowOptimizeTvm());

      DynamicPropertiesStore historical = mock(DynamicPropertiesStore.class);
      ChainBaseManager historicalManager = mock(ChainBaseManager.class);
      StoreFactory historicalFactory = mock(StoreFactory.class);
      when(historicalFactory.getChainBaseManager()).thenReturn(historicalManager);
      when(historicalManager.getDynamicPropertiesStore()).thenReturn(historical);
      when(historical.getAllowOptimizeTvm()).thenReturn(0L);
      ConfigLoader.load(historicalFactory, true);
      assertFalse(VMConfig.allowOptimizeTvm());
      AtomicBoolean headEnabled = new AtomicBoolean(false);
      Thread head = new Thread(() -> headEnabled.set(VMConfig.allowOptimizeTvm()));
      head.start();
      head.join();
      assertTrue(headEnabled.get());
      VMConfig.clearLocalSnapshot();
      assertTrue(VMConfig.allowOptimizeTvm());
    }
  }

  private void assertWalletValue(long expected) {
    long value = wallet.getChainParameters().getChainParameterList().stream()
        .filter(parameter -> "getAllowOptimizeTvm".equals(parameter.getKey()))
        .findFirst().orElseThrow(() -> new AssertionError("missing chain parameter")).getValue();
    assertEquals(expected, value);
  }
}
