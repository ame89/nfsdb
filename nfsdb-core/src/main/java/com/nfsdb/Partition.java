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

package com.nfsdb;

import com.nfsdb.collections.DirectInputStream;
import com.nfsdb.collections.ObjList;
import com.nfsdb.exceptions.JournalException;
import com.nfsdb.exceptions.JournalRuntimeException;
import com.nfsdb.factory.configuration.ColumnMetadata;
import com.nfsdb.factory.configuration.JournalMetadata;
import com.nfsdb.io.sink.CharSink;
import com.nfsdb.logging.Logger;
import com.nfsdb.query.iterator.PartitionBufferedIterator;
import com.nfsdb.storage.*;
import com.nfsdb.utils.Dates;
import com.nfsdb.utils.Hash;
import com.nfsdb.utils.Interval;
import com.nfsdb.utils.Unsafe;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.Closeable;
import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

@SuppressFBWarnings({"PL_PARALLEL_LISTS", "EXS_EXCEPTION_SOFTENING_NO_CONSTRAINTS"})
public class Partition<T> implements Closeable {
    private static final Logger LOGGER = Logger.getLogger(Partition.class);
    private final Journal<T> journal;
    private final ObjList<SymbolIndexProxy<T>> indexProxies = new ObjList<>();
    private final Interval interval;
    private final int columnCount;
    private final ColumnMetadata[] columnMetadata;
    SymbolIndexProxy<T> sparseIndexProxies[];
    AbstractColumn[] columns;
    private int partitionIndex;
    private File partitionDir;
    private long lastAccessed = System.currentTimeMillis();
    private long txLimit;
    private FixedColumn timestampColumn;

    Partition(Journal<T> journal, Interval interval, int partitionIndex, long txLimit, long[] indexTxAddresses) {
        JournalMetadata<T> meta = journal.getMetadata();
        this.journal = journal;
        this.partitionIndex = partitionIndex;
        this.interval = interval;
        this.txLimit = txLimit;
        this.columnCount = meta.getColumnCount();
        this.columnMetadata = new ColumnMetadata[columnCount];
        meta.copyColumnMetadata(columnMetadata);
        setPartitionDir(new File(this.journal.getLocation(), interval.getDirName(meta.getPartitionType())), indexTxAddresses);
    }

    public void applyTx(long txLimit, long[] indexTxAddresses) {
        if (this.txLimit != txLimit) {
            this.txLimit = txLimit;
            for (int i = 0, k = indexProxies.size(); i < k; i++) {
                SymbolIndexProxy<T> proxy = indexProxies.getQuick(i);
                proxy.setTxAddress(indexTxAddresses == null ? 0 : indexTxAddresses[proxy.getColumnIndex()]);
            }
        }
    }

    public PartitionBufferedIterator<T> bufferedIterator() {
        return new PartitionBufferedIterator<>(this, 0, size() - 1);
    }

    public PartitionBufferedIterator<T> bufferedIterator(long lo, long hi) {
        return new PartitionBufferedIterator<>(this, lo, hi);
    }

    public void close() {
        if (isOpen()) {
            for (int i = 0; i < columns.length; i++) {
                if (Unsafe.arrayGet(columns, i) != null) {
                    Unsafe.arrayGet(columns, i).close();
                }
            }
            columns = null;
            LOGGER.trace("Partition %s closed", partitionDir);
        }

        for (int i = 0, k = indexProxies.size(); i < k; i++) {
            indexProxies.getQuick(i).close();
        }
    }

    public void commitColumns() {
        // have to commit columns from first to last
        // this is because size of partition is calculated by size of
        // last column. If below loop is to break in the middle partition will assume smallest
        // column size.
        for (int i = 0; i < columnCount; i++) {
            AbstractColumn col = Unsafe.arrayGet(columns, i);
            if (col != null) {
                col.commit();
            }
        }
    }

    public void compact() throws JournalException {
        if (columns == null || columns.length == 0) {
            throw new JournalException("Cannot compact closed partition: %s", this);
        }

        for (int i = 0; i < columns.length; i++) {
            if (columns[i] != null) {
                columns[i].compact();
            }
        }

        for (int i = 0, k = indexProxies.size(); i < k; i++) {
            indexProxies.getQuick(i).getIndex().compact();
        }
    }

    public FixedColumn fixCol(int i) {
        checkColumnIndex(i);
        return (FixedColumn) Unsafe.arrayGet(columns, i);
    }

