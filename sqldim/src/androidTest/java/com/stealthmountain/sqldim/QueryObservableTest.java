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
import android.database.MatrixCursor;

import androidx.annotation.NonNull;

import com.stealthmountain.sqldim.SqlDim.Query;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.functions.Function;

import org.junit.Test;

import java.util.ArrayList;

public final class QueryObservableTest {
  @Test public void mapToListThrowsFromQueryRun() {
    @NonNull final IllegalStateException error = new IllegalStateException("test exception");
    @NonNull final Query query = new Query() {
      @Override public Cursor run() {
        throw error;
      }
    };
    new QueryObservable(Observable.just(query)) //
        .mapToList(new Function<Cursor, Object>() {
          @NonNull @Override public Object apply(@NonNull Cursor cursor) {
            throw new AssertionError("Must not be called");
          }
        }) //
        .test() //
        .assertNoValues() //
        .assertError(error);
  }

  @Test public void mapToSpecificListThrowsFromQueryRun() {
    @NonNull final IllegalStateException error = new IllegalStateException("test exception");
    @NonNull final Query query = new Query() {
      @Override public Cursor run() {
        throw error;
      }
    };
    new QueryObservable(Observable.just(query)) //
        .mapToSpecificList(new Function<Cursor, Object>() {
          @NonNull @Override public Object apply(@NonNull Cursor cursor) {
            throw new AssertionError("Must not be called");
          }
        }, ArrayList::new) //
        .test() //
        .assertNoValues() //
        .assertError(error);
  }

  @Test public void mapToListThrowsFromMapFunction() {
    @NonNull final Query query = new Query() {
      @Override public Cursor run() {
        @NonNull final MatrixCursor cursor = new MatrixCursor(new String[] { "col1" });
        cursor.addRow(new Object[] { "value1" });
        return cursor;
      }
    };

    @NonNull final IllegalStateException error = new IllegalStateException("test exception");
    new QueryObservable(Observable.just(query)) //
        .mapToList(new Function<Cursor, Object>() {
          @NonNull @Override public Object apply(@NonNull Cursor cursor) {
            throw error;
          }
        }) //
        .test() //
        .assertNoValues() //
        .assertError(error);
  }

  @Test public void mapToSpecificListThrowsFromMapFunction() {
    @NonNull final Query query = new Query() {
      @Override public Cursor run() {
        @NonNull final MatrixCursor cursor = new MatrixCursor(new String[] { "col1" });
        cursor.addRow(new Object[] { "value1" });
        return cursor;
      }
    };

    @NonNull final IllegalStateException error = new IllegalStateException("test exception");
    new QueryObservable(Observable.just(query)) //
        .mapToSpecificList(new Function<Cursor, Object>() {
          @NonNull @Override public Object apply(@NonNull Cursor cursor) {
            throw error;
          }
        }, ArrayList::new) //
        .test() //
        .assertNoValues() //
        .assertError(error);
  }
}
