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
package com.squareup.sqlbrite3;

import androidx.sqlite.db.SimpleSQLiteQuery;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import androidx.sqlite.db.SupportSQLiteOpenHelper.Callback;
import androidx.sqlite.db.SupportSQLiteQuery;
import androidx.sqlite.db.SupportSQLiteStatement;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteTransactionListener;
import androidx.annotation.CheckResult;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import com.squareup.sqlbrite3.SqlBrite.Logger;
import com.squareup.sqlbrite3.SqlBrite.Query;
import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;
import io.reactivex.Scheduler;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.functions.Predicate;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import java.io.Closeable;
import java.lang.annotation.Retention;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT;
import static android.database.sqlite.SQLiteDatabase.CONFLICT_FAIL;
import static android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE;
import static android.database.sqlite.SQLiteDatabase.CONFLICT_NONE;
import static android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE;
import static android.database.sqlite.SQLiteDatabase.CONFLICT_ROLLBACK;
import static com.squareup.sqlbrite3.QueryObservable.QUERY_OBSERVABLE;
import static java.lang.annotation.RetentionPolicy.SOURCE;
import static java.util.Collections.singletonList;

/**
 * A lightweight wrapper around {@link SupportSQLiteOpenHelper} which allows for continuously
 * observing the result of a query. Create using a {@link SqlBrite} instance.
 */
public final class BriteDatabase<M> implements Closeable {
  @NonNull private final SupportSQLiteOpenHelper helper;
  @NonNull private final Logger logger;
  @NonNull private final ObservableTransformer<Query, Query> queryTransformer;

  // Package-private to avoid synthetic accessor method for 'transaction' instance.
  @NonNull final ThreadLocal<@org.checkerframework.checker.nullness.qual.Nullable SqliteTransaction<M>> transactions = new ThreadLocal<>();
  @NonNull private final Subject<Trigger<M>> triggers = PublishSubject.create();

  @NonNull private final Transaction<M> transaction = new Transaction<M>() {
    @Override public void markSuccessful(@NonNull M marker) {
      @Nullable final SqliteTransaction<M> transaction = transactions.get();
      if (transaction == null) {
        throw new IllegalStateException("Not in transaction.");
      }
      if (logging) log("TXN SUCCESS %s, %s", String.valueOf(marker), String.valueOf(transaction));
      transaction.marker = marker;
      getWritableDatabase().setTransactionSuccessful();
    }

    @Override public boolean yieldIfContendedSafely() {
      return getWritableDatabase().yieldIfContendedSafely();
    }

    @Override public boolean yieldIfContendedSafely(long sleepAmount, @NonNull TimeUnit sleepUnit) {
      return getWritableDatabase().yieldIfContendedSafely(sleepUnit.toMillis(sleepAmount));
    }

    @Override public void end() {
      @Nullable final SqliteTransaction<M> transaction = transactions.get();
      if (transaction == null) {
        throw new IllegalStateException("Not in transaction.");
      }
      @Nullable final SqliteTransaction<M> newTransaction = transaction.parent;
      transactions.set(newTransaction);
      if (logging) log("TXN END %s", transaction);
      getWritableDatabase().endTransaction();
      // Send the triggers after ending the transaction in the DB.
      if (transaction.commit) {
        @NonNull final M marker = Objects.requireNonNull(transaction.marker);
        sendTableTrigger(transaction, marker);
      }
    }

    @Override public void close() {
      end();
    }
  };
  @NonNull private final Consumer<Object> ensureNotInTransaction = new Consumer<Object>() {
    @Override public void accept(@NonNull Object ignored) throws Exception {
      @Nullable final SqliteTransaction transaction = transactions.get();
      if (transaction != null) {
        throw new IllegalStateException("Cannot subscribe to observable query in a transaction.");
      }
    }
  };

  @NonNull private final Scheduler scheduler;

  // Package-private to avoid synthetic accessor method for 'transaction' instance.
  volatile boolean logging;

  BriteDatabase(@NonNull SupportSQLiteOpenHelper helper,
                @NonNull Logger logger,
                @NonNull Scheduler scheduler,
                @NonNull ObservableTransformer<Query, Query> queryTransformer) {
    this.helper = helper;
    this.logger = logger;
    this.scheduler = scheduler;
    this.queryTransformer = queryTransformer;
  }

  /**
   * Control whether debug logging is enabled.
   */
  public void setLoggingEnabled(boolean enabled) {
    logging = enabled;
  }

  /**
   * Create and/or open a database.  This will be the same object returned by
   * {@link SupportSQLiteOpenHelper#getWritableDatabase} unless some problem, such as a full disk,
   * requires the database to be opened read-only.  In that case, a read-only
   * database object will be returned.  If the problem is fixed, a future call
   * to {@link SupportSQLiteOpenHelper#getWritableDatabase} may succeed, in which case the read-only
   * database object will be closed and the read/write object will be returned
   * in the future.
   *
   * <p class="caution">Like {@link SupportSQLiteOpenHelper#getWritableDatabase}, this method may
   * take a long time to return, so you should not call it from the
   * application main thread, including from
   * {@link android.content.ContentProvider#onCreate ContentProvider.onCreate()}.
   *
   * @throws android.database.sqlite.SQLiteException if the database cannot be opened
   * @return a database object valid until {@link SupportSQLiteOpenHelper#getWritableDatabase}
   *     or {@link #close} is called.
   */
  @NonNull @CheckResult @WorkerThread
  public SupportSQLiteDatabase getReadableDatabase() {
    return helper.getReadableDatabase();
  }

  /**
   * Create and/or open a database that will be used for reading and writing.
   * The first time this is called, the database will be opened and
   * {@link Callback#onCreate}, {@link Callback#onUpgrade} and/or {@link Callback#onOpen} will be
   * called.
   *
   * <p>Once opened successfully, the database is cached, so you can
   * call this method every time you need to write to the database.
   * (Make sure to call {@link #close} when you no longer need the database.)
   * Errors such as bad permissions or a full disk may cause this method
   * to fail, but future attempts may succeed if the problem is fixed.</p>
   *
   * <p class="caution">Database upgrade may take a long time, you
   * should not call this method from the application main thread, including
   * from {@link android.content.ContentProvider#onCreate ContentProvider.onCreate()}.
   *
   * @throws android.database.sqlite.SQLiteException if the database cannot be opened for writing
   * @return a read/write database object valid until {@link #close} is called
   */
  @NonNull @CheckResult @WorkerThread
  public SupportSQLiteDatabase getWritableDatabase() {
    return helper.getWritableDatabase();
  }

  void sendTableTrigger(@NonNull Set<String> tables, @NonNull M marker) {
    @Nullable final SqliteTransaction<M> transaction = transactions.get();
    if (transaction != null) {
      transaction.addAll(tables);
    } else {
      if (logging) log("TRIGGER %s", tables);
      triggers.onNext(new Trigger<>(tables, marker));
    }
  }

  /**
   * Begin a transaction for this thread.
   * <p>
   * Transactions may nest. If the transaction is not in progress, then a database connection is
   * obtained and a new transaction is started. Otherwise, a nested transaction is started.
   * <p>
   * Each call to {@code newTransaction} must be matched exactly by a call to
   * {@link Transaction#end()}. To mark a transaction as successful, call
   * {@link Transaction#markSuccessful(M)} before calling {@link Transaction#end()}. If the
   * transaction is not successful, or if any of its nested transactions were not successful, then
   * the entire transaction will be rolled back when the outermost transaction is ended.
   * <p>
   * Transactions queue up all query notifications until they have been applied.
   * <p>
   * Here is the standard idiom for transactions:
   *
   * <pre>{@code
   * try (Transaction transaction = db.newTransaction()) {
   *   ...
   *   transaction.markSuccessful();
   * }
   * }</pre>
   *
   * Manually call {@link Transaction#end()} when try-with-resources is not available:
   * <pre>{@code
   * Transaction transaction = db.newTransaction();
   * try {
   *   ...
   *   transaction.markSuccessful();
   * } finally {
   *   transaction.end();
   * }
   * }</pre>
   *
   *
   * @see SupportSQLiteDatabase#beginTransaction()
   */
  @CheckResult @NonNull
  public Transaction<M> newTransaction() {
    @NonNull final SqliteTransaction transaction = new SqliteTransaction(transactions.get());
    transactions.set(transaction);
    if (logging) log("TXN BEGIN %s", transaction);
    getWritableDatabase().beginTransactionWithListener(transaction);

    return this.transaction;
  }

