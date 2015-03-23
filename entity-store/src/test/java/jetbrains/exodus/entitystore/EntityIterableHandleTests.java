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
package jetbrains.exodus.entitystore;

import jetbrains.exodus.core.dataStructures.hash.HashSet;
import jetbrains.exodus.entitystore.iterate.ConstantEntityIterableHandle;
import jetbrains.exodus.entitystore.iterate.EntityIterableHandleBase;
import org.jetbrains.annotations.NotNull;
import org.junit.Assert;

import java.security.SecureRandom;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

public class EntityIterableHandleTests extends EntityStoreTestBase {

    public void testTrivial() {
        final EntityIterableHandleBase h = new ConstantEntityIterableHandle(getEntityStore(), EntityIterableType.EMPTY) {
            @Override
            protected void hashCode(@NotNull EntityIterableHandleHash hash) {
                for (int i = 0; i < 31; ++i) {
                    hash.apply("0");
                }
            }
        };
        Assert.assertEquals("00000000000000000000000000000000", h.toString());
    }

    public void testDistribution() {
        final SecureRandom rnd = new SecureRandom();
        final Set<EntityIterableHandleBase.EntityIterableHandleHash> set = new HashSet<EntityIterableHandleBase.EntityIterableHandleHash>();
        for (int i = 0; i < 1000000; ++i) {
            final EntityIterableHandleBase.EntityIterableHandleHash h = new EntityIterableHandleBase.EntityIterableHandleHash();
            h.apply("00000000000000000000000000000000");
            final IntStream ints = rnd.ints(rnd.nextInt(40) + 10);
            ints.forEach(new IntConsumer() {
                @Override
                public void accept(int value) {
                    h.applyDelimiter();
                    h.apply(value & 0xff);
                }
            });
            ints.close();
            // in case of poor distribution, birthday paradox will give assertion quite soon
            if (!set.add(h)) {
                Assert.assertTrue(false);
            }
            if ((i % 1000000) == 0) {
                System.out.print(".");
            }
        }
    }
}