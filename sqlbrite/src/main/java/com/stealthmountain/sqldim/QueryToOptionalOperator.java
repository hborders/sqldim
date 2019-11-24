/*
 * Copyright (C) 2017 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.stealthmountain.sqldim;

import android.database.Cursor;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import io.reactivex.ObservableOperator;
import io.reactivex.Observer;
import io.reactivex.exceptions.Exceptions;
import io.reactivex.observers.DisposableObserver;
import io.reactivex.plugins.RxJavaPlugins;
import java.util.Optional;

@RequiresApi(Build.VERSION_CODES.N)
final class QueryToOptionalOperator<T> implements ObservableOperator<Optional<T>, SqlDim.Query> {
  @NonNull private final FunctionRR<Cursor, T> mapper;

  QueryToOptionalOperator(@NonNull FunctionRR<Cursor, T> mapper) {
    this.mapper = mapper;
  }

  @NonNull @Override
  public Observer<? super SqlDim.Query> apply(@NonNull Observer<? super Optional<T>> observer) {
    return new MappingObserver<>(observer, mapper);
  }

  static final class MappingObserver<T> extends DisposableObserver<SqlDim.Query> {
    @NonNull private final Observer<? super Optional<T>> downstream;
    @NonNull private final FunctionRR<Cursor, T> mapper;

    MappingObserver(@NonNull Observer<? super Optional<T>> downstream, @NonNull FunctionRR<Cursor, T> mapper) {
      this.downstream = downstream;
      this.mapper = mapper;
    }

    @Override protected void onStart() {
      downstream.onSubscribe(this);
    }

    @Override public void onNext(@NonNull SqlDim.Query query) {
      try {
        @Nullable final T item;
        @Nullable final Cursor cursor = query.run();
        if (cursor != null) {
          try {
            if (cursor.moveToNext()) {
              item = mapper.applyRR(cursor);
              // even though the type system should make this impossible,
              // Java doesn't always check nullability annotations,
              // so leave this in just in case our clients don't follow the rules.
              if (item == null) {
                downstream.onError(new NullPointerException("QueryToOne mapper returned null"));
                return;
              }
              if (cursor.moveToNext()) {
                throw new IllegalStateException("Cursor returned more than 1 row");
              }
            } else {
              item = null;
            }
          } finally {
            cursor.close();
          }
        } else {
          item = null;
        }
        if (!isDisposed()) {
          downstream.onNext(Optional.ofNullable(item));
        }
      } catch (Throwable e) {
        Exceptions.throwIfFatal(e);
        onError(e);
      }
    }

    @Override public void onComplete() {
      if (!isDisposed()) {
        downstream.onComplete();
      }
    }

    @Override public void onError(@NonNull Throwable e) {
      if (isDisposed()) {
        RxJavaPlugins.onError(e);
      } else {
        downstream.onError(e);
      }
    }
  }
}
