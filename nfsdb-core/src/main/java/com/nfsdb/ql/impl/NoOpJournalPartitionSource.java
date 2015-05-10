/*
 * Copyright (c) 2014. Vlad Ilyushchenko
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

package com.nfsdb.ql.impl;

import com.nfsdb.Journal;
import com.nfsdb.collections.AbstractImmutableIterator;
import com.nfsdb.factory.configuration.JournalMetadata;
import com.nfsdb.ql.PartitionCursor;
import com.nfsdb.ql.PartitionSlice;
import com.nfsdb.ql.PartitionSource;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class NoOpJournalPartitionSource extends AbstractImmutableIterator<PartitionSlice> implements PartitionSource, PartitionCursor {

    private final Journal journal;

    public NoOpJournalPartitionSource(Journal journal) {
        this.journal = journal;
        unprepare();
    }

    @Override
    public JournalMetadata getMetadata() {
        return journal.getMetadata();
    }

    @Override
    public PartitionCursor prepareCursor() {
        return this;
    }

    @Override
    public final void unprepare() {
    }

    @Override
    public boolean hasNext() {
        return false;
    }

    @SuppressFBWarnings({"IT_NO_SUCH_ELEMENT"})
    @Override
    public PartitionSlice next() {
        return null;
    }
}