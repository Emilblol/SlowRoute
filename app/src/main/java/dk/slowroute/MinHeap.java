package dk.slowroute;

import java.util.Arrays;

final class MinHeap {
    private int[] items;
    private float[] keys;
    private int size;

    MinHeap(int capacity) {
        items = new int[Math.max(16, capacity)];
        keys = new float[items.length];
    }

    boolean isEmpty() {
        return size == 0;
    }

    void push(int item, float key) {
        if (size == items.length) {
            items = Arrays.copyOf(items, size * 2);
            keys = Arrays.copyOf(keys, size * 2);
        }
        int i = size++;
        while (i > 0) {
            int parent = (i - 1) >> 1;
            if (keys[parent] <= key) {
                break;
            }
            items[i] = items[parent];
            keys[i] = keys[parent];
            i = parent;
        }
        items[i] = item;
        keys[i] = key;
    }

    int pop() {
        int top = items[0];
        size--;
        if (size > 0) {
            int item = items[size];
            float key = keys[size];
            int i = 0;
            while (true) {
                int child = 2 * i + 1;
                if (child >= size) {
                    break;
                }
                if (child + 1 < size && keys[child + 1] < keys[child]) {
                    child++;
                }
                if (keys[child] >= key) {
                    break;
                }
                items[i] = items[child];
                keys[i] = keys[child];
                i = child;
            }
            items[i] = item;
            keys[i] = key;
        }
        return top;
    }
}
