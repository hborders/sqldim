/*
 * Copyright (C) 2015 Square, Inc.
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

import androidx.annotation.NonNull;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration;
import androidx.sqlite.db.SupportSQLiteOpenHelper.Factory;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import android.database.Cursor;
import android.os.Build;
import androidx.annotation.Nullable;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import com.stealthmountain.sqldim.SqlDim.Query;
import com.stealthmountain.sqldim.TestDb.Employee;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.functions.Function;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.schedulers.Schedulers;

import static com.google.common.truth.Truth.assertThat;
import static com.stealthmountain.sqldim.AssertErrorMessagePredicate.assertErrorMessage;
import static com.stealthmountain.sqldim.TestDb.Employee.MAPPER;
import static com.stealthmountain.sqldim.TestDb.SELECT_EMPLOYEES;
import static com.stealthmountain.sqldim.TestDb.TABLE_EMPLOYEE;
import static org.junit.Assert.fail;

public final class QueryTest {
  @Nullable private DimDatabase<Object> db;

  @Before public void setUp() {
    @NonNull final Configuration configuration = Configuration.builder(
            InstrumentationRegistry.getInstrumentation().getTargetContext()
    )
        .callback(new TestDb())
        .build();

    @NonNull final Factory factory = new FrameworkSQLiteOpenHelperFactory();
    @NonNull final SupportSQLiteOpenHelper helper = factory.create(configuration);

    @NonNull final SqlDim<Object> sqlDim = new SqlDim.Builder<>().build();
    db = sqlDim.wrapDatabaseHelper(helper, Schedulers.trampoline());
  }

  @Test public void mapToOne() {
    @NonNull final DimDatabase<Object> db = Objects.requireNonNull(this.db);

    @NonNull final Employee employees = db.createQuery(TABLE_EMPLOYEE, SELECT_EMPLOYEES + " LIMIT 1")
        .lift(Query.mapToOne(MAPPER))
        .blockingFirst();
    assertThat(employees).isEqualTo(new Employee("alice", "Alice Allison"));
  }

  @Test public void mapToOneThrowsWhenMapperReturnsNull() {
    @NonNull final DimDatabase<Object> db = Objects.requireNonNull(this.db);

    db.createQuery(TABLE_EMPLOYEE, SELECT_EMPLOYEES + " LIMIT 1")
        .lift(Query.mapToOne(new Function<Cursor, Employee>() {
          @NonNull @Override public Employee apply(@NonNull Cursor cursor) throws Exception {
            return null;
          }
        }))
        .test()
        .assertError(NullPointerException.class)
        .assertError(assertErrorMessage("QueryToOne mapper returned null"));
  }

  @Test public void mapToOneThrowsOnMultipleRows() {
    @NonNull final DimDatabase<Object> db = Objects.requireNonNull(this.db);

    @NonNull final Observable<Employee> employees =
        db.createQuery(TABLE_EMPLOYEE, SELECT_EMPLOYEES + " LIMIT 2") //
            .lift(Query.mapToOne(MAPPER));
    try {
      //noinspection ResultOfMethodCallIgnored
      employees.blockingFirst();
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().isEqualTo("Cursor returned more than 1 row");
    }
  }

  @Test public void mapToOneIgnoresNullCursor() {
    @NonNull final Query nully = new Query() {
      @Nullable @Override public Cursor run() {
        return null;
      }
    };

    @NonNull final TestObserver<Employee> observer = new TestObserver<>();
    Observable.just(nully)
        .lift(Query.mapToOne(MAPPER))
        .subscribe(observer);

    observer.assertNoValues();
    observer.assertComplete();
  }

  @Test public void mapToOneOrDefault() {
    @NonNull final DimDatabase<Object> db = Objects.requireNonNull(this.db);

    @NonNull final Employee employees = db.createQuery(TABLE_EMPLOYEE, SELECT_EMPLOYEES + " LIMIT 1")
        .lift(Query.mapToOneOrDefault(
            MAPPER, new Employee("fred", "Fred Frederson")))
        .blockingFirst();
    assertThat(employees).isEqualTo(new Employee("alice", "Alice Allison"));
  }

  @Test public void mapToOneOrDefaultDisallowsNullDefault() {
    try {
      //noinspection ConstantConditions
      Query.mapToOneOrDefault(MAPPER, null);
      fail();
    } catch (NullPointerException e) {
      assertThat(e).hasMessageThat().isEqualTo("defaultValue == null");
    }
  }

  @Test public void mapToOneOrDefaultThrowsWhenMapperReturnsNull() {
    @NonNull final DimDatabase<Object> db = Objects.requireNonNull(this.db);

    db.createQuery(TABLE_EMPLOYEE, SELECT_EMPLOYEES + " LIMIT 1")
        .lift(Query.mapToOneOrDefault(new Function<Cursor, Employee>() {
          @NonNull @Override public Employee apply(@NonNull Cursor cursor) throws Exception {
            return null;
          }
        }, new Employee("fred", "Fred Frederson")))
        .test()
        .assertError(NullPointerException.class)
        .assertError(assertErrorMessage("QueryToOne mapper returned null"));
  }

  @Test public void mapToOneOrDefaultThrowsOnMultipleRows() {
    @NonNull final DimDatabase<Object> db = Objects.requireNonNull(this.db);

    @NonNull final Observable<Employee> employees =
        db.createQuery(TABLE_EMPLOYEE, SELECT_EMPLOYEES + " LIMIT 2") //
            .lift(Query.mapToOneOrDefault(
                MAPPER, new Employee("fred", "Fred Frederson")));
    try {
      //noinspection ResultOfMethodCallIgnored
      employees.blockingFirst();
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().isEqualTo("Cursor returned more than 1 row");
    }
  }

  @Test public void mapToOneOrDefaultReturnsDefaultWhenNullCursor() {
    @NonNull final Employee defaultEmployee = new Employee("bob", "Bob Bobberson");
    @NonNull final Query nully = new Query() {
      @Nullable @Override public Cursor run() {
        return null;
      }
    };

    @NonNull final TestObserver<Employee> observer = new TestObserver<>();
    Observable.just(nully)
        .lift(Query.mapToOneOrDefault(MAPPER, defaultEmployee))
        .subscribe(observer);

    observer.assertValues(defaultEmployee);
    observer.assertComplete();
  }

  @Test public void mapToList() {
    @NonNull final DimDatabase<Object> db = Objects.requireNonNull(this.db);

    @NonNull final List<Employee> employees = db.createQuery(TABLE_EMPLOYEE, SELECT_EMPLOYEES)
        .lift(Query.mapToList(MAPPER))
        .blockingFirst();
    assertThat(employees).containsExactly( //
        new Employee("alice", "Alice Allison"), //
        new Employee("bob", "Bob Bobberson"), //
        new Employee("eve", "Eve Evenson"));
  }

  @Test public void mapToListEmptyWhenNoRows() {
    @NonNull final DimDatabase<Object> db = Objects.requireNonNull(this.db);

    @NonNull final List<Employee> employees = db.createQuery(TABLE_EMPLOYEE, SELECT_EMPLOYEES + " WHERE 1=2")
        .lift(Query.mapToList(MAPPER))
        .blockingFirst();
    assertThat(employees).isEmpty();
  }

  @Test public void mapToListThrowsWhenMapperReturnsNull() {
    @NonNull final DimDatabase<Object> db = Objects.requireNonNull(this.db);

    @NonNull final Function<Cursor, Employee> mapToNull = new Function<Cursor, Employee>() {
      @NonNull @Override public Employee apply(@NonNull Cursor cursor) throws Exception {
        return null;
      }
    };

    try {
      //noinspection ResultOfMethodCallIgnored
      db.createQuery(TABLE_EMPLOYEE, SELECT_EMPLOYEES) //
              .lift(Query.mapToList(mapToNull)) //
              .blockingFirst();
      fail();
    } catch (NullPointerException e) {
      assertThat(e).hasMessageThat().isEqualTo("QueryToList mapper returned null");
    }
  }

  @Test public void mapToListIgnoresNullCursor() {
    @NonNull final Query nully = new Query() {
      @Nullable @Override public Cursor run() {
        return null;
      }
    };

    @NonNull final TestObserver<List<Employee>> subscriber = new TestObserver<>();
    Observable.just(nully)
        .lift(Query.mapToList(MAPPER))
        .subscribe(subscriber);

    subscriber.assertNoValues();
    subscriber.assertComplete();
  }

  @SdkSuppress(minSdkVersion = Build.VERSION_CODES.N)
  @Test public void mapToOptional() {
    @NonNull final DimDatabase<Object> db = Objects.requireNonNull(this.db);

    db.createQuery(TABLE_EMPLOYEE, SELECT_EMPLOYEES + " LIMIT 1")
        .lift(Query.mapToOptional(MAPPER))
        .test()
        .assertValue(Optional.of(new Employee("alice", "Alice Allison")));
  }

  @SdkSuppress(minSdkVersion = Build.VERSION_CODES.N)
  @Test public void mapToOptionalThrowsWhenMapperReturnsNull() {
    @NonNull final DimDatabase<Object> db = Objects.requireNonNull(this.db);

    db.createQuery(TABLE_EMPLOYEE, SELECT_EMPLOYEES + " LIMIT 1")
        .lift(Query.mapToOptional(new Function<Cursor, Employee>() {
          @NonNull @Override public Employee apply(@NonNull Cursor cursor) throws Exception {
            return null;
          }
        }))
        .test()
        .assertError(NullPointerException.class)
        .assertError(assertErrorMessage("QueryToOne mapper returned null"));
  }

  @SdkSuppress(minSdkVersion = Build.VERSION_CODES.N)
  @Test public void mapToOptionalThrowsOnMultipleRows() {
    @NonNull final DimDatabase<Object> db = Objects.requireNonNull(this.db);

    db.createQuery(TABLE_EMPLOYEE, SELECT_EMPLOYEES + " LIMIT 2") //
        .lift(Query.mapToOptional(MAPPER))
        .test()
        .assertError(IllegalStateException.class)
        .assertError(assertErrorMessage("Cursor returned more than 1 row"));
  }

  @SdkSuppress(minSdkVersion = Build.VERSION_CODES.N)
  @Test public void mapToOptionalIgnoresNullCursor() {
    @NonNull final Query nully = new Query() {
      @Nullable @Override public Cursor run() {
        return null;
      }
    };

    Observable.just(nully)
        .lift(Query.mapToOptional(MAPPER))
        .test()
        .assertValue(Optional.<Employee>empty());
  }
}
