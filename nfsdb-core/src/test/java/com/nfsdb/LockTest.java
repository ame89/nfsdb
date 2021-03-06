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

import com.nfsdb.exceptions.JournalException;
import com.nfsdb.factory.JournalFactory;
import com.nfsdb.model.Quote;
import com.nfsdb.model.configuration.ModelConfiguration;
import com.nfsdb.test.tools.AbstractTest;
import com.nfsdb.utils.Dates;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

public class LockTest extends AbstractTest {

    @Test
    public void testLockAcrossClassLoaders() throws JournalException, ClassNotFoundException, IllegalAccessException, InstantiationException, NoSuchMethodException, InvocationTargetException {
        URLClassLoader classLoader = new URLClassLoader(((URLClassLoader) this.getClass().getClassLoader()).getURLs(), null);

        JournalWriter<Quote> rw = factory.writer(Quote.class);
        rw.close();
        rw.delete();

        rw = factory.writer(Quote.class);

        List<Quote> data = new ArrayList<>();
        data.add(new Quote().setSym("S1").setTimestamp(Dates.toMillis(2013, 3, 10, 15, 0)));
        data.add(new Quote().setSym("S2").setTimestamp(Dates.toMillis(2013, 3, 10, 16, 0)));
        rw.mergeAppend(data);
        rw.commit();

        new TestAccessor(factory.getConfiguration().getJournalBase());
        classLoader.loadClass("com.nfsdb.LockTest$TestAccessor").getConstructor(File.class)
                .newInstance(factory.getConfiguration().getJournalBase());

        rw.close();
        rw.delete();
    }

    public static class TestAccessor {
        public TestAccessor(File journalBase) throws JournalException {
            JournalFactory factory = new JournalFactory(ModelConfiguration.MAIN.build(journalBase));
            Journal<Quote> reader = factory.reader(Quote.class);
            Assert.assertEquals(2, reader.size());
            reader.close();
        }
    }
}
