package com.quarty.housamoembedtrans.translation.request;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits an API scene only at root scene-item boundaries.
 *
 * <p>A root choice or if node is always kept whole. Its options,
 * following_text arrays, and nested control-flow nodes can never become a
 * gradient boundary.
 */
public final class TranslationGradientPlanner {
    private static final int FIRST_BLOCK_TARGET = 30;

    public static final class Block {
        private final int index;
        private final int firstRootIndex;
        private final int lastRootIndex;
        private final List<Integer> seqs;

        private Block(
            int index,
            int firstRootIndex,
            int lastRootIndex,
            List<Integer> seqs
        ) {
            this.index = index;
            this.firstRootIndex = firstRootIndex;
            this.lastRootIndex = lastRootIndex;
            this.seqs = Collections.unmodifiableList(
                new ArrayList<>(seqs)
            );
        }

        public int getIndex() {
            return index;
        }

        public int getFirstRootIndex() {
            return firstRootIndex;
        }

        public int getLastRootIndex() {
            return lastRootIndex;
        }

        public List<Integer> getSeqs() {
            return seqs;
        }

        public int size() {
            return seqs.size();
        }

        public int getFirstSeq() {
            return seqs.get(0);
        }

        public int getLastSeq() {
            return seqs.get(seqs.size() - 1);
        }

        public boolean containsSeq(int seq) {
            return Collections.binarySearch(seqs, seq) >= 0;
        }
    }

    private static final class RootUnit {
        private final int rootIndex;
        private final List<Integer> seqs;

        private RootUnit(int rootIndex, List<Integer> seqs) {
            this.rootIndex = rootIndex;
            this.seqs = seqs;
        }

        private int lastSeq() {
            return seqs.get(seqs.size() - 1);
        }
    }

    private TranslationGradientPlanner() {
        throw new AssertionError("No instances");
    }

    public static List<Block> plan(JSONObject request, int configuredCount) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        if (configuredCount < 1) {
            throw new IllegalArgumentException(
                "configured gradient count must be positive"
            );
        }

        JSONArray rootItems = request.optJSONArray("scene_items");
        if (rootItems == null) {
            throw new IllegalArgumentException(
                "request.scene_items must be an array"
            );
        }

        List<RootUnit> units = collectRootUnits(rootItems);
        if (units.isEmpty()) {
            throw new IllegalArgumentException(
                "request.scene_items contains no translatable seq"
            );
        }

        int total = units.get(units.size() - 1).lastSeq();
        int requestedBlocks = Math.min(configuredCount, units.size());
        if (requestedBlocks <= 1 || total <= FIRST_BLOCK_TARGET) {
            return Collections.singletonList(
                blockFromUnits(0, 0, units.size() - 1, units)
            );
        }

        List<Integer> idealEnds = idealCumulativeEnds(
            total,
            requestedBlocks
        );
        List<Integer> snappedUnitEnds = new ArrayList<>();
        int previous = -1;
        for (Integer idealEnd : idealEnds) {
            int unitEnd = unitContainingSeq(units, idealEnd);
            if (unitEnd > previous) {
                snappedUnitEnds.add(unitEnd);
                previous = unitEnd;
            }
        }
        int finalUnit = units.size() - 1;
        if (snappedUnitEnds.isEmpty()
            || snappedUnitEnds.get(snappedUnitEnds.size() - 1) != finalUnit) {
            snappedUnitEnds.add(finalUnit);
        }