    public AbstractColumn getAbstractColumn(int i) {
        checkColumnIndex(i);
        return Unsafe.arrayGet(columns, i);
    }

    public void getBin(long localRowID, int columnIndex, OutputStream s) {
        varCol(columnIndex).getBin(localRowID, s);
    }

    public DirectInputStream getBin(long localRowID, int columnIndex) {
        return varCol(columnIndex).getBin(localRowID);
    }

    public boolean getBool(long localRowID, int columnIndex) {
        return fixCol(columnIndex).getBool(localRowID);
    }

    public byte getByte(long localRowID, int columnIndex) {
        return fixCol(columnIndex).getByte(localRowID);
    }

    public double getDouble(long localRowID, int columnIndex) {
        return fixCol(columnIndex).getDouble(localRowID);
    }

    public float getFloat(long localRowID, int columnIndex) {
        return fixCol(columnIndex).getFloat(localRowID);
    }

    public CharSequence getFlyweightStr(long localRowID, int columnIndex) {
        return varCol(columnIndex).getFlyweightStr(localRowID);
    }

    public KVIndex getIndexForColumn(String columnName) throws JournalException {
        return getIndexForColumn(journal.getMetadata().getColumnIndex(columnName));
    }

    public KVIndex getIndexForColumn(final int columnIndex) throws JournalException {
        SymbolIndexProxy h = sparseIndexProxies[columnIndex];
        if (h == null) {
            throw new JournalException("There is no index for column '%s' in %s", columnMetadata[columnIndex].name, this);
        }
        return h.getIndex();
    }

    public int getInt(long localRowID, int columnIndex) {
        return fixCol(columnIndex).getInt(localRowID);
    }

    public Interval getInterval() {
        return interval;
    }

    public Journal<T> getJournal() {
        return journal;
    }

    public long getLastAccessed() {
        return lastAccessed;
    }

    public long getLong(long localRowID, int columnIndex) {
        return fixCol(columnIndex).getLong(localRowID);
    }

    public String getName() {
        return partitionDir.getName();
    }

    public File getPartitionDir() {
        return partitionDir;
    }

    public int getPartitionIndex() {
        return partitionIndex;
    }

    public void setPartitionIndex(int partitionIndex) {
        this.partitionIndex = partitionIndex;
    }

    public short getShort(long localRowID, int columnIndex) {
        return fixCol(columnIndex).getShort(localRowID);
    }

    public String getStr(long localRowID, int columnIndex) {
        checkColumnIndex(columnIndex);
        return ((VariableColumn) columns[columnIndex]).getStr(localRowID);
    }

    public void getStr(long localRowID, int columnIndex, CharSink sink) {
        checkColumnIndex(columnIndex);
        ((VariableColumn) columns[columnIndex]).getStr(localRowID, sink);
    }

    public String getSym(long localRowID, int columnIndex) {
        checkColumnIndex(columnIndex);
        int symbolIndex = ((FixedColumn) columns[columnIndex]).getInt(localRowID);
        switch (symbolIndex) {
            case SymbolTable.VALUE_IS_NULL:
            case SymbolTable.VALUE_NOT_FOUND:
                return null;
            default:
                return columnMetadata[columnIndex].symbolTable.value(symbolIndex);
        }
    }

    public FixedColumn getTimestampColumn() {
        if (timestampColumn == null) {
            throw new JournalRuntimeException("There is no timestamp column in: " + this);
        }
        return timestampColumn;
    }

    public long indexOf(long timestamp, BSearchType type) {
        return getTimestampColumn().bsearchEdge(timestamp, type);
    }

    public long indexOf(long timestamp, BSearchType type, long lo, long hi) {
        return getTimestampColumn().bsearchEdge(timestamp, type, lo, hi);
    }

    public boolean isOpen() {
        return columns != null;
    }

    public Partition<T> open() throws JournalException {
        access();
        if (columns == null) {
            open0();
        }
        return this;
    }

    public T read(long localRowID) {
        T obj = journal.newObject();
        read(localRowID, obj);
        return obj;
    }

    public void read(long localRowID, T obj) {
        for (int i = 0; i < columnCount; i++) {
            ColumnMetadata m;
            if (journal.getInactiveColumns().get(i) || (m = Unsafe.arrayGet(columnMetadata, i)).offset == 0) {
                continue;
            }

            switch (m.type) {
                case BOOLEAN:
                    Unsafe.getUnsafe().putBoolean(obj, m.offset, ((FixedColumn) Unsafe.arrayGet(columns, i)).getBool(localRowID));
                    break;
                case BYTE:
                    Unsafe.getUnsafe().putByte(obj, m.offset, ((FixedColumn) Unsafe.arrayGet(columns, i)).getByte(localRowID));
                    break;
                case DOUBLE:
                    Unsafe.getUnsafe().putDouble(obj, m.offset, ((FixedColumn) Unsafe.arrayGet(columns, i)).getDouble(localRowID));
                    break;
                case FLOAT:
                    Unsafe.getUnsafe().putFloat(obj, m.offset, ((FixedColumn) Unsafe.arrayGet(columns, i)).getFloat(localRowID));
                    break;
                case INT:
                    Unsafe.getUnsafe().putInt(obj, m.offset, ((FixedColumn) Unsafe.arrayGet(columns, i)).getInt(localRowID));
                    break;
                case LONG:
                case DATE:
                    Unsafe.getUnsafe().putLong(obj, m.offset, ((FixedColumn) Unsafe.arrayGet(columns, i)).getLong(localRowID));
                    break;
                case SHORT:
                    Unsafe.getUnsafe().putShort(obj, m.offset, ((FixedColumn) Unsafe.arrayGet(columns, i)).getShort(localRowID));
                    break;
                case STRING:
                    Unsafe.getUnsafe().putObject(obj, m.offset, ((VariableColumn) Unsafe.arrayGet(columns, i)).getStr(localRowID));
                    break;
                case SYMBOL:
                    Unsafe.getUnsafe().putObject(obj, m.offset, m.symbolTable.value(((FixedColumn) Unsafe.arrayGet(columns, i)).getInt(localRowID)));
                    break;
                case BINARY:
                    readBin(localRowID, obj, i, m);
            }
        }
    }

    public void rebuildIndexes() throws JournalException {
        if (!isOpen()) {
            throw new JournalException("Cannot rebuild indexes in closed partition: %s", this);
        }
        for (int i = 0; i < columnCount; i++) {
            if (Unsafe.arrayGet(columnMetadata, i).indexed) {
                rebuildIndex(i);
            }
        }
    }

    public long size() {
        if (!isOpen()) {
            throw new JournalRuntimeException("Closed partition: %s", this);
        }

        if (txLimit != Journal.TX_LIMIT_EVAL) {
            return txLimit;
        }

        long sz = 0;
        for (int i = columns.length - 1; i > -1; i--) {
            AbstractColumn c = Unsafe.arrayGet(columns, i);
            if (c != null) {
                sz = c.size();
                break;
            }
        }

        txLimit = sz;
        return sz;
    }

    @Override
    public String toString() {
        return "Partition{" +
                "partitionIndex=" + partitionIndex +
                ", open=" + isOpen() +
                ", partitionDir=" + partitionDir +
                ", interval=" + interval +
                ", lastAccessed=" + Dates.toString(lastAccessed) +
                '}';
    }

    public void updateIndexes(long oldSize, long newSize) {
        if (oldSize < newSize) {
            try {
                for (int n = 0, k = indexProxies.size(); n < k; n++) {
                    SymbolIndexProxy<T> proxy = indexProxies.getQuick(n);
                    KVIndex index = proxy.getIndex();
                    FixedColumn col = fixCol(proxy.getColumnIndex());
                    for (long i = oldSize; i < newSize; i++) {
                        index.add(col.getInt(i), i);
                    }
                    index.commit();
                }
            } catch (JournalException e) {
                throw new JournalRuntimeException(e);
            }
        }
    }

    public VariableColumn varCol(int i) {
        checkColumnIndex(i);
        return (VariableColumn) Unsafe.arrayGet(columns, i);
    }

    Partition<T> access() {
        switch (journal.getMetadata().getPartitionType()) {
            case NONE:
                return this;
            default:
                long t = System.currentTimeMillis();
                if (lastAccessed < t) {
                    lastAccessed = t;
                }
                return this;
        }
    }

    void append(Iterator<T> it) throws JournalException {
        while (it.hasNext()) {
            append(it.next());
        }
    }

    void append(T obj) throws JournalException {

        try {
            for (int i = 0; i < columnCount; i++) {
                ColumnMetadata m = Unsafe.arrayGet(columnMetadata, i);
                switch (m.type) {
                    case INT:
                        int v = Unsafe.getUnsafe().getInt(obj, m.offset);
                        if (m.indexed) {
                            sparseIndexProxies[i].getIndex().add(v & m.distinctCountHint, ((FixedColumn) Unsafe.arrayGet(columns, i)).putInt(v));
                        } else {
                            ((FixedColumn) Unsafe.arrayGet(columns, i)).putInt(v);
                        }
                        break;
                    case STRING:
                        String s = (String) Unsafe.getUnsafe().getObject(obj, m.offset);
                        long offset = ((VariableColumn) Unsafe.arrayGet(columns, i)).putStr(s);
                        if (m.indexed) {
                            sparseIndexProxies[i].getIndex().add(
                                    s == null ? SymbolTable.VALUE_IS_NULL : Hash.boundedHash(s, m.distinctCountHint)
                                    , offset
                            );
                        }
                        break;
                    case SYMBOL:
                        int key;
                        String sym = (String) Unsafe.getUnsafe().getObject(obj, m.offset);
                        if (sym == null) {
                            key = SymbolTable.VALUE_IS_NULL;
                        } else {
                            key = m.symbolTable.put(sym);
                        }
                        if (m.indexed) {
                            sparseIndexProxies[i].getIndex().add(key, ((FixedColumn) Unsafe.arrayGet(columns, i)).putInt(key));
                        } else {
                            ((FixedColumn) Unsafe.arrayGet(columns, i)).putInt(key);
                        }
                        break;
                    case BINARY:
                        appendBin(obj, i, m);
                        break;
                    default:
                        ((FixedColumn) Unsafe.arrayGet(columns, i)).copy(obj, m.offset);
                        break;
                }

                Unsafe.arrayGet(columns, i).commit();
            }

            applyTx(Journal.TX_LIMIT_EVAL, null);
        } catch (Throwable e) {
            ((JournalWriter) this.journal).rollback();
            throw e;
        }
    }

    private void appendBin(T obj, int i, ColumnMetadata meta) {
        ByteBuffer buf = (ByteBuffer) Unsafe.getUnsafe().getObject(obj, meta.offset);
        if (buf == null) {
            ((VariableColumn) Unsafe.arrayGet(columns, i)).putNull();
        } else {
            ((VariableColumn) Unsafe.arrayGet(columns, i)).putBin(buf);
        }
    }

    private void checkColumnIndex(int i) {
        if (i > -1 && i < columnCount) {
            return;
        }
        throw new JournalRuntimeException("Invalid column index: %d in %s", i, this);
    }

    private void clearTx() {
        applyTx(Journal.TX_LIMIT_EVAL, null);
    }

    void commit() throws JournalException {
        for (int i = 0, k = indexProxies.size(); i < k; i++) {
            indexProxies.getQuick(i).getIndex().commit();
        }
    }

    @SuppressWarnings("unchecked")
    private void createSymbolIndexProxies(long[] indexTxAddresses) {
        indexProxies.clear();
        if (sparseIndexProxies == null || sparseIndexProxies.length != columnCount) {
            sparseIndexProxies = new SymbolIndexProxy[columnCount];
        }

        for (int i = 0; i < columnCount; i++) {
            if (Unsafe.arrayGet(columnMetadata, i).indexed) {
                SymbolIndexProxy<T> proxy = new SymbolIndexProxy<>(this, i, indexTxAddresses == null ? 0 : indexTxAddresses[i]);
                indexProxies.add(proxy);
                sparseIndexProxies[i] = proxy;
            } else {
                sparseIndexProxies[i] = null;
            }
        }
    }

    void force() throws JournalException {
        for (int i = 0, k = indexProxies.size(); i < k; i++) {
            indexProxies.getQuick(i).getIndex().force();
        }

        if (columns != null) {
            for (int i = 0; i < columns.length; i++) {
                AbstractColumn column = Unsafe.arrayGet(columns, i);
                if (column != null) {
                    column.force();
                }
            }
        }
    }

    void getIndexPointers(long[] pointers) throws JournalException {
        for (int i = 0, k = indexProxies.size(); i < k; i++) {
            SymbolIndexProxy<T> proxy = indexProxies.getQuick(i);
            pointers[proxy.getColumnIndex()] = proxy.getIndex().getTxAddress();
        }
    }