  /**
   * Begins a transaction in IMMEDIATE mode for this thread.
   * <p>
   * Transactions may nest. If the transaction is not in progress, then a database connection is
   * obtained and a new transaction is started. Otherwise, a nested transaction is started.
   * <p>
   * Each call to {@code newNonExclusiveTransaction} must be matched exactly by a call to
   * {@link Transaction#end()}. To mark a transaction as successful, call
   * {@link Transaction#markSuccessful(M)} before calling {@link Transaction#end()}. If the
   * transaction is not successful, or if any of its nested transactions were not successful, then
   * the entire transaction will be rolled back when the outermost transaction is ended.
   * <p>
   * Transactions queue up all query notifications until they have been applied.
   * <p>
   * Here is the standard idiom for transactions:
   *
   * <pre>{@code
   * try (Transaction transaction = db.newNonExclusiveTransaction()) {
   *   ...
   *   transaction.markSuccessful();
   * }
   * }</pre>
   *
   * Manually call {@link Transaction#end()} when try-with-resources is not available:
   * <pre>{@code
   * Transaction transaction = db.newNonExclusiveTransaction();
   * try {
   *   ...
   *   transaction.markSuccessful();
   * } finally {
   *   transaction.end();
   * }
   * }</pre>
   *
   *
   * @see SupportSQLiteDatabase#beginTransactionNonExclusive()
   */
  @CheckResult @NonNull
  public Transaction<M> newNonExclusiveTransaction() {
    @NonNull final SqliteTransaction transaction = new SqliteTransaction(transactions.get());
    transactions.set(transaction);
    if (logging) log("TXN BEGIN %s", transaction);
    getWritableDatabase().beginTransactionWithListenerNonExclusive(transaction);

    return this.transaction;
  }

  /**
   * Close the underlying {@link SupportSQLiteOpenHelper} and remove cached readable and writeable
   * databases. This does not prevent existing observables from retaining existing references as
   * well as attempting to create new ones for new subscriptions.
   */
  @Override public void close() {
    helper.close();
  }

  /**
   * Create an observable which will notify subscribers with a {@linkplain Query query} for
   * execution. Subscribers are responsible for <b>always</b> closing {@link Cursor} instance
   * returned from the {@link Query}.
   * <p>
   * Subscribers will receive an immediate notification for initial data as well as subsequent
   * notifications for when the supplied {@code table}'s data changes through the {@code insert},
   * {@code update}, and {@code delete} methods of this class. Unsubscribe when you no longer want
   * updates to a query.
   * <p>
   * Since database triggers are inherently asynchronous, items emitted from the returned
   * observable use the {@link Scheduler} supplied to {@link SqlBrite#wrapDatabaseHelper}. For
   * consistency, the immediate notification sent on subscribe also uses this scheduler. As such,
   * calling {@link Observable#subscribeOn subscribeOn} on the returned observable has no effect.
   * <p>
   * Note: To skip the immediate notification and only receive subsequent notifications when data
   * has changed call {@code skip(1)} on the returned observable.
   * <p>
   * <b>Warning:</b> this method does not perform the query! Only by subscribing to the returned
   * {@link Observable} will the operation occur.
   *
   * @see SupportSQLiteDatabase#query(String, Object[])
   */
  @CheckResult @NonNull
  public QueryObservable createQuery(@NonNull final String table,
                                     @NonNull String sql, @NonNull Object... args) {
    return createQuery(singletonList(table), new SimpleSQLiteQuery(sql, args));
  }