        List<Block> blocks = new ArrayList<>();
        int firstUnit = 0;
        for (Integer lastUnit : snappedUnitEnds) {
            if (lastUnit < firstUnit) {
                continue;
            }
            blocks.add(
                blockFromUnits(
                    blocks.size(),
                    firstUnit,
                    lastUnit,
                    units
                )
            );
            firstUnit = lastUnit + 1;
        }
        return Collections.unmodifiableList(blocks);
    }

    private static List<Integer> idealCumulativeEnds(
        int total,
        int blockCount
    ) {
        List<Integer> ends = new ArrayList<>();
        int firstSize = Math.min(FIRST_BLOCK_TARGET, total);
        ends.add(firstSize);

        int remaining = total - firstSize;
        long weightSum = 0L;
        for (int weight = 2; weight <= blockCount; weight++) {
            weightSum += weight;
        }

        long consumedWeight = 0L;
        for (int weight = 2; weight < blockCount; weight++) {
            consumedWeight += weight;
            int cumulative = firstSize + (int) Math.round(
                remaining * (double) consumedWeight / (double) weightSum
            );
            ends.add(Math.max(firstSize, Math.min(cumulative, total)));
        }
        ends.add(total);
        return ends;
    }

    /**
     * Snapping is forward-only: if the target seq is inside a choice or if,
     * its complete outer root node becomes the end of the block.
     */
    private static int unitContainingSeq(
        List<RootUnit> units,
        int targetSeq
    ) {
        for (int index = 0; index < units.size(); index++) {
            if (units.get(index).lastSeq() >= targetSeq) {
                return index;
            }
        }
        return units.size() - 1;
    }

    private static Block blockFromUnits(
        int blockIndex,
        int firstUnit,
        int lastUnit,
        List<RootUnit> units
    ) {
        List<Integer> seqs = new ArrayList<>();
        for (int unitIndex = firstUnit;
             unitIndex <= lastUnit;
             unitIndex++) {
            seqs.addAll(units.get(unitIndex).seqs);
        }
        return new Block(
            blockIndex,
            units.get(firstUnit).rootIndex,
            units.get(lastUnit).rootIndex,
            seqs
        );
    }

    private static List<RootUnit> collectRootUnits(JSONArray rootItems) {
        List<RootUnit> units = new ArrayList<>();
        int previousSeq = 0;
        for (int rootIndex = 0;
             rootIndex < rootItems.length();
             rootIndex++) {
            JSONObject item = rootItems.optJSONObject(rootIndex);
            if (item == null) {
                throw new IllegalArgumentException(
                    "scene_items[" + rootIndex + "] must be an object"
                );
            }

            List<Integer> seqs = new ArrayList<>();
            collectSeqs(item, seqs);
            if (seqs.isEmpty()) {
                continue;
            }
            for (Integer seq : seqs) {
                if (seq != previousSeq + 1) {
                    throw new IllegalArgumentException(
                        "scene seqs must be continuous in traversal order; "
                            + "expected="
                            + (previousSeq + 1)
                            + " actual="
                            + seq
                    );
                }
                previousSeq = seq;
            }
            units.add(new RootUnit(rootIndex, seqs));
        }
        return units;
    }

    private static void collectSeqs(JSONObject item, List<Integer> out) {
        String type = item.optString("type", "");
        if ("text".equals(type)) {
            int seq = item.optInt("seq", 0);
            if (seq <= 0) {
                throw new IllegalArgumentException(
                    "text seq must be positive"
                );
            }
            out.add(seq);
            return;
        }

        if ("choice".equals(type)) {
            JSONArray branches = item.optJSONArray("branches");
            if (branches == null) {
                throw new IllegalArgumentException(
                    "choice.branches must be an array"
                );
            }
            for (int branchIndex = 0;
                 branchIndex < branches.length();
                 branchIndex++) {
                JSONObject branch = branches.optJSONObject(branchIndex);
                if (branch == null) {
                    throw new IllegalArgumentException(
                        "choice branch must be an object"
                    );
                }
                collectSeqs(branch.optJSONArray("options"), out);
                collectSeqs(branch.optJSONArray("following_text"), out);
            }
            return;
        }

        if ("if".equals(type)) {
            collectSeqs(item.optJSONArray("following_text"), out);
            return;
        }

        throw new IllegalArgumentException(
            "unsupported scene item type: " + type
        );
    }

    private static void collectSeqs(JSONArray items, List<Integer> out) {
        if (items == null) {
            throw new IllegalArgumentException(
                "nested scene items must be an array"
            );
        }
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) {
                throw new IllegalArgumentException(
                    "nested scene item must be an object"
                );
            }
            collectSeqs(item, out);
        }
    }
}
