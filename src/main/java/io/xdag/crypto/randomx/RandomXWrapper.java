/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2022-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.xdag.crypto.randomx;

import java.util.ArrayList;
import java.util.Arrays;

import com.google.common.collect.Lists;
import com.sun.jna.Memory;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import lombok.Builder;

@Builder
public final class RandomXWrapper {

    private PointerByReference cache;
    private PointerByReference dataset;

    protected final ArrayList<RandomXVM> vms = Lists.newArrayList();

    boolean fastInit;

    private Pointer memory;
    private int keySize;

    private int flagsValue = 0;
    private final ArrayList<Flag> flags;

    /**
     * Initialize randomX cache or dataset for a specific key
     * @param key The key to initialize randomX with. (generally a hash)
     */
    public void init(byte[] key) {
        if(flags.contains(Flag.FULL_MEM)) {
            setDataset(key);
        } else {
            setCache(key);
        }
    }

    /**
     * Create a new VM for the current randomX instance
     * RandomX must be initialized before calling this.
     * @return RandomX_VM an Object representing the resulting VM
     */
    public RandomXVM createVM() {
        RandomXVM vm = new RandomXVM(RandomXJNA.INSTANCE.randomx_create_vm(flagsValue, cache, dataset), this);
        vms.add(vm);
        return vm;
    }

    /**
     * Initialize randomX cache for a specific key
     * @param key The key to initialize randomX with. (generally a hash)
     */
    private void setCache(byte[] key) {
        if(this.memory != null && Arrays.equals(key, this.memory.getByteArray(0, keySize)))
            return;

        PointerByReference newCache = RandomXJNA.INSTANCE.randomx_alloc_cache(flagsValue);

        this.memory = new Memory(key.length);
        this.memory.write(0, key, 0, key.length);
        keySize = key.length;

        RandomXJNA.INSTANCE.randomx_init_cache(newCache, this.memory, new NativeSize(key.length));

        if(cache != null) {
            RandomXJNA.INSTANCE.randomx_release_cache(cache);
        }

        cache = newCache;
    }

    /**
     * Initialize randomX dataset for a specific key
     * @param key The key to initialize randomX with. (generally a hash)
     */
    private void setDataset(byte[] key) {
        if(this.memory != null && Arrays.equals(key, this.memory.getByteArray(0, keySize))) {
            return;
        }

        //Initialized cache is required to create dataset, first initialize it
        setCache(key);

        PointerByReference newDataset;

        //Allocate memory for dataset
        if(flags.contains(Flag.LARGE_PAGES)) {
            newDataset = RandomXJNA.INSTANCE.randomx_alloc_dataset(Flag.LARGE_PAGES.getValue());
        } else {
            newDataset = RandomXJNA.INSTANCE.randomx_alloc_dataset(0);
        }

        if(fastInit) {
            /*
             * If fastInit enabled use all cores to create the dataset
             * by equally distributing work between them
             */
            ArrayList<Thread> threads = new ArrayList<>();
            int threadCount = Runtime.getRuntime().availableProcessors();
            long perThread = RandomXJNA.INSTANCE.randomx_dataset_item_count().longValue() / threadCount;
            long remainder = RandomXJNA.INSTANCE.randomx_dataset_item_count().longValue() % threadCount;

            long startItem = 0;
            for (int i = 0; i < threadCount; ++i) {
                long count = perThread + (i == threadCount - 1 ? remainder : 0);
                long start = startItem;
                Thread thread = new Thread(() -> RandomXJNA.INSTANCE.randomx_init_dataset(newDataset, cache, new NativeLong(start), new NativeLong(count)));
                thread.start();
                threads.add(thread);
                startItem += count;
            }

            //wait for every thread to terminate execution (ie: dataset is initialised)
            for(Thread thread : threads) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

        }   else {
            RandomXJNA.INSTANCE.randomx_init_dataset(newDataset, cache, new NativeLong(0), RandomXJNA.INSTANCE.randomx_dataset_item_count());
        }

        //Release the cache that was used to create the dataset
        RandomXJNA.INSTANCE.randomx_release_cache(cache);
        cache = null;

        //If there is a old dataset release it before remplacing by the new one
        if(dataset != null) {
            RandomXJNA.INSTANCE.randomx_release_dataset(dataset);
        }
        dataset = newDataset;
    }

    /**
     * Change current randomX key by reinitializing dataset or cache
     * @param key The key to initialize randomX with. (generally a hash)
     */
    public void changeKey(byte[] key) {
        if(flags.contains(Flag.FULL_MEM)) {
            setDataset(key);
            for(RandomXVM vm : vms) {
                RandomXJNA.INSTANCE.randomx_vm_set_dataset(vm.getPointer(), dataset);
            }
        }   else    {
            setCache(key);
            for(RandomXVM vm : vms) {
                RandomXJNA.INSTANCE.randomx_vm_set_cache(vm.getPointer(), cache);
            }
        }

    }

    /**
     * Destroy all VMs and clear cache and dataset
     */
    public void destroy() {
        for(RandomXVM vm : vms) {
            RandomXJNA.INSTANCE.randomx_destroy_vm(vm.getPointer());
        }
        vms.clear();
        if(cache != null) {
            RandomXJNA.INSTANCE.randomx_release_cache(cache);
            cache = null;
        }
        if(dataset != null) {
            RandomXJNA.INSTANCE.randomx_release_dataset(dataset);
            dataset = null;
        }
    }

    public enum Flag {
        DEFAULT(0),
        LARGE_PAGES(1),
        HARD_AES(2),
        FULL_MEM(4),
        JIT(8),
        SECURE(16),
        ARGON2_SSSE3(32),
        ARGON2_AVX2(64),
        ARGON2(96);

        private final int value;

        Flag(int value){
            this.value = value;
        }

        public int getValue() {
            return value;
        }

    }

}
