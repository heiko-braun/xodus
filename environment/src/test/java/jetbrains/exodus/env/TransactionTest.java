/**
 * Copyright 2010 - 2015 JetBrains s.r.o.
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
package jetbrains.exodus.env;

import jetbrains.exodus.ArrayByteIterable;
import jetbrains.exodus.ByteIterable;
import jetbrains.exodus.ExodusException;
import jetbrains.exodus.TestUtil;
import jetbrains.exodus.bindings.StringBinding;
import jetbrains.exodus.log.LogConfig;
import jetbrains.exodus.tree.btree.BTreeBase;
import org.jetbrains.annotations.NotNull;
import org.junit.Assert;
import org.junit.Test;

public class TransactionTest extends EnvironmentTestsBase {

    @Override
    protected void createEnvironment() {
        LogConfig config = new LogConfig();
        config.setReader(reader);
        config.setWriter(writer);
        env = newContextualEnvironmentInstance(config, new EnvironmentConfig());
    }

    @Test
    public void testCurrentTransaction() {
        final ContextualEnvironment env = (ContextualEnvironment) getEnvironment();
        Transaction txn = env.beginTransaction();
        Assert.assertEquals(txn, env.getCurrentTransaction());
        txn.abort();
        Assert.assertEquals(null, env.getCurrentTransaction());
        txn = env.beginTransaction();
        Assert.assertEquals(txn, env.getCurrentTransaction());
        Transaction txn1 = env.beginTransaction();
        Assert.assertEquals(txn1, env.getCurrentTransaction());
        txn1.commit();
        txn.commit();
        Assert.assertEquals(null, env.getCurrentTransaction());
    }

    @Test
    public void testCommitTwice() {
        TestUtil.runWithExpectedException(new Runnable() {
            @Override
            public void run() {
                Transaction txn = env.beginTransaction();
                txn.commit();
                txn.commit();
            }
        }, ExodusException.class);
    }

    @Test
    public void testAbortTwice() {
        TestUtil.runWithExpectedException(new Runnable() {
            @Override
            public void run() {
                Transaction txn = env.beginTransaction();
                txn.abort();
                txn.abort();
            }
        }, ExodusException.class);
    }

    @Test
    public void testNestedTransactions() {
        final Transaction txn = env.beginTransaction();
        final Transaction txn1 = env.beginTransaction();
        TestUtil.runWithExpectedException(new Runnable() {
            @Override
            public void run() {
                txn.commit();
            }
        }, ExodusException.class);
        txn1.commit();
        txn.commit();
    }

    @Test
    public void testAtomicity() {
        final Environment env = getEnvironment();
        final Transaction txn = env.beginTransaction();
        final Store store = env.openStore("new_store", StoreConfig.WITHOUT_DUPLICATES, txn);
        final ArrayByteIterable entry1 = StringBinding.stringToEntry("1");
        store.put(txn, entry1, entry1);
        final ArrayByteIterable entry2 = StringBinding.stringToEntry("2");
        store.put(txn, entry2, entry2);
        txn.commit();
        // all changes should be placed in single snapshot
        assertLoggableTypes(getLog(), 0, BTreeBase.BOTTOM_ROOT, DatabaseRoot.DATABASE_ROOT_TYPE,
                BTreeBase.LEAF, BTreeBase.LEAF, BTreeBase.BOTTOM_ROOT, BTreeBase.LEAF, BTreeBase.LEAF,
                BTreeBase.BOTTOM_ROOT, DatabaseRoot.DATABASE_ROOT_TYPE);
    }

    @Test
    public void testAbort() {
        final Environment env = getEnvironment();
        final Transaction txn = env.beginTransaction();
        final Store store = env.openStore("new_store", StoreConfig.WITHOUT_DUPLICATES, txn);
        final ArrayByteIterable entry1 = StringBinding.stringToEntry("1");
        store.put(txn, entry1, entry1);
        final ArrayByteIterable entry2 = StringBinding.stringToEntry("2");
        store.put(txn, entry2, entry2);
        txn.abort();
        // no changes should be written since transaction was not committed
        assertLoggableTypes(getLog(), 0, BTreeBase.BOTTOM_ROOT, DatabaseRoot.DATABASE_ROOT_TYPE);
    }

    @Test
    public void testReadCommitted() {
        final Environment env = getEnvironment();
        final ByteIterable key = StringBinding.stringToEntry("key");
        Transaction txn = env.beginTransaction();
        final Store store = env.openStore("new_store", StoreConfig.WITHOUT_DUPLICATES, txn);
        store.put(txn, key, StringBinding.stringToEntry("value"));
        Transaction t = env.beginTransaction();
        Assert.assertNull(store.get(t, key));
        t.commit();
        txn.commit();
        txn = env.beginTransaction();
        store.put(txn, key, StringBinding.stringToEntry("value1"));
        t = env.beginTransaction();
        assertNotNullStringValue(store, key, "value");
        t.commit();
        txn.commit();
        assertNotNullStringValue(store, key, "value1");
    }

    @Test
    public void testReadUncommitted() {
        final Environment env = getEnvironment();
        final ByteIterable key = StringBinding.stringToEntry("key");
        Transaction txn = env.beginTransaction();
        final Store store = env.openStore("new_store", StoreConfig.WITHOUT_DUPLICATES, txn);
        txn.commit();
        txn = env.beginTransaction();
        store.put(txn, key, StringBinding.stringToEntry("value"));
        assertNotNullStringValue(store, key, "value");
        txn.commit();
    }

    @Test
    public void testRepeatableRead() {
        final Environment env = getEnvironment();
        final ByteIterable key = StringBinding.stringToEntry("key");
        Transaction txn = env.beginTransaction();
        final Store store = env.openStore("new_store", StoreConfig.WITHOUT_DUPLICATES, txn);
        store.put(txn, key, StringBinding.stringToEntry("value"));
        assertNotNullStringValue(store, key, "value");
        txn.commit();
        assertNotNullStringValue(store, key, "value");
        txn = env.beginTransaction();
        assertNotNullStringValue(store, key, "value");
        executeParallelTransaction(new TransactionalExecutable() {
            @Override
            public void execute(@NotNull final Transaction txn) {
                store.put(txn, key, StringBinding.stringToEntry("value1"));
            }
        });
        assertNotNullStringValue(store, key, "value");
        txn.abort();
        assertNotNullStringValue(store, key, "value1");
    }

    @Test
    public void testTransactionSafeJob() throws InterruptedException {
        final boolean[] bTrue = new boolean[]{false};
        final boolean[] bFalse = new boolean[]{true};
        final Transaction txn = env.beginTransaction();
        final Transaction txn1 = env.beginTransaction();
        env.executeTransactionSafeTask(new Runnable() {
            @Override
            public void run() {
                bTrue[0] = true;
            }
        });
        env.executeTransactionSafeTask(new Runnable() {
            @Override
            public void run() {
                bFalse[0] = false;
            }
        });
        Thread.sleep(500);
        Assert.assertFalse(bTrue[0]);
        Assert.assertTrue(bFalse[0]);
        txn1.abort();
        Thread.sleep(500);
        Assert.assertFalse(bTrue[0]);
        Assert.assertTrue(bFalse[0]);
        txn.abort();
        Thread.sleep(500);
        Assert.assertTrue(bTrue[0]);
        Assert.assertFalse(bFalse[0]);
    }

    @Test
    public void testFlush() {
        final boolean[] ok = {true};
        final Environment env = getEnvironment();
        final ByteIterable key1 = StringBinding.stringToEntry("key1");
        final ByteIterable key2 = StringBinding.stringToEntry("key2");
        Transaction txn = env.beginTransaction();
        final Store store = env.openStore("new_store", StoreConfig.WITHOUT_DUPLICATES, txn);
        txn.commit();
        txn = env.beginTransaction();
        store.put(txn, key1, StringBinding.stringToEntry("value1"));
        executeParallelTransaction(new TransactionalExecutable() {
            @Override
            public void execute(@NotNull final Transaction txn) {
                try {
                    assertEmptyValue(txn, store, key1);
                    assertEmptyValue(txn, store, key2);
                } catch (Throwable t) {
                    ok[0] = false;
                }
            }
        });
        txn.flush();
        store.put(txn, key2, StringBinding.stringToEntry("value2"));
        executeParallelTransaction(new TransactionalExecutable() {
            @Override
            public void execute(@NotNull final Transaction txn) {
                try {
                    assertNotNullStringValue(txn, store, key1, "value1");
                    assertEmptyValue(txn, store, key2);
                } catch (Throwable t) {
                    ok[0] = false;
                }
            }
        });
        txn.flush();
        txn.abort();
        Assert.assertTrue(ok[0]);
        assertNotNullStringValue(store, key1, "value1");
        assertNotNullStringValue(store, key2, "value2");
    }

    @Test
    public void testRevert() {
        final Environment env = getEnvironment();
        final ByteIterable key1 = StringBinding.stringToEntry("key1");
        final ByteIterable key2 = StringBinding.stringToEntry("key2");
        Transaction txn = env.beginTransaction();
        final Store store = env.openStore("new_store", StoreConfig.WITHOUT_DUPLICATES, txn);
        txn.commit();
        txn = env.beginTransaction();
        store.put(txn, key1, StringBinding.stringToEntry("value1"));
        executeParallelTransaction(new TransactionalExecutable() {
            @Override
            public void execute(@NotNull final Transaction txn) {
                store.put(txn, key2, StringBinding.stringToEntry("value2"));
            }
        });
        assertNotNullStringValue(store, key1, "value1");
        assertEmptyValue(store, key2);
        txn.revert();
        assertEmptyValue(store, key1);
        assertNotNullStringValue(store, key2, "value2");
        txn.abort();
    }

    @Test(expected = ReadonlyTransactionException.class)
    public void testExecuteInReadonlyTransaction() {
        final EnvironmentImpl env = getEnvironment();
        env.executeInReadonlyTransaction(new TransactionalExecutable() {
            @Override
            public void execute(@NotNull Transaction txn) {
                env.openStore("WTF", StoreConfig.WITHOUT_DUPLICATES, txn);
            }
        });
    }

    @Test(expected = ReadonlyTransactionException.class)
    public void test_XD_447() {
        final EnvironmentImpl env = getEnvironment();
        final EnvironmentConfig ec = env.getEnvironmentConfig();
        ec.setEnvIsReadonly(true);
        ec.setEnvReadonlyEmptyStores(true);
        env.executeInTransaction(new TransactionalExecutable() {
            @Override
            public void execute(@NotNull Transaction txn) {
                final StoreImpl store = env.openStore("WTF", StoreConfig.WITHOUT_DUPLICATES, txn);
                final ArrayByteIterable wtfEntry = StringBinding.stringToEntry("WTF");
                store.put(txn, wtfEntry, wtfEntry);
            }
        });
    }

    @Test(expected = ReadonlyTransactionException.class)
    public void test_XD_447_() {
        final EnvironmentImpl env = getEnvironment();
        final EnvironmentConfig ec = env.getEnvironmentConfig();
        ec.setEnvIsReadonly(true);
        ec.setEnvReadonlyEmptyStores(true);
        env.executeInTransaction(new TransactionalExecutable() {
            @Override
            public void execute(@NotNull Transaction txn) {
                final StoreImpl store = env.openStore("WTF", StoreConfig.WITHOUT_DUPLICATES, txn);
                store.delete(txn, StringBinding.stringToEntry("WTF"));
            }
        });
    }

    @Test
    public void test_XD_401() throws Exception {
        final Environment env = getEnvironment();
        final Store store = env.computeInTransaction(new TransactionalComputable<Store>() {
            @Override
            public Store compute(@NotNull final Transaction txn) {
                return env.openStore("store", StoreConfig.WITH_DUPLICATES, txn);
            }
        });
        final Transaction txn = env.beginTransaction();
        final long started = txn.getCreated();
        store.put(txn, StringBinding.stringToEntry("key"), StringBinding.stringToEntry("value"));
        Thread.sleep(200);
        try {
            Assert.assertTrue(txn.flush());
            Assert.assertTrue(txn.getCreated() > started + 150);
            store.put(txn, StringBinding.stringToEntry("key"), StringBinding.stringToEntry("new value"));
            Thread.sleep(200);
            txn.revert();
            Assert.assertTrue(txn.getCreated() > started + 350);
        } finally {
            txn.abort();
        }
    }
}
