/*******************************************************************************
 *  _  _ ___ ___     _ _
 * | \| | __/ __| __| | |__
 * | .` | _|\__ \/ _` | '_ \
 * |_|\_|_| |___/\__,_|_.__/
 *
 * Copyright (c) 2014-2015. The NFSdb project and its contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.nfsdb.concurrent;

public abstract class AbstractSSequence implements Sequence {
    private final WaitStrategy waitStrategy;
    Barrier barrier = OpenBarrier.INSTANCE;

    AbstractSSequence(WaitStrategy waitStrategy) {
        this.waitStrategy = waitStrategy;
    }

    AbstractSSequence() {
        this(null);
    }

    @Override
    public void followedBy(Barrier barrier) {
        this.barrier = barrier;
    }

    @Override
    public long waitForNext() {
        long r;
        while ((r = next()) < 0) {
            waitStrategy.await((int) r);
        }
        return r;
    }

    @Override
    public void signal() {
        if (waitStrategy != null) {
            waitStrategy.signal();
        }
    }
}
