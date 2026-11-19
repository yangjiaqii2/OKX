package com.example.quant.crypto.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeepAnalysisTargetAllocatorTest {

    private static class Rounds {
        final DeepAnalysisTargetAllocator allocator = new DeepAnalysisTargetAllocator();
        final java.util.Map<String, DeepAnalysisTargetAllocator.Visit> history = new java.util.HashMap<>();
        long time = 1;
        DeepAnalysisTargetAllocator.Allocation allocate(List<String> ranked, int limit, int rotation) {
            return allocate(ranked, limit, rotation, Set.of());
        }
        DeepAnalysisTargetAllocator.Allocation allocate(List<String> ranked, int limit, int rotation, Set<String> pins) {
            return allocator.allocate(ranked, limit, rotation, pins, history, time);
        }
        void commit(DeepAnalysisTargetAllocator.Allocation allocation) {
            for (String id : allocation.instIds()) history.put(id, new DeepAnalysisTargetAllocator.Visit(time, time));
            time++;
        }
    }

    private static List<String> ranked(int count) {
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add("SYM" + i + "-USDT-SWAP");
        }
        return out;
    }

    @Test
    @DisplayName("静态名单上，成交额排名靠后的合格候选在有界轮数内会被轮换覆盖")
    void lowTurnoverQualifiedCandidateIsCoveredWithinBoundedRounds() {
        List<String> passed = ranked(100);
        Rounds allocator = new Rounds();

        String tailLast = passed.get(99);
        int headSize = 30 - 6;
        int tailSize = 100 - headSize;
        int expectedRounds = (tailSize + 6 - 1) / 6;

        boolean covered = false;
        int roundsUsed = 0;
        for (int round = 0; round < expectedRounds; round++) {
            DeepAnalysisTargetAllocator.Allocation allocation = allocator.allocate(passed, 30, 6);
            allocator.commit(allocation);
            roundsUsed++;
            if (allocation.instIds().contains(tailLast)) {
                covered = true;
                break;
            }
        }

        assertThat(covered)
                .as("静态名单上末位合格候选必须在 ceil(tail/quota)=%d 轮内被分析，实际用了 %d 轮",
                        expectedRounds, roundsUsed)
                .isTrue();
    }

    @Test
    @DisplayName("静态名单上，轮换在有界轮数内覆盖全部合格候选")
    void rotationCoversEveryQualifiedCandidateWithoutGaps() {
        List<String> passed = ranked(100);
        Rounds allocator = new Rounds();
        Set<String> seen = new LinkedHashSet<>();

        int rounds = (100 - (30 - 6) + 6 - 1) / 6;
        for (int round = 0; round < rounds; round++) {
            DeepAnalysisTargetAllocator.Allocation allocation = allocator.allocate(passed, 30, 6);
            allocator.commit(allocation);
            seen.addAll(allocation.instIds());
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(passed);
    }

    @Test
    @DisplayName("任何一轮分配都不突破 deepAnalysisLimit，且不产生重复标的")
    void allocationNeverExceedsDeepAnalysisLimitAndHasNoDuplicates() {
        List<String> passed = ranked(100);
        Rounds allocator = new Rounds();

        for (int round = 0; round < 40; round++) {
            DeepAnalysisTargetAllocator.Allocation allocation = allocator.allocate(passed, 30, 6);
            allocator.commit(allocation);

            assertThat(allocation.instIds()).hasSizeLessThanOrEqualTo(30);
            assertThat(allocation.instIds()).doesNotHaveDuplicates();
            assertThat(allocation.pinnedCount() + allocation.headCount() + allocation.rotationCount())
                    .isEqualTo(allocation.instIds().size());
        }
    }

    @Test
    @DisplayName("成交额头部名额保持不变：每轮都包含成交额最高的候选")
    void volumeHeadIsAlwaysRetained() {
        List<String> passed = ranked(100);
        Rounds allocator = new Rounds();

        for (int round = 0; round < 25; round++) {
            DeepAnalysisTargetAllocator.Allocation allocation = allocator.allocate(passed, 30, 6);
            allocator.commit(allocation);

            assertThat(allocation.instIds()).containsAll(passed.subList(0, 30 - 6));
            assertThat(allocation.headCount()).isEqualTo(30 - 6);
        }
    }

    @Test
    @DisplayName("合格候选数量不超过上限时全部分析，不启用轮换")
    void everyCandidateIsAnalyzedWhenPassedFitsWithinLimit() {
        List<String> passed = ranked(12);
        Rounds allocator = new Rounds();

        DeepAnalysisTargetAllocator.Allocation allocation = allocator.allocate(passed, 30, 6);

        assertThat(allocation.instIds()).containsExactlyElementsOf(passed);
        assertThat(allocation.rotationCount()).isZero();
    }

    @Test
    @DisplayName("确定性：相同输入与相同覆盖记录产生完全相同的分配序列")
    void identicalInputAndCursorProduceIdenticalAllocationSequence() {
        List<String> passed = ranked(100);
        Rounds left = new Rounds();
        Rounds right = new Rounds();

        for (int round = 0; round < 20; round++) {
            DeepAnalysisTargetAllocator.Allocation leftAllocation = left.allocate(passed, 30, 6);
            DeepAnalysisTargetAllocator.Allocation rightAllocation = right.allocate(passed, 30, 6);
            left.commit(leftAllocation);
            right.commit(rightAllocation);

            assertThat(leftAllocation.instIds()).containsExactlyElementsOf(rightAllocation.instIds());
        }
    }

    @Test
    @DisplayName("尚未记录的分配不改变覆盖历史：扫描超时中断不会跳过未分析的候选")
    void allocationWithoutCommitDoesNotAdvanceCursor() {
        List<String> passed = ranked(100);
        Rounds allocator = new Rounds();

        DeepAnalysisTargetAllocator.Allocation first = allocator.allocate(passed, 30, 6);
        DeepAnalysisTargetAllocator.Allocation retry = allocator.allocate(passed, 30, 6);

        assertThat(retry.instIds()).containsExactlyElementsOf(first.instIds());

        allocator.commit(retry);
        DeepAnalysisTargetAllocator.Allocation afterCommit = allocator.allocate(passed, 30, 6);

        assertThat(afterCommit.instIds()).isNotEqualTo(first.instIds());
    }

    @Test
    @DisplayName("轮换配额为 0 时退化为纯成交额截断（旧行为可完整复现）")
    void zeroRotationQuotaReproducesPureTurnoverTruncation() {
        List<String> passed = ranked(100);
        Rounds allocator = new Rounds();

        DeepAnalysisTargetAllocator.Allocation allocation = allocator.allocate(passed, 30, 0);

        assertThat(allocation.instIds()).containsExactlyElementsOf(passed.subList(0, 30));
        assertThat(allocation.rotationCount()).isZero();
    }

    @Test
    @DisplayName("轮换配额被限制为不得吃光成交额头部名额")
    void rotationQuotaIsClampedSoTheVolumeHeadNeverBecomesEmpty() {
        List<String> passed = ranked(100);
        Rounds allocator = new Rounds();

        DeepAnalysisTargetAllocator.Allocation allocation = allocator.allocate(passed, 30, 999);

        assertThat(allocation.headCount()).isGreaterThanOrEqualTo(1);
        assertThat(allocation.instIds()).hasSize(30);
        assertThat(allocation.instIds().get(0)).isEqualTo(passed.get(0));
    }

    // ------------------------------------------------------------------
    // pinned：已存在的等待入场意图必须每轮拿到数据刷新机会
    // ------------------------------------------------------------------

    @Test
    @DisplayName("已存在等待意图的合约每轮都拿到深度分析名额，不依赖成交额排名或轮换游标")
    void pinnedInstIdIsAllocatedEveryRoundRegardlessOfTurnoverRank() {
        List<String> passed = ranked(100);
        String pinned = passed.get(95);
        Rounds allocator = new Rounds();

        for (int round = 0; round < 12; round++) {
            DeepAnalysisTargetAllocator.Allocation allocation =
                    allocator.allocate(passed, 30, 6, Set.of(pinned));
            assertThat(allocation.instIds())
                    .as("第 %d 轮：等待中的合约必须拿到刷新机会", round)
                    .contains(pinned);
            assertThat(allocation.pinnedCount()).isEqualTo(1);
            allocator.commit(allocation);
        }
    }

    @Test
    @DisplayName("固定名额从总量内扣，不扩大 deepAnalysisLimit，请求预算不变")
    void pinnedSlotsAreTakenFromTheSameLimitAndNeverExpandIt() {
        List<String> passed = ranked(100);
        Rounds allocator = new Rounds();

        Set<String> pinned = new LinkedHashSet<>(List.of(passed.get(70), passed.get(80), passed.get(90)));
        DeepAnalysisTargetAllocator.Allocation allocation = allocator.allocate(passed, 30, 6, pinned);

        assertThat(allocation.instIds()).hasSize(30);
        assertThat(allocation.instIds()).doesNotHaveDuplicates();
        assertThat(allocation.pinnedCount() + allocation.headCount() + allocation.rotationCount())
                .isEqualTo(30);
    }

    @Test
    @DisplayName("固定名额不会与成交额头部或轮换名额重复占用")
    void pinnedInstIdAlreadyInTurnoverHeadIsNotDoubleCounted() {
        List<String> passed = ranked(100);
        Rounds allocator = new Rounds();

        DeepAnalysisTargetAllocator.Allocation allocation =
                allocator.allocate(passed, 30, 6, Set.of(passed.get(0)));

        assertThat(allocation.instIds()).hasSize(30);
        assertThat(allocation.instIds()).doesNotHaveDuplicates();
        assertThat(allocation.instIds()).contains(passed.get(0));
    }

    @Test
    @DisplayName("不在硬过滤通过名单内的等待合约拿不到名额：本层不绕过资格判定")
    void pinnedInstIdOutsideThePassedUniverseIsNotForcedIn() {
        List<String> passed = ranked(40);
        Rounds allocator = new Rounds();

        DeepAnalysisTargetAllocator.Allocation allocation =
                allocator.allocate(passed, 30, 6, Set.of("GONE-USDT-SWAP"));

        assertThat(allocation.instIds()).doesNotContain("GONE-USDT-SWAP");
        assertThat(allocation.pinnedCount()).isZero();
        assertThat(allocation.instIds()).hasSize(30);
    }

    @Test
    @DisplayName("固定名额多于总量时仍然被总量截断，绝不突破请求预算")
    void pinnedCountExceedingLimitIsStillTruncatedToLimit() {
        List<String> passed = ranked(100);
        Rounds allocator = new Rounds();

        Set<String> pinned = new LinkedHashSet<>(passed.subList(50, 100));
        DeepAnalysisTargetAllocator.Allocation allocation = allocator.allocate(passed, 30, 6, pinned);

        assertThat(allocation.instIds()).hasSize(30);
        assertThat(allocation.pinnedCount()).isLessThanOrEqualTo(30);
    }

    @Test
    void restoredHistoryCovers229WithinElevenRoundsEvenWhenTailOrderChanges() {
        List<String> passed = ranked(229);
        var history = new java.util.HashMap<String, DeepAnalysisTargetAllocator.Visit>();
        for (int round = 1; round <= 11; round++) {
            java.util.Collections.rotate(passed.subList(10, passed.size()), 17);
            var allocation = new DeepAnalysisTargetAllocator().allocate(passed, 30, 20, Set.of(), history, round);
            for (String id : allocation.instIds()) {
                history.put(id, new DeepAnalysisTargetAllocator.Visit(round, round));
            }
        }
        assertThat(history.keySet()).containsExactlyInAnyOrderElementsOf(passed);
    }

    @Test
    void failedTargetYieldsDuringCooldownAndBecomesEligibleForRetry() {
        var allocator = new DeepAnalysisTargetAllocator();
        var history = java.util.Map.of("B", new DeepAnalysisTargetAllocator.Visit(1000, 0),
                "C", new DeepAnalysisTargetAllocator.Visit(900, 900));
        var ranked = List.of("A", "B", "C");
        assertThat(allocator.allocate(ranked, 2, 1, Set.of(), history, 1001).instIds()).containsExactly("A", "C");
        assertThat(allocator.allocate(ranked, 2, 1, Set.of(), history, 301000).instIds()).containsExactly("A", "B");
    }

    @Test
    @DisplayName("尾部因新币进入而变长后，按原名单算出的覆盖上界不再够用")
    void growingTheTailMakesTheOriginalCoverageBoundInsufficient() {
        Rounds allocator = new Rounds();
        String victim = "VICTIM-USDT-SWAP";
        int originalBound = (76 + 6 - 1) / 6;

        List<String> passed = new ArrayList<>(ranked(99));
        passed.add(victim);

        boolean covered = false;
        for (int round = 0; round < originalBound; round++) {
            if (round == 1) {
                List<String> grown = new ArrayList<>();
                grown.addAll(ranked(24));
                for (int i = 0; i < 100; i++) {
                    grown.add("EXTRA" + i + "-USDT-SWAP");
                }
                grown.addAll(ranked(99).subList(24, 99));
                grown.add(victim);
                passed = grown;
            }
            DeepAnalysisTargetAllocator.Allocation allocation = allocator.allocate(passed, 30, 6);
            allocator.commit(allocation);
            if (allocation.instIds().contains(victim)) {
                covered = true;
            }
        }

        assertThat(covered)
                .as("原 100 名单的 ceil(76/6)=%d 轮上界，在尾部插入 100 个新币后不再覆盖末位 victim",
                        originalBound)
                .isFalse();
    }

    @Test
    @DisplayName("固定名额不改变无 pinned 时的既有分配结果")
    void emptyPinnedSetReproducesTheExistingAllocation() {
        List<String> passed = ranked(100);

        Rounds legacy = new Rounds();
        Rounds withPinned = new Rounds();

        for (int round = 0; round < 5; round++) {
            DeepAnalysisTargetAllocator.Allocation a = legacy.allocate(passed, 30, 6);
            DeepAnalysisTargetAllocator.Allocation b = withPinned.allocate(passed, 30, 6, Set.of());
            assertThat(b.instIds()).containsExactlyElementsOf(a.instIds());
            legacy.commit(a);
            withPinned.commit(b);
        }
    }
}