  /**
   * See {@link #createQuery(String, String, Object...)} for usage. This overload allows for
   * monitoring multiple tables for changes.
   *
   * @see SupportSQLiteDatabase#query(String, Object[])
   */
  @CheckResult @NonNull
  public QueryObservable createQuery(@NonNull final Iterable<String> tables,
                                     @NonNull String sql, @NonNull Object... args) {
    return createQuery(tables, new SimpleSQLiteQuery(sql, args));
  }

  /**
   * Create an observable which will notify subscribers with a {@linkplain Query query} for
   * execution. Subscribers are responsible for <b>always</b> closing {@link Cursor} instance
   * returned from the {@link Query}.
   * <p>
   * Subscribers will receive an immediate notification for initial data as well as subsequent
   * notifications for when the supplied {@code table}'s data changes through the {@code insert},
   * {@code update}, and {@code delete} methods of this class. Unsubscribe when you no longer want
   * updates to a query.
   * <p>
   * Since database triggers are inherently asynchronous, items emitted from the returned
   * observable use the {@link Scheduler} supplied to {@link SqlBrite#wrapDatabaseHelper}. For
   * consistency, the immediate notification sent on subscribe also uses this scheduler. As such,
   * calling {@link Observable#subscribeOn subscribeOn} on the returned observable has no effect.
   * <p>
   * Note: To skip the immediate notification and only receive subsequent notifications when data
   * has changed call {@code skip(1)} on the returned observable.
   * <p>
   * <b>Warning:</b> this method does not perform the query! Only by subscribing to the returned
   * {@link Observable} will the operation occur.
   *
   * @see SupportSQLiteDatabase#query(SupportSQLiteQuery)
   */
  @CheckResult @NonNull
  public QueryObservable createQuery(@NonNull final String table,
      @NonNull SupportSQLiteQuery query) {
    return createQuery(singletonList(table), query);
  }

  /**
   * See {@link #createQuery(String, SupportSQLiteQuery)} for usage. This overload allows for
   * monitoring multiple tables for changes.
   *
   * @see SupportSQLiteDatabase#query(SupportSQLiteQuery)
   */
  @CheckResult @NonNull
  public QueryObservable createQuery(@NonNull final Iterable<String> tables,
      @NonNull SupportSQLiteQuery query) {
    @Nullable final SqliteTransaction transaction = transactions.get();
    if (transaction != null) {
      throw new IllegalStateException("Cannot create observable query in transaction. "
              + "Use query() for a query inside a transaction.");
    }

    return triggers //
            .filter(new TriggerFilter(tables)) // TriggerFilter filters triggers to on tables we care about.
            .map(new TriggerToQueryFunction(tables, query)) // TriggerToQueryFunction maps to a new MarkedQuery to capture the marker.
            .startWith(new MarkedQuery(null, tables, query)) // No marker for the initial query
            .observeOn(scheduler) //
            .compose(queryTransformer) // Apply the user's query transformer.
            .doOnSubscribe(ensureNotInTransaction)
            .to(QUERY_OBSERVABLE);
  }

  /**
   * Runs the provided SQL and returns a {@link Cursor} over the result set.
   *
   * @see SupportSQLiteDatabase#query(String, Object[])
   */
  @CheckResult @NonNull @WorkerThread
  public Cursor query(@NonNull String sql, @NonNull Object... args) {
    @NonNull final Cursor cursor = Objects.requireNonNull(getReadableDatabase().query(sql, args));
    if (logging) {
      log("QUERY\n  sql: %s\n  args: %s", indentSql(sql), Arrays.toString(args));
    }

    return cursor;
  }

  /**
   * Runs the provided {@link SupportSQLiteQuery} and returns a {@link Cursor} over the result set.
   *
   * @see SupportSQLiteDatabase#query(SupportSQLiteQuery)
   */
  @CheckResult @NonNull @WorkerThread
  public Cursor query(@NonNull SupportSQLiteQuery query) {
    @NonNull final Cursor cursor = Objects.requireNonNull(getReadableDatabase().query(query));
    if (logging) {
      log("QUERY\n  sql: %s", indentSql(query.getSql()));
    }

    return cursor;
  }

