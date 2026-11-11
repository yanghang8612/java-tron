package org.tron.consensus.dpos;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.util.Collections;
import org.junit.Test;
import org.tron.common.utils.ReflectUtils;
import org.tron.common.utils.Sha256Hash;
import org.tron.consensus.ConsensusDelegate;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.store.TrackerStore;

public class MaintenanceTimestampTest {

  @Test
  public void recordsTheMaintenanceBlockTime() {
    MaintenanceManager manager = spy(new MaintenanceManager());
    ConsensusDelegate delegate = mock(ConsensusDelegate.class);
    DynamicPropertiesStore properties = mock(DynamicPropertiesStore.class);
    TrackerStore tracker = mock(TrackerStore.class);
    ReflectUtils.setFieldValue(manager, "consensusDelegate", delegate);
    ReflectUtils.setFieldValue(manager, "trackerStore", tracker);
    when(delegate.getNextMaintenanceTime()).thenReturn(1000L);
    when(delegate.getActiveWitnesses()).thenReturn(Collections.emptyList());
    when(delegate.getDynamicPropertiesStore()).thenReturn(properties);
    when(properties.allowChangeDelegation()).thenReturn(true);
    when(properties.getCurrentCycleNumber()).thenReturn(43L);
    doNothing().when(manager).doMaintenance();
    manager.applyBlock(new BlockCapsule(2, Sha256Hash.ZERO_HASH, 2000, ByteString.EMPTY));
    verify(tracker).saveCycleEndTimestamp(42, 2000);
  }
}
