/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.collector;

import javax.annotation.Nullable;

import com.google.common.collect.Ordering;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Longs;
import org.immutables.value.Json;
import org.immutables.value.Value;

import static com.google.common.base.Preconditions.checkNotNull;

@Value.Immutable
@Json.Marshaled
public abstract class ErrorSummary {

    public static final Ordering<ErrorSummary> orderingByErrorCountDesc =
            new Ordering<ErrorSummary>() {
                @Override
                public int compare(@Nullable ErrorSummary left, @Nullable ErrorSummary right) {
                    checkNotNull(left);
                    checkNotNull(right);
                    return Longs.compare(right.errorCount(), left.errorCount());
                }
            };

    public static final Ordering<ErrorSummary> orderingByErrorRateDesc =
            new Ordering<ErrorSummary>() {
                @Override
                public int compare(@Nullable ErrorSummary left, @Nullable ErrorSummary right) {
                    checkNotNull(left);
                    checkNotNull(right);
                    return Doubles.compare(right.errorCount() / (double) right.transactionCount(),
                            left.errorCount() / (double) left.transactionCount());
                }
            };

    public abstract @Nullable String transactionName();
    public abstract long errorCount();
    public abstract long transactionCount();
}