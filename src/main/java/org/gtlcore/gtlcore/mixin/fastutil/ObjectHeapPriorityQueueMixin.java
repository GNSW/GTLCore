package org.gtlcore.gtlcore.mixin.fastutil;

import it.unimi.dsi.fastutil.objects.ObjectArrays;
import it.unimi.dsi.fastutil.objects.ObjectHeapPriorityQueue;
import it.unimi.dsi.fastutil.objects.ObjectHeaps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Comparator;
import java.util.NoSuchElementException;

@Mixin(value = ObjectHeapPriorityQueue.class, remap = false)
public abstract class ObjectHeapPriorityQueueMixin<K> {

    @Shadow
    protected transient K[] heap;
    @Shadow
    protected int size;
    @Shadow
    protected Comparator<? super K> c;

    /**
     * @author gtlcore
     * @reason synchronize the fastutil heap used by Glodium's highlight queue.
     */
    @Overwrite(remap = false)
    public void enqueue(K k) {
        synchronized (this) {
            if (size >= heap.length) {
                heap = ObjectArrays.grow(heap, size + 1);
            }
            heap[size++] = k;
            ObjectHeaps.upHeap(heap, size, size - 1, (Comparator<K>) c);
        }
    }

    /**
     * @author gtlcore
     * @reason synchronize the fastutil heap used by Glodium's highlight queue.
     */
    @Overwrite(remap = false)
    public K dequeue() {
        synchronized (this) {
            if (size == 0) {
                throw new NoSuchElementException();
            }
            K result = heap[0];
            heap[0] = heap[--size];
            heap[size] = null;
            if (size != 0) {
                ObjectHeaps.downHeap(heap, size, 0, (Comparator<K>) c);
            }
            return result;
        }
    }
}
