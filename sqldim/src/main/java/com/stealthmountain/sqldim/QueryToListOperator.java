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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.stealthmountain.sqldim.SqlDim.Query;

import java.util.List;

import io.reactivex.rxjava3.core.ObservableOperator;
import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.exceptions.Exceptions;
import io.reactivex.rxjava3.functions.Function;
import io.reactivex.rxjava3.observers.DisposableObserver;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;

final class QueryToListOperator<L extends List<T>, T> implements ObservableOperator<L, Query> {

  @NonNull private final Function<Cursor, T> mapper;
  @NonNull private final NewList<L, T> newList;

  QueryToListOperator(@NonNull Function<Cursor, T> mapper, @NonNull NewList<L, T> newList) {
    this.mapper = mapper;
    this.newList = newList;
  }

  @NonNull @Override
  public Observer<? super Query> apply(@NonNull Observer<? super L> observer) {
    return new MappingObserver<>(observer, mapper, newList);
  }

  static final class MappingObserver<L extends List<T>, T> extends DisposableObserver<Query> {
    @NonNull private final Observer<? super L> downstream;
    @NonNull private final Function<Cursor, T> mapper;
    @NonNull private final NewList<L, T> newList;

    MappingObserver(@NonNull Observer<? super L> downstream,
                    @NonNull Function<Cursor, T> mapper, @NonNull NewList<L, T> newList) {
      this.downstream = downstream;
      this.mapper = mapper;
      this.newList = newList;
    }

    @Override protected void onStart() {
      downstream.onSubscribe(this);
    }

    @Override public void onNext(@NonNull Query query) {
      try {
        @Nullable T item;
        @Nullable final Cursor cursor = query.run();
        if (cursor == null || isDisposed()) {
          return;
        }
        @NonNull final L items = newList.newList(cursor.getCount());
        try {
          while (cursor.moveToNext()) {
            item = mapper.apply(cursor);
            // even though the type system should make this impossible,
            // Java doesn't always check nullability annotations,
            // so leave this in just in case our clients don't follow the rules.
            if (item == null) {
              downstream.onError(new NullPointerException("QueryToList mapper returned null"));
              return;
            }
            items.add(item);
          }
        } finally {
          cursor.close();
        }
        if (!isDisposed()) {
          downstream.onNext(items);
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
