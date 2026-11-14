package com.iruixos.reapwright.treefeller;

import java.util.random.RandomGenerator;

/** Pure per-log tool-wear logic. One roll per log; never charges foliage/attachments. */
final class AxeWear {
    private AxeWear() { }

    static boolean[] roll(int logs, int unbreaking, RandomGenerator random) {
        if (logs < 0) throw new IllegalArgumentException("logs must be nonnegative");
        int level = Math.max(0, unbreaking);
        boolean[] wears = new boolean[logs];
        for (int i = 0; i < logs; i++)
            wears[i] = level == 0 || random.nextInt(level + 1) == 0;
        return wears;
    }

    static int count(boolean[] wears, int limit) {
        int total = 0;
        for (int i = 0; i < Math.min(limit, wears.length); i++)
            if (wears[i]) total++;
        return total;
    }
}
