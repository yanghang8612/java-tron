package org.tron.core.service;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.DelegatedResourceAccountIndexCapsule;
import org.tron.core.capsule.DelegatedResourceCapsule;
import org.tron.core.store.AccountStore;
import org.tron.core.store.DelegatedResourceAccountIndexStore;
import org.tron.core.store.DelegatedResourceStore;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.protos.Protocol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j(topic = "TopDelegatorService")
public class TopDelegatorService {

    private AccountStore accountStore;

    private final DelegatedResourceStore delegatedResourceStore;

    private final DelegatedResourceAccountIndexStore delegatedResourceAccountIndexStore;

    private final DynamicPropertiesStore dynamicPropertiesStore;

    private final Set<ByteString> stakers = new HashSet<>();

    @Autowired
    public TopDelegatorService(DelegatedResourceStore delegatedResourceStore,
                               DelegatedResourceAccountIndexStore delegatedResourceAccountIndexStore,
                               DynamicPropertiesStore dynamicPropertiesStore) {
        this.delegatedResourceStore = delegatedResourceStore;
        this.delegatedResourceAccountIndexStore = delegatedResourceAccountIndexStore;
        this.dynamicPropertiesStore = dynamicPropertiesStore;
    }

    public void init(AccountStore accountStore) {
        logger.info("TopDelegatorService init");

        this.accountStore = accountStore;
        AtomicLong count = new AtomicLong(0);
        accountStore.forEach(e -> {
            if (e.getValue().getAllStakedTRXForEnergy() > 0) {
                stakers.add(ByteString.copyFrom(e.getKey()));

                if (count.incrementAndGet() % 10_000 == 0) {
                    logger.info("TopDelegatorService initializing, Staker size: {}", count.get());
                }
            }
        });
        logger.info("TopDelegatorService init finish, Staker size: {}", count.get());
    }

    public void addStaker(ByteString address) {
        stakers.add(address);
    }

    public void removeStaker(ByteString address) {
        stakers.remove(address);
    }

    public void doStats() {
        logger.info("TopDelegatorService doStats, Staker size: {}", stakers.size());

        List<AccountCapsule> stakerCaps = new ArrayList<>();
        for (ByteString address : stakers) {
            AccountCapsule accountCapsule = accountStore.get(address.toByteArray());
            if (accountCapsule != null) {
                stakerCaps.add(accountCapsule);
            }
        }

        stakerCaps.sort(Comparator.comparingLong(AccountCapsule::getAllStakedTRXForEnergy).reversed());

        for (int i = 0; i < 1000 && i < stakerCaps.size(); i++) {
            byte[] staker = stakerCaps.get(i).getAddress().toByteArray();
            logger.info("TopDelegatorService doStats, Staker: {}, Staked TRX for Energy: {}",
                ByteString.copyFrom(staker).toStringUtf8(), stakerCaps.get(i).getAllStakedTRXForEnergy());
            Map<ByteString, Long> delegateAmountMap = new HashMap<>();

            DelegatedResourceAccountIndexCapsule v1IndexCap = delegatedResourceAccountIndexStore.getIndex(staker);
            if (v1IndexCap != null) {
                for (ByteString to : v1IndexCap.getToAccountsList()) {
                    byte[] dbKey = DelegatedResourceCapsule
                        .createDbKey(staker, to.toByteArray());
                    DelegatedResourceCapsule v1DelegateCap = delegatedResourceStore.get(dbKey);
                    long delegateAmount = v1DelegateCap != null ? v1DelegateCap.getFrozenBalanceForEnergy() : 0;
                    delegateAmountMap.put(to, delegateAmountMap.getOrDefault(to, 0L) + delegateAmount);
                }
            }

            DelegatedResourceAccountIndexCapsule v2IndexCap = delegatedResourceAccountIndexStore.getV2Index(staker);
            if (v2IndexCap != null) {
                for (ByteString to : v2IndexCap.getToAccountsList()) {
                    byte[] dbKey = DelegatedResourceCapsule
                        .createDbKeyV2(staker, to.toByteArray(), false);
                    DelegatedResourceCapsule v2UnlockDelegateCap = delegatedResourceStore.get(dbKey);
                    long v2UnlockDelegateAmount = v2UnlockDelegateCap != null ? v2UnlockDelegateCap.getFrozenBalanceForEnergy() : 0;
                    delegateAmountMap.put(to, delegateAmountMap.getOrDefault(to, 0L) + v2UnlockDelegateAmount);

                    dbKey = DelegatedResourceCapsule
                        .createDbKeyV2(staker, to.toByteArray(), true);
                    DelegatedResourceCapsule v2LockDelegateCap = delegatedResourceStore.get(dbKey);
                    long v2LockDelegateAmount = v2LockDelegateCap != null ? v2LockDelegateCap.getFrozenBalanceForEnergy() : 0;
                    delegateAmountMap.put(to, delegateAmountMap.getOrDefault(to, 0L) + v2LockDelegateAmount);
                }
            }

            Protocol.StakerStat.Builder stakerStatBuilder = Protocol.StakerStat.newBuilder();
            stakerStatBuilder.setAddress(ByteString.copyFrom(staker));
            stakerStatBuilder.setStakedTrxForEnergy(stakerCaps.get(i).getAllStakedTRXForEnergy());
            stakerStatBuilder.setMeu(dynamicPropertiesStore.getMaxEnergyUtilization(stakerCaps.get(i)));
            for (Map.Entry<ByteString, Long> entry : delegateAmountMap.entrySet()) {
                stakerStatBuilder.addDelegateStats(
                    Protocol.StakerStat.DelegateStat.newBuilder()
                        .setTo(entry.getKey())
                        .setAmount(entry.getValue())
                        .setMeu(dynamicPropertiesStore.getMaxEnergyUtilization(accountStore.get(entry.getKey().toByteArray())))
                        .build()
                );
            }
            dynamicPropertiesStore.recordStakerStat(staker, stakerStatBuilder.build().toByteArray());
        }

        logger.info("TopDelegatorService doStats finish");
    }
}
