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

package com.nfsdb.io.parser.listener;

import com.nfsdb.collections.ObjList;
import com.nfsdb.factory.configuration.ColumnMetadata;
import com.nfsdb.factory.configuration.RecordColumnMetadata;
import com.nfsdb.io.ImportManager;
import com.nfsdb.io.ImportSchema;
import com.nfsdb.io.ImportedColumnMetadata;
import com.nfsdb.io.ImportedColumnType;
import com.nfsdb.io.parser.listener.probe.*;
import com.nfsdb.io.sink.StringSink;
import com.nfsdb.ql.collections.MultiMap;
import com.nfsdb.storage.ColumnType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.Closeable;

@SuppressFBWarnings({"PL_PARALLEL_LISTS", "LII_LIST_INDEXED_ITERATING"})
public class MetadataExtractorListener implements Listener, Closeable {

    // order of probes in array is critical
    private static final TypeProbe probes[] = new TypeProbe[]{new IntProbe(), new LongProbe(), new DoubleProbe(), new BooleanProbe(), new DateIsoProbe(), new DateFmt1Probe(), new DateFmt2Probe()};
    private static final int probeLen = probes.length;
    private static final ObjList<RecordColumnMetadata> keyMeta = new ObjList<>(1);
    private static final ObjList<RecordColumnMetadata> counterMeta = new ObjList<>(1);
    public final int frequencyMapAreaSize;
    private final StringSink tempSink = new StringSink();
    private final ImportSchema importSchema;
    private int fieldCount;
    private int histogram[];
    private int blanks[];
    private ImportedColumnMetadata metadata[];
    private String headers[];
    private boolean header = false;
    private MultiMap frequencyMaps[];

    public MetadataExtractorListener(ImportSchema importSchema, int sampleSize) {
        this.importSchema = importSchema;
        this.frequencyMapAreaSize = sampleSize * 163;
    }

    @Override
    public void close() {
        if (frequencyMaps != null) {
            for (int i = 0; i < frequencyMaps.length; i++) {
                if (frequencyMaps[i] != null) {
                    frequencyMaps[i].close();
                    frequencyMaps[i] = null;
                }
            }
        }
    }

    @SuppressFBWarnings({"EI_EXPOSE_REP"})
    public ImportedColumnMetadata[] getMetadata() {
        return metadata;
    }

    public boolean isHeader() {
        return header;
    }

    @Override
    public void onError(int line) {

    }

    @Override
    public void onFieldCount(int count) {
        this.histogram = new int[(fieldCount = count) * probeLen];
        this.blanks = new int[count];
        this.metadata = new ImportedColumnMetadata[count];
        this.headers = new String[count];
        this.frequencyMaps = new MultiMap[count];
        for (int i = 0; i < count; i++) {
            frequencyMaps[i] = new MultiMap(counterMeta, keyMeta, null);
        }
    }

    @Override
    public void onFields(int line, CharSequence values[], int hi) {
        // keep first line in case its a header
        if (line == 0) {
            stashPossibleHeader(values, hi);
        }

        for (int i = 0; i < hi; i++) {
            if (values[i].length() == 0) {
                blanks[i]++;
            }
            int offset = i * probeLen;
            for (int k = 0; k < probeLen; k++) {
                if (probes[k].probe(values[i])) {
                    histogram[k + offset]++;
                }
            }
            frequencyMaps[i].getOrCreateValues(frequencyMaps[i].keyWriter().putStr(values[i]));
        }
    }

    @Override
    public void onHeader(CharSequence[] values, int hi) {

    }