  /**
   * Insert a row into the specified {@code table} and notify any subscribed queries.
   *
   * @see SupportSQLiteDatabase#insert(String, int, ContentValues)
   */
  @WorkerThread
  public long insert(@NonNull String table, @NonNull M marker, @ConflictAlgorithm int conflictAlgorithm,
      @NonNull ContentValues values) {
    @NonNull final SupportSQLiteDatabase db = getWritableDatabase();

    if (logging) {
      log("INSERT\n  table: %s\n  marker: %s\n  values: %s\n  conflictAlgorithm: %s",
          table, marker, values, conflictString(conflictAlgorithm));
    }
    final long rowId = db.insert(table, conflictAlgorithm, values);

    if (logging) log("INSERT id: %s", rowId);

    if (rowId != -1) {
      // Only send a table trigger if the insert was successful.
      sendTableTrigger(Collections.singleton(table), marker);
    }
    return rowId;
  }

  /**
   * Delete rows from the specified {@code table} and notify any subscribed queries. This method
   * will not trigger a notification if no rows were deleted.
   *
   * @see SupportSQLiteDatabase#delete(String, String, Object[])
   */
  @WorkerThread
  public int delete(@NonNull String table, @NonNull M marker, @Nullable String whereClause,
      @Nullable String... whereArgs) {
    @NonNull final SupportSQLiteDatabase db = getWritableDatabase();

    if (logging) {
      log("DELETE\n  table: %s\n  marker: %s\n  whereClause: %s\n  whereArgs: %s",
          table,
          marker,
          String.valueOf(whereClause),
          Arrays.toString(whereArgs));
    }
    final int rows = db.delete(table, whereClause, whereArgs);

    if (logging) log("DELETE affected %s %s", rows, rows != 1 ? "rows" : "row");

    if (rows > 0) {
      // Only send a table trigger if rows were affected.
      sendTableTrigger(Collections.singleton(table), marker);
    }
    return rows;
  }

  /**
   * Update rows in the specified {@code table} and notify any subscribed queries. This method
   * will not trigger a notification if no rows were updated.
   *
   * @see SupportSQLiteDatabase#update(String, int, ContentValues, String, Object[])
   */
  @WorkerThread
  public int update(@NonNull String table, @NonNull M marker, @ConflictAlgorithm int conflictAlgorithm,
      @NonNull ContentValues values, @Nullable String whereClause, @Nullable String... whereArgs) {
    @NonNull final SupportSQLiteDatabase db = getWritableDatabase();

    if (logging) {
      log("UPDATE\n  table: %s\n  marker: %s\n  values: %s\n  whereClause: %s\n  whereArgs: %s\n  conflictAlgorithm: %s",
          table, marker, values, String.valueOf(whereClause), Arrays.toString(whereArgs),
          conflictString(conflictAlgorithm));
    }
    final int rows = db.update(table, conflictAlgorithm, values, whereClause, whereArgs);

    if (logging) log("UPDATE affected %s %s", rows, rows != 1 ? "rows" : "row");

    if (rows > 0) {
      // Only send a table trigger if rows were affected.
      sendTableTrigger(Collections.singleton(table), marker);
    }
    return rows;
  }

  /**
   * Execute {@code sql} provided it is NOT a {@code SELECT} or any other SQL statement that
   * returns data. No data can be returned (such as the number of affected rows). Instead, use
   * {@link #insert}, {@link #update}, et al, when possible.
   * <p>
   * No notifications will be sent to queries if {@code sql} affects the data of a table.
   *
   * @see SupportSQLiteDatabase#execSQL(String)
   */
  @WorkerThread
  public void execute(@NonNull String sql) {
    if (logging) log("EXECUTE\n  sql: %s", indentSql(sql));

    // SupportSQLiteDatabase#execSQL(String) isn't annotated, but sql gets passed
    // through to android.database.DatabaseUtils#getSqlStatementType(String), which
    // is also not annotated, but does require sql to be @NonNull.
    getWritableDatabase().execSQL(sql);
  }