    @SuppressFBWarnings({"PRMC_POSSIBLY_REDUNDANT_METHOD_CALLS"})
    private void open0() throws JournalException {
        columns = new AbstractColumn[journal.getMetadata().getColumnCount()];

        for (int i = 0; i < columns.length; i++) {
            switch (Unsafe.arrayGet(columnMetadata, i).type) {
                case STRING:
                case BINARY:
                    Unsafe.arrayPut(columns, i,
                            new VariableColumn(
                                    new MemoryFile(
                                            new File(partitionDir, Unsafe.arrayGet(columnMetadata, i).name + ".d"),
                                            Unsafe.arrayGet(columnMetadata, i).bitHint, journal.getMode()
                                    ),
                                    new MemoryFile(
                                            new File(partitionDir, Unsafe.arrayGet(columnMetadata, i).name + ".i"),
                                            Unsafe.arrayGet(columnMetadata, i).indexBitHint,
                                            journal.getMode()
                                    )
                            )
                    );
                    break;
                default:
                    Unsafe.arrayPut(columns, i,
                            new FixedColumn(
                                    new MemoryFile(
                                            new File(partitionDir, Unsafe.arrayGet(columnMetadata, i).name + ".d"),
                                            Unsafe.arrayGet(columnMetadata, i).bitHint,
                                            journal.getMode()
                                    ),
                                    Unsafe.arrayGet(columnMetadata, i).size
                            )
                    );
            }
        }

        int tsIndex = journal.getMetadata().getTimestampIndex();
        if (tsIndex > -1) {
            timestampColumn = fixCol(tsIndex);
        }
    }

    private void readBin(long localRowID, T obj, int i, ColumnMetadata m) {
        int size = ((VariableColumn) Unsafe.arrayGet(columns, i)).getBinSize(localRowID);
        ByteBuffer buf = (ByteBuffer) Unsafe.getUnsafe().getObject(obj, m.offset);
        if (size == -1) {
            if (buf != null) {
                buf.clear();
            }
        } else {
            if (buf == null || buf.capacity() < size) {
                buf = ByteBuffer.allocate(size);
                Unsafe.getUnsafe().putObject(obj, m.offset, buf);
            }

            if (buf.remaining() < size) {
                buf.rewind();
            }
            buf.limit(size);
            ((VariableColumn) Unsafe.arrayGet(columns, i)).getBin(localRowID, buf);
            buf.flip();
        }
    }

    /**
     * Rebuild the index of a column using the default keyCountHint and recordCountHint values.
     *
     * @param columnIndex the column index
     * @throws com.nfsdb.exceptions.JournalException if the operation fails
     */
    private void rebuildIndex(int columnIndex) throws JournalException {
        JournalMetadata<T> meta = journal.getMetadata();
        rebuildIndex(columnIndex,
                columnMetadata[columnIndex].distinctCountHint,
                meta.getRecordHint(),
                meta.getTxCountHint());
    }

    /**
     * Rebuild the index of a column.
     *
     * @param columnIndex     the column index
     * @param keyCountHint    the key count hint override
     * @param recordCountHint the record count hint override
     * @throws com.nfsdb.exceptions.JournalException if the operation fails
     */
    private void rebuildIndex(int columnIndex, int keyCountHint, int recordCountHint, int txCountHint) throws JournalException {
        final long time = LOGGER.isInfoEnabled() ? System.nanoTime() : 0L;

        getIndexForColumn(columnIndex).close();

        File base = new File(partitionDir, columnMetadata[columnIndex].name);
        KVIndex.delete(base);

        try (KVIndex index = new KVIndex(base, keyCountHint, recordCountHint, txCountHint, JournalMode.APPEND, 0)) {
            FixedColumn col = fixCol(columnIndex);
            for (long localRowID = 0, sz = size(); localRowID < sz; localRowID++) {
                index.add(col.getInt(localRowID), localRowID);
            }
            index.commit();
        }

        LOGGER.debug("REBUILT %s [%dms]", base, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - time));
    }

    final void setPartitionDir(File partitionDir, long[] indexTxAddresses) {
        boolean create = partitionDir != null && !partitionDir.equals(this.partitionDir);
        this.partitionDir = partitionDir;
        if (create) {
            createSymbolIndexProxies(indexTxAddresses);
        }
    }

    void truncate(long newSize) throws JournalException {
        if (isOpen() && size() > newSize) {
            for (int i = 0, k = indexProxies.size(); i < k; i++) {
                indexProxies.getQuick(i).getIndex().truncate(newSize);
            }
            for (int i = 0; i < columns.length; i++) {
                if (Unsafe.arrayGet(columns, i) != null) {
                    Unsafe.arrayGet(columns, i).truncate(newSize);
                }
            }

            commitColumns();
            clearTx();
        }
    }
}
