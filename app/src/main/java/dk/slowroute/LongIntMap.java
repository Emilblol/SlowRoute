package dk.slowroute;

import java.util.Arrays;

public final class LongIntMap {
    private static final long EMPTY = Long.MIN_VALUE;
    private long[] keys;
    private int[] values;
    private int size;
    private int mask;

    public LongIntMap(int expected) {
        int capacity = 16;
        while (capacity < expected * 2) {
            capacity <<= 1;
        }
        allocate(capacity);
    }

    private void allocate(int capacity) {
        keys = new long[capacity];
        values = new int[capacity];
        Arrays.fill(keys, EMPTY);
        mask = capacity - 1;
        size = 0;
    }

    private int slot(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & mask;
    }

    public int get(long key) {
        int i = slot(key);
        while (true) {
            long k = keys[i];
            if (k == EMPTY) {
                return -1;
            }
            if (k == key) {
                return values[i];
            }
            i = (i + 1) & mask;
        }
    }

    public void put(long key, int value) {
        if ((size + 1) * 2 > keys.length) {
            grow();
        }
        int i = slot(key);
        while (true) {
            long k = keys[i];
            if (k == EMPTY) {
                keys[i] = key;
                values[i] = value;
                size++;
                return;
            }
            if (k == key) {
                values[i] = value;
                return;
            }
            i = (i + 1) & mask;
        }
    }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        allocate(oldKeys.length * 2);
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }
}