  /**
   * Execute {@code sql} provided it is NOT a {@code SELECT} or any other SQL statement that
   * returns data. No data can be returned (such as the number of affected rows). Instead, use
   * {@link #insert}, {@link #update}, et al, when possible.
   * <p>
   * No notifications will be sent to queries if {@code sql} affects the data of a table.
   *
   * @see SupportSQLiteDatabase#execSQL(String, Object[])
   */
  @WorkerThread
  public void execute(@NonNull String sql, @NonNull Object... args) {
    if (logging) log("EXECUTE\n  sql: %s\n  args: %s", indentSql(sql), Arrays.toString(args));

    // SupportSQLiteDatabase#execSQL(String) isn't annotated, but sql gets passed
    // through to android.database.DatabaseUtils#getSqlStatementType(String), which
    // is also not annotated, but does require sql to be @NonNull.
    // android.database.sqlite.SQLiteDatabase#execSQL(String,Object[]) explicitly
    // checks for a @NonNull args, but the method itself isn't annotated.
    getWritableDatabase().execSQL(sql, args);
  }

  /**
   * Execute {@code sql} provided it is NOT a {@code SELECT} or any other SQL statement that
   * returns data. No data can be returned (such as the number of affected rows). Instead, use
   * {@link #insert}, {@link #update}, et al, when possible.
   * <p>
   * A notification to queries for {@code table} will be sent after the statement is executed.
   *
   * @see SupportSQLiteDatabase#execSQL(String)
   */
  @WorkerThread
  public void executeAndTrigger(@NonNull String table, @NonNull M marker, @NonNull String sql) {
    executeAndTrigger(Collections.singleton(table), marker, sql);
  }

  /**
   * See {@link #executeAndTrigger(String, M, String)} for usage. This overload allows for triggering multiple tables.
   *
   * @see BriteDatabase#executeAndTrigger(String, M, String)
   */
  @WorkerThread
  public void executeAndTrigger(@NonNull Set<String> tables, @NonNull M marker, @NonNull String sql) {
    execute(sql);

    sendTableTrigger(tables, marker);
  }

  /**
   * Execute {@code sql} provided it is NOT a {@code SELECT} or any other SQL statement that
   * returns data. No data can be returned (such as the number of affected rows). Instead, use
   * {@link #insert}, {@link #update}, et al, when possible.
   * <p>
   * A notification to queries for {@code table} will be sent after the statement is executed.
   *
   * @see SupportSQLiteDatabase#execSQL(String, Object[])
   */
  @WorkerThread
  public void executeAndTrigger(@NonNull String table, @NonNull M marker, @NonNull String sql,
                                @NonNull Object... args) {
    executeAndTrigger(Collections.singleton(table), marker, sql, args);
  }

  /**
   * See {@link #executeAndTrigger(String, M, String, Object...)} for usage. This overload allows for triggering multiple tables.
   *
   * @see BriteDatabase#executeAndTrigger(String, M, String, Object...)
   */
  @WorkerThread
  public void executeAndTrigger(@NonNull Set<String> tables, @NonNull M marker, @NonNull String sql,
                                @NonNull Object... args) {
    execute(sql, args);

    sendTableTrigger(tables, marker);
  }

  /**
   * Execute {@code statement}, if the the number of rows affected by execution of this SQL
   * statement is of any importance to the caller - for example, UPDATE / DELETE SQL statements.
   *
   * @return the number of rows affected by this SQL statement execution.
   * @throws android.database.SQLException If the SQL string is invalid
   *
   * @see SupportSQLiteStatement#executeUpdateDelete()
   */
  @WorkerThread
  public int executeUpdateDelete(@NonNull String table, @NonNull M marker,
                                 @NonNull SupportSQLiteStatement statement) {
    return executeUpdateDelete(Collections.singleton(table), marker, statement);
  }

  /**
   * See {@link #executeUpdateDelete(String, M, SupportSQLiteStatement)} for usage. This overload
   * allows for triggering multiple tables.
   *
   * @see BriteDatabase#executeUpdateDelete(String, M, SupportSQLiteStatement)
   */
  @WorkerThread
  public int executeUpdateDelete(@NonNull Set<String> tables, @NonNull M marker,
                                 @NonNull SupportSQLiteStatement statement) {
    if (logging) log("EXECUTE\n %s", statement);

    final int rows = statement.executeUpdateDelete();
    if (rows > 0) {
      // Only send a table trigger if rows were affected.
      sendTableTrigger(tables, marker);
    }
    return rows;
  }

