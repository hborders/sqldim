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
package com.example.sqldim.todo.ui;

import android.database.Cursor;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.example.sqldim.todo.db.Db;
import com.example.sqldim.todo.db.TodoItem;
import com.example.sqldim.todo.db.TodoList;
import com.google.auto.value.AutoValue;
import com.stealthmountain.sqldim.FunctionRR;

import java.util.Arrays;
import java.util.Collection;

@AutoValue
abstract class ListsItem implements Parcelable {
  private static final String ALIAS_LIST = "list";
  private static final String ALIAS_ITEM = "item";

  private static final String LIST_ID = ALIAS_LIST + "." + TodoList.ID;
  private static final String LIST_NAME = ALIAS_LIST + "." + TodoList.NAME;
  private static final String ITEM_COUNT = "item_count";
  private static final String ITEM_ID = ALIAS_ITEM + "." + TodoItem.ID;
  private static final String ITEM_LIST_ID = ALIAS_ITEM + "." + TodoItem.LIST_ID;

  public static final Collection<String> TABLES = Arrays.asList(TodoList.TABLE, TodoItem.TABLE);
  public static final String QUERY = ""
      + "SELECT " + LIST_ID + ", " + LIST_NAME + ", COUNT(" + ITEM_ID + ") as " + ITEM_COUNT
      + " FROM " + TodoList.TABLE + " AS " + ALIAS_LIST
      + " LEFT OUTER JOIN " + TodoItem.TABLE + " AS " + ALIAS_ITEM + " ON " + LIST_ID + " = " + ITEM_LIST_ID
      + " GROUP BY " + LIST_ID;

  abstract long id();
  abstract String name();
  abstract int itemCount();

  static FunctionRR<Cursor, ListsItem> MAPPER = new FunctionRR<Cursor, ListsItem>() {
    @NonNull @Override public ListsItem applyRR(@NonNull Cursor cursor) {
      long id = Db.getLong(cursor, TodoList.ID);
      String name = Db.getString(cursor, TodoList.NAME);
      int itemCount = Db.getInt(cursor, ITEM_COUNT);
      return new AutoValue_ListsItem(id, name, itemCount);
    }
  };
}