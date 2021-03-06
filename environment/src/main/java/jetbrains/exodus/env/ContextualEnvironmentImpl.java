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

import jetbrains.exodus.ExodusException;
import jetbrains.exodus.log.Log;
import jetbrains.exodus.tree.TreeMetaInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ContextualEnvironmentImpl extends EnvironmentImpl implements ContextualEnvironment {

    private final Map<Thread, Deque<TransactionImpl>> threadTxns = new ConcurrentHashMap<>(4, 0.75f, 4);

    ContextualEnvironmentImpl(@NotNull Log log, @NotNull EnvironmentConfig ec) {
        super(log, ec);
    }

    @Override
    @Nullable
    public TransactionImpl getCurrentTransaction() {
        final Thread thread = Thread.currentThread();
        final Deque<TransactionImpl> stack = threadTxns.get(thread);
        return stack == null ? null : stack.peek();
    }

    @NotNull
    @Override
    public Transaction getAndCheckCurrentTransaction() {
        final Transaction txn = getCurrentTransaction();
        if (txn == null) {
            throw new IllegalStateException("No transaction started in current thread.");
        }
        return txn;
    }

    @Override
    public void executeInTransaction(@NotNull TransactionalExecutable executable) {
        final Transaction current = getCurrentTransaction();
        if (current != null) {
            executable.execute(current);
        } else {
            super.executeInTransaction(executable);
        }
    }

    @Override
    public <T> T computeInTransaction(@NotNull TransactionalComputable<T> computable) {
        final Transaction current = getCurrentTransaction();
        return current != null ? computable.compute(current) : super.computeInTransaction(computable);
    }

    @NotNull
    @Override
    public List<String> getAllStoreNames() {
        return getAllStoreNames(getAndCheckCurrentTransaction());
    }

    @NotNull
    @Override
    public ContextualStoreImpl openStore(@NotNull final String name, @NotNull final StoreConfig config) {
        final TransactionImpl txn = beginTransaction();
        try {
            return openStore(name, config, txn);
        } finally {
            txn.commit();
        }
    }

    @Override
    @Nullable
    public ContextualStoreImpl openStore(@NotNull final String name, @NotNull final StoreConfig config, final boolean creationRequired) {
        final TransactionImpl txn = beginTransaction();
        try {
            return openStore(name, config, txn, creationRequired);
        } finally {
            txn.commit();
        }
    }

    @NotNull
    @Override
    public ContextualStoreImpl openStore(@NotNull String name, @NotNull StoreConfig config, @NotNull Transaction transaction) {
        return (ContextualStoreImpl) super.openStore(name, config, transaction);
    }

    @Nullable
    @Override
    public ContextualStoreImpl openStore(@NotNull String name, @NotNull StoreConfig config, @NotNull Transaction transaction, boolean creationRequired) {
        return (ContextualStoreImpl) super.openStore(name, config, transaction, creationRequired);
    }

    @Override
    protected StoreImpl createStore(@NotNull final String name, @NotNull final TreeMetaInfo metaInfo) {
        return new ContextualStoreImpl(this, name, metaInfo);
    }

    @Override
    protected Thread getCreatingThread() {
        return Thread.currentThread();
    }

    @NotNull
    @Override
    protected TransactionImpl beginTransaction(boolean cloneMeta, Runnable beginHook) {
        final TransactionImpl result = super.beginTransaction(cloneMeta, beginHook);
        setCurrentTransaction(result);
        return result;
    }

    @NotNull
    @Override
    public TransactionImpl beginReadonlyTransaction(final Runnable beginHook) {
        final TransactionImpl txn = super.beginReadonlyTransaction(beginHook);
        setCurrentTransaction(txn);
        return txn;
    }

    private void setCurrentTransaction(@NotNull final TransactionImpl result) {
        final Thread thread = result.getCreatingThread();
        if (thread == null) {
            throw new NullPointerException("Creating thread should be set for transaction");
        }
        Deque<TransactionImpl> stack = threadTxns.get(thread);
        if (stack == null) {
            stack = new ArrayDeque<>(4);
            threadTxns.put(thread, stack);
        }
        stack.push(result);
    }

    @Override
    protected void finishTransaction(@NotNull TransactionImpl txn) {
        final Thread thread = txn.getCreatingThread();
        if (thread == null) {
            throw new NullPointerException("Creating thread should be set for transaction");
        }
        // logging.info("finished txn " + System.identityHashCode(txn) + " in thread " + thread.getName(), new Throwable());
        if (!Thread.currentThread().equals(thread)) {
            throw new ExodusException("Can't finish transaction in a thread different from the one which it was created in");
        }
        final Deque<TransactionImpl> stack = threadTxns.get(thread);
        if (stack == null) {
            throw new ExodusException("Transaction was already finished");
        }
        if (txn != stack.peek()) {
            throw new ExodusException("Can't finish transaction: nested transaction is not finished");
        }
        stack.pop();
        if (stack.isEmpty()) {
            threadTxns.remove(thread);
        }
        super.finishTransaction(txn);
    }

    protected StoreImpl createTemporaryEmptyStore(String name) {
        return new ContextualTemporaryEmptyStore(this, name);
    }
}
