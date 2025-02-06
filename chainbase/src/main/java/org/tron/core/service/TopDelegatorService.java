package org.tron.core.service;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.utils.StringUtil;
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

import static org.tron.core.config.Parameter.ChainConstant.TRX_PRECISION;

@Component
@Slf4j(topic = "TopDelegatorService")
public class TopDelegatorService {

    private AccountStore accountStore;

    private final DelegatedResourceStore delegatedResourceStore;

    private final DelegatedResourceAccountIndexStore delegatedResourceAccountIndexStore;

    private final DynamicPropertiesStore dynamicPropertiesStore;

    private final Set<ByteString> stakers = new HashSet<>();

    private final Map<ByteString, AccountCapsule> stakerCaps = new HashMap<>();

    private final Map<ByteString, Long> accountMEUs = new HashMap<>();

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
        this.dynamicPropertiesStore.removeMEUs();

        AtomicLong count = new AtomicLong(0);
        accountStore.forEach(e -> {
            if (e.getValue().getAllStakedTRXForEnergy() > 0) {
                stakers.add(ByteString.copyFrom(e.getKey()));
                stakerCaps.put(ByteString.copyFrom(e.getKey()), e.getValue());

                if (count.incrementAndGet() % 10_000 == 0) {
                    logger.info("TopDelegatorService initializing, Staker size: {}", count.get());
                }
            }
        });
        logger.info("TopDelegatorService init finish, Staker size: {}", count.get());
    }

    public void addStaker(AccountCapsule accountCap) {
        stakers.add(accountCap.getAddress());
        stakerCaps.put(accountCap.getAddress(), accountCap);
    }

    public void removeStaker(AccountCapsule accountCap) {
        stakers.remove(accountCap.getAddress());
        stakerCaps.remove(accountCap.getAddress());
    }

    public void updateStaker(AccountCapsule accountCapsule) {
        stakerCaps.put(accountCapsule.getAddress(), accountCapsule);
    }

    public long getMEU(ByteString address) {
        if (!accountMEUs.containsKey(address)) {
            AccountCapsule accountCap = accountStore.get(address.toByteArray());
            return calcMaxEnergyUtilization(accountCap);
        }
        return accountMEUs.get(address);
    }

    public void updateMEU(AccountCapsule accountCap) {
        long meu = calcMaxEnergyUtilization(accountCap);
        if (meu > accountMEUs.getOrDefault(accountCap.getAddress(), 0L)) {
            accountMEUs.put(accountCap.getAddress(), meu);
        }
    }

    private long calcMaxEnergyUtilization(AccountCapsule accountCap) {
        long currentUsage = accountCap.getRealEnergyUsage();
        long availableEnergy = (long) ((double) accountCap.getAllFrozenBalanceForEnergy() / TRX_PRECISION
            * dynamicPropertiesStore.getTotalEnergyCurrentLimit() / dynamicPropertiesStore.getTotalEnergyWeight());
        if (availableEnergy == 0) {
            return -1;
        }
        return currentUsage * 10_000 / availableEnergy;
    }

    public void doStats() {
        logger.info("TopDelegatorService doStats, Staker size: {}", stakers.size());

        List<AccountCapsule> stakerList = new ArrayList<>();
        for (ByteString address : stakers) {
            stakerList.add(stakerCaps.get(address));
        }

        logger.info("TopDelegatorService finish get stakerCaps, Staker size: {}", stakerList.size());

        stakerList.sort(Comparator.comparingLong(AccountCapsule::getAllStakedTRXForEnergy).reversed());

        logger.info("TopDelegatorService finish sort stakerCaps");

        for (int i = 0; i < 1000 && i < stakerList.size(); i++) {
            byte[] staker = stakerList.get(i).getAddress().toByteArray();
            logger.info("TopDelegatorService doStats, Staker: {}, Staked TRX for Energy: {}",
                StringUtil.encode58Check(staker), stakerList.get(i).getAllStakedTRXForEnergy());
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
            stakerStatBuilder.setStakedTrxForEnergy(stakerList.get(i).getAllStakedTRXForEnergy());
            stakerStatBuilder.setMeu(getMEU(ByteString.copyFrom(staker)));
            for (Map.Entry<ByteString, Long> entry : delegateAmountMap.entrySet()) {
                stakerStatBuilder.addDelegateStats(
                    Protocol.StakerStat.DelegateStat.newBuilder()
                        .setTo(entry.getKey())
                        .setAmount(entry.getValue())
                        .setMeu(getMEU(entry.getKey()))
                        .build()
                );
            }
            dynamicPropertiesStore.recordStakerStat(staker, stakerStatBuilder.build().toByteArray());
        }

        logger.info("TopDelegatorService doStats finish");
        accountMEUs.clear();
    }
}
