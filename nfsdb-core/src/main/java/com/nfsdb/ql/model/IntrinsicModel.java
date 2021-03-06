/*
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
 */

package com.nfsdb.ql.model;

import com.nfsdb.collections.CharSequenceHashSet;
import com.nfsdb.collections.IntList;
import com.nfsdb.collections.Mutable;
import com.nfsdb.collections.ObjectPoolFactory;
import com.nfsdb.ql.impl.IntervalSource;
import com.nfsdb.utils.Dates;

public class IntrinsicModel implements Mutable {
    public static final IntrinsicModelFactory FACTORY = new IntrinsicModelFactory();
    public final CharSequenceHashSet keyValues = new CharSequenceHashSet();
    public final IntList keyValuePositions = new IntList();
    public String keyColumn;
    public long intervalLo = Long.MIN_VALUE;
    public long intervalHi = Long.MAX_VALUE;
    public ExprNode filter;
    public long millis = Long.MIN_VALUE;
    public IntervalSource intervalSource;
    public IntrinsicValue intrinsicValue = IntrinsicValue.UNDEFINED;
    public boolean keyValuesIsLambda = false;

    private IntrinsicModel() {
    }

    @Override
    public void clear() {
        keyColumn = null;
        keyValues.clear();
        keyValuePositions.clear();
        clearInterval();
        filter = null;
        millis = Long.MIN_VALUE;
        intervalSource = null;
        intrinsicValue = IntrinsicValue.UNDEFINED;
        keyValuesIsLambda = false;
    }

    public void clearInterval() {
        this.intervalLo = Long.MIN_VALUE;
        this.intervalHi = Long.MAX_VALUE;
    }

    public void overlapInterval(long lo, long hi) {
        if (hi < intervalLo || lo > intervalHi) {
            intrinsicValue = IntrinsicValue.FALSE;
        } else {
            if (lo > intervalLo) {
                intervalLo = lo;
            }

            if (hi < intervalHi) {
                intervalHi = hi;
            }
        }
    }

    @Override
    public String toString() {
        return "IntrinsicModel{" +
                "keyValues=" + keyValues +
                ", keyColumn='" + keyColumn + '\'' +
                ", intervalLo=" + Dates.toString(intervalLo) +
                ", intervalHi=" + Dates.toString(intervalHi) +
                ", filter=" + filter +
                ", millis=" + millis +
                '}';
    }

    public static final class IntrinsicModelFactory implements ObjectPoolFactory<IntrinsicModel> {
        @Override
        public IntrinsicModel newInstance() {
            return new IntrinsicModel();
        }
    }

}