    @SuppressFBWarnings({"SF_SWITCH_NO_DEFAULT"})
    @Override
    public void onLineCount(int count) {
        int frequencyExpectation = 1;
        // try calculate types counting all rows
        // if all types come up as strings, reduce count by one and retry
        // if some fields come up as non-string after subtracting row - we have a header
        if (calcTypes(count, true)) {
            frequencyExpectation++;
            if (!calcTypes(count - 1, false)) {
                // copy headers
                for (int i = 0; i < fieldCount; i++) {
                    metadata[i].name = headers[i];
                }
                header = true;
            }
        }

        // make up field names if there is no header
        if (!header) {
            for (int i = 0; i < fieldCount; i++) {
                tempSink.clear();
                tempSink.put('f').put(i);
                metadata[i].name = tempSink.toString();
            }
        }

        // check field value frequencies to determine
        // which fields can be symbols.
        // consider only INT and STRING fields
        for (int i = 0; i < fieldCount; i++) {
            switch (metadata[i].importedType) {
                case STRING:
                case INT:
                    int sz = frequencyMaps[i].size();
                    if (sz > frequencyExpectation
                            && (sz * 10) < ImportManager.SAMPLE_SIZE
                            && (blanks[i] * 10) < ImportManager.SAMPLE_SIZE) {
                        ImportedColumnMetadata m = metadata[i];
                        m.type = ColumnType.SYMBOL;
                        m.importedType = ImportedColumnType.SYMBOL;
                        m.indexed = true;
                        m.size = 4;
                    }
            }
        }

        // override calculated types with user-supplied information
        if (importSchema != null) {
            ObjList<ImportedColumnMetadata> override = importSchema.getMetadata();
            for (int i = 0, k = override.size(); i < k; i++) {
                ImportedColumnMetadata m = override.getQuick(i);
                if (m.columnIndex < fieldCount) {
                    metadata[m.columnIndex].importedType = m.importedType;
                    metadata[m.columnIndex].type = m.type;
                    metadata[m.columnIndex].size = m.size;
                }
            }
        }
    }

    /**
     * Histogram contains counts for every probe that validates field. It is possible for multiple probes to validate same field.
     * It can happen because of two reasons.
     * <p/>
     * probes are compatible, for example INT is compatible wth DOUBLE in a sense that DOUBLE probe will positively
     * validate every INT. If this the case we will use order of probes as priority. First probe wins
     * <p/>
     * it is possible to have mixed types in same column, in which case column has to become string.
     * to establish if we have mixed column we check if probe count + blank values add up to total number of rows.
     */
    private boolean calcTypes(int count, boolean setDefault) {
        boolean allStrings = true;
        for (int i = 0; i < fieldCount; i++) {
            int offset = i * probeLen;
            int blanks = this.blanks[i];

            for (int k = 0; k < probeLen; k++) {
                if (histogram[k + offset] + blanks == count && blanks < count) {
                    metadata[i] = probes[k].getMetadata();
                    if (allStrings) {
                        allStrings = false;
                    }
                    break;
                }
            }

            if (setDefault && metadata[i] == null) {
                ImportedColumnMetadata meta = new ImportedColumnMetadata();
                meta.type = ColumnType.STRING;
                meta.importedType = ImportedColumnType.STRING;
                meta.size = meta.avgSize + 4;
                metadata[i] = meta;
            }
        }

        return allStrings;
    }

    private String normalise(CharSequence seq) {
        boolean capNext = false;
        tempSink.clear();
        for (int i = 0, l = seq.length(); i < l; i++) {
            char c = seq.charAt(i);
            switch (c) {
                case ' ':
                case '_':
                    capNext = true;
                    break;
                default:
                    if (capNext) {
                        tempSink.put(Character.toUpperCase(c));
                        capNext = false;
                    } else {
                        tempSink.put(c);
                    }
            }
        }
        return tempSink.toString();
    }

    private void stashPossibleHeader(CharSequence values[], int hi) {
        for (int i = 0; i < hi; i++) {
            headers[i] = normalise(values[i]);
        }
    }

    static {
        ColumnMetadata keyMeta = new ColumnMetadata();
        keyMeta.setName("Key");
        keyMeta.setType(ColumnType.STRING);
        MetadataExtractorListener.keyMeta.add(keyMeta);

        ColumnMetadata counterMeta = new ColumnMetadata();
        counterMeta.setName("Counter");
        counterMeta.setType(ColumnType.INT);
        MetadataExtractorListener.counterMeta.add(counterMeta);
    }
}