  /**
   * Execute {@code statement} and return the ID of the row inserted due to this call.
   * The SQL statement should be an INSERT for this to be a useful call.
   *
   * @return the row ID of the last row inserted, if this insert is successful. -1 otherwise.
   *
   * @throws android.database.SQLException If the SQL string is invalid
   *
   * @see SupportSQLiteStatement#executeInsert()
   */
  @WorkerThread
  public long executeInsert(@NonNull String table, @NonNull M marker,
                            @NonNull SupportSQLiteStatement statement) {
    return executeInsert(Collections.singleton(table), marker, statement);
  }

  /**
   * See {@link #executeInsert(String, M, SupportSQLiteStatement)} for usage. This overload allows for
   * triggering multiple tables.
   *
   * @see BriteDatabase#executeInsert(String, M, SupportSQLiteStatement)
   */
  @WorkerThread
  public long executeInsert(@NonNull Set<String> tables, @NonNull M marker,
                            @NonNull SupportSQLiteStatement statement) {
    if (logging) log("EXECUTE\n %s", statement);

    final long rowId = statement.executeInsert();
    if (rowId != -1) {
      // Only send a table trigger if the insert was successful.
      sendTableTrigger(tables, marker);
    }
    return rowId;
  }

  /** An in-progress database transaction. */
  public interface Transaction<M> extends Closeable {
    /**
     * End a transaction. See {@link #newTransaction()} for notes about how to use this and when
     * transactions are committed and rolled back.
     *
     * @see SupportSQLiteDatabase#endTransaction()
     */
    @WorkerThread
    void end();

    /**
     * Marks the current transaction as successful. Do not do any more database work between
     * calling this and calling {@link #end()}. Do as little non-database work as possible in that
     * situation too. If any errors are encountered between this and {@link #end()} the transaction
     * will still be committed.
     *
     * @param marker A value that will be sent along with active queries to mark the results
     *               as coming from this transaction.
     *
     * @see SupportSQLiteDatabase#setTransactionSuccessful()
     */
    @WorkerThread
    void markSuccessful(@NonNull M marker);

    /**
     * Temporarily end the transaction to let other threads run. The transaction is assumed to be
     * successful so far. Do not call {@link #markSuccessful(M)} before calling this. When this
     * returns a new transaction will have been created but not marked as successful. This assumes
     * that there are no nested transactions (newTransaction has only been called once) and will
     * throw an exception if that is not the case.
     *
     * @return true if the transaction was yielded
     *
     * @see SupportSQLiteDatabase#yieldIfContendedSafely()
     */
    @WorkerThread
    boolean yieldIfContendedSafely();

    /**
     * Temporarily end the transaction to let other threads run. The transaction is assumed to be
     * successful so far. Do not call {@link #markSuccessful(M)} before calling this. When this
     * returns a new transaction will have been created but not marked as successful. This assumes
     * that there are no nested transactions (newTransaction has only been called once) and will
     * throw an exception if that is not the case.
     *
     * @param sleepAmount if > 0, sleep this long before starting a new transaction if
     *   the lock was actually yielded. This will allow other background threads to make some
     *   more progress than they would if we started the transaction immediately.
     * @return true if the transaction was yielded
     *
     * @see SupportSQLiteDatabase#yieldIfContendedSafely(long)
     */
    @WorkerThread
    boolean yieldIfContendedSafely(long sleepAmount, @NonNull TimeUnit sleepUnit);

    /**
     * Equivalent to calling {@link #end()}
     */
    @WorkerThread
    @Override void close();
  }

  @IntDef({
      CONFLICT_ABORT,
      CONFLICT_FAIL,
      CONFLICT_IGNORE,
      CONFLICT_NONE,
      CONFLICT_REPLACE,
      CONFLICT_ROLLBACK
  })
  @Retention(SOURCE)
  private @interface ConflictAlgorithm {
  }

