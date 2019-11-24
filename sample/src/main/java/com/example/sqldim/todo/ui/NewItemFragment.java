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

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import com.example.sqldim.todo.R;
import com.example.sqldim.todo.TodoApp;
import com.example.sqldim.todo.db.TodoItem;
import com.jakewharton.rxbinding2.widget.RxTextView;
import com.stealthmountain.sqldim.BriteDatabase;
import io.reactivex.Observable;
import io.reactivex.functions.BiFunction;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;
import javax.inject.Inject;

import static android.database.sqlite.SQLiteDatabase.CONFLICT_NONE;

public final class NewItemFragment extends DialogFragment {
  private static final String KEY_LIST_ID = "list_id";

  public static NewItemFragment newInstance(long listId) {
    Bundle arguments = new Bundle();
    arguments.putLong(KEY_LIST_ID, listId);

    NewItemFragment fragment = new NewItemFragment();
    fragment.setArguments(arguments);
    return fragment;
  }

  private final PublishSubject<String> createClicked = PublishSubject.create();

  @Inject BriteDatabase<Object> db;

  private long getListId() {
    return getArguments().getLong(KEY_LIST_ID);
  }

  @Override public void onAttach(@NonNull Context context) {
    super.onAttach(context);
    TodoApp.getComponent(context).inject(this);
  }

  @NonNull @Override public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
    final Context context = getActivity();
    View view = LayoutInflater.from(context).inflate(R.layout.new_item, null);

    EditText name = view.findViewById(android.R.id.input);
    Observable.combineLatest(createClicked, RxTextView.textChanges(name),
        new BiFunction<String, CharSequence, String>() {
          @Override public String apply(String ignored, CharSequence text) {
            return text.toString();
          }
        }) //
        .observeOn(Schedulers.io())
        .subscribe(new Consumer<String>() {
          @Override public void accept(String description) {
            db.insert(TodoItem.TABLE, CONFLICT_NONE,
                new TodoItem.Builder().listId(getListId()).description(description).build());
          }
        });

    return new AlertDialog.Builder(context) //
        .setTitle(R.string.new_item)
        .setView(view)
        .setPositiveButton(R.string.create, new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface dialog, int which) {
            createClicked.onNext("clicked");
          }
        })
        .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
          @Override public void onClick(@NonNull DialogInterface dialog, int which) {
          }
        })
        .create();
  }
}