  // Package-private to avoid synthetic accessor method for 'DatabaseQuery' instances.
  @NonNull
  static String indentSql(@NonNull String sql) {
    return sql.replace("\n", "\n       ");
  }

  // Package-private to avoid synthetic accessor method for 'transaction' instance.
  void log(@NonNull String message, @NonNull Object... args) {
    if (args.length > 0) message = String.format(message, args);
    logger.log(message);
  }

  private static String conflictString(@ConflictAlgorithm int conflictAlgorithm) {
    switch (conflictAlgorithm) {
      case CONFLICT_ABORT:
        return "abort";
      case CONFLICT_FAIL:
        return "fail";
      case CONFLICT_IGNORE:
        return "ignore";
      case CONFLICT_NONE:
        return "none";
      case CONFLICT_REPLACE:
        return "replace";
      case CONFLICT_ROLLBACK:
        return "rollback";
      default:
        return "unknown (" + conflictAlgorithm + ')';
    }
  }

  static final class Trigger<M> {
    @NonNull final Set<String> tables;
    @NonNull final M marker;

    Trigger(@NonNull Set<String> tables, @NonNull M marker) {
      this.tables = tables;
      this.marker = marker;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      Trigger<?> trigger = (Trigger<?>) o;

      if (!tables.equals(trigger.tables)) return false;
      return marker.equals(trigger.marker);
    }

    @Override
    public int hashCode() {
      int result = tables.hashCode();
      result = 31 * result + marker.hashCode();
      return result;
    }

    @Override
    public String toString() {
      return "Trigger[" +
              "tables=" + tables +
              ", marker=" + marker +
              ']';
    }
  }

  static final class SqliteTransaction<M> extends LinkedHashSet<String>
      implements SQLiteTransactionListener {
    @Nullable final SqliteTransaction<M> parent;
    boolean commit;
    @Nullable M marker;

    SqliteTransaction(@Nullable SqliteTransaction<M> parent) {
      this.parent = parent;
    }

    @Override public void onBegin() {
    }

    @Override public void onCommit() {
      commit = true;
    }

    @Override public void onRollback() {
    }

    @NonNull @Override public String toString() {
      @NonNull final String name = String.format("%08x", System.identityHashCode(this));
      return parent == null ? name : name + " [" + parent.toString() + ']';
    }
  }

  final class TriggerFilter implements Predicate<Trigger<M>> {
    @NonNull private final Iterable<String> tables;

    TriggerFilter(@NonNull Iterable<String> tables) {
      this.tables = tables;
    }

    @Override public boolean test(@NonNull Trigger<M> trigger) {
      for (String table : tables) {
        if (trigger.tables.contains(table)) {
          return true;
        }
      }
      return false;
    }
  }

  final class TriggerToQueryFunction implements Function<Trigger<M>, Query<M>> {
    @NonNull private final Iterable<String> tables;
    @NonNull private final SupportSQLiteQuery query;

    TriggerToQueryFunction(@NonNull Iterable<String> tables, @NonNull SupportSQLiteQuery query) {
      this.tables = tables;
      this.query = query;
    }

    @NonNull @Override public String toString() {
      return query.getSql();
    }

    @NonNull @Override public Query<M> apply(@NonNull Trigger<M> trigger) {
      return new MarkedQuery(trigger.marker, tables, query);
    }
  }

  final class MarkedQuery extends Query<M> {
    @NonNull private final Iterable<String> tables;
    @NonNull private final SupportSQLiteQuery query;

    MarkedQuery(@Nullable M marker,
                @NonNull Iterable<String> tables,
                @NonNull SupportSQLiteQuery query) {
      super(marker);
      this.tables = tables;
      this.query = query;
    }

    @Nullable @Override public Cursor run() {
      if (transactions.get() != null) {
        throw new IllegalStateException("Cannot execute observable query in a transaction.");
      }

      @Nullable final Cursor cursor = getReadableDatabase().query(query);

      if (logging) {
        log("QUERY\n  tables: %s\n  marker: %s\n  sql: %s",
                tables, String.valueOf(marker), indentSql(query.getSql()));
      }

      return cursor;
    }
  }
}
