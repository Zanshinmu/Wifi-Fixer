/*
 * Wifi Fixer for Android
 *        Copyright (C) 2010-2016  David Van de Ven
 *
 *        This program is free software: you can redistribute it and/or modify
 *        it under the terms of the GNU General Public License as published by
 *        the Free Software Foundation, either version 3 of the License, or
 *        (at your option) any later version.
 *
 *        This program is distributed in the hope that it will be useful,
 *        but WITHOUT ANY WARRANTY; without even the implied warranty of
 *        MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *        GNU General Public License for more details.
 *
 *        You should have received a copy of the GNU General Public License
 *        along with this program.  If not, see http://www.gnu.org/licenses
 */

package org.wahtod.wififixer.utility;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.Observable;
import java.util.Observer;

/**
 * Created by zanshin on 11/16/13.
 */
public class LogOpenHelper extends SQLiteOpenHelper {
    public static final int DB_VERSION = 6;
    public static final String DB_NAME = "log";
    public static final String TABLE_NAME = "entries";
    public static final String ID_KEY = "id";
    public static final String TEXT_KEY = "logtext";
    public static final String TIMESTAMP_KEY = "timeStamp";
    private static volatile LogOpenHelper _instance;
    private volatile LogObservable logObservable;
    private volatile SQLiteDatabase database;

    private LogOpenHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        database = this.getWritableDatabase();
        logObservable = new LogObservable();
    }

    public static LogOpenHelper newinstance(Context context) {
        if (_instance == null) {
            _instance = new LogOpenHelper(context.getApplicationContext());
        }
        return _instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String query = "CREATE TABLE " + TABLE_NAME + "(" + ID_KEY + " INTEGER PRIMARY KEY," +
                TEXT_KEY + " TEXT," +
                TIMESTAMP_KEY + " DATE DEFAULT (datetime('now','localtime')))";
        db.execSQL(query);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
    }

    public synchronized void expireEntries() {
        String query = "DELETE FROM " + TABLE_NAME + " WHERE " + TIMESTAMP_KEY + " <= datetime('now','localtime','-30 minutes')";
        database.execSQL(query);
    }

    public synchronized String getEntry(long id) {
        if (id == 0) {
            return "";
        }
        Cursor cursor;
        cursor = database.query(TABLE_NAME, new String[]{ID_KEY, TEXT_KEY, TIMESTAMP_KEY}, ID_KEY + "=?",
                new String[]{String.valueOf(id)}, null, null, null, null);
        if (cursor != null)
            cursor.moveToFirst();
        StringBuilder out = new StringBuilder(cursor.getString(2))
                .append(": ")
                .append(cursor.getString(1))
                .append("\n");
        cursor.close();
        return out.toString();
    }

    public synchronized String getAllEntries() {
        StringBuilder out = new StringBuilder();
        String query = "SELECT * FROM " + TABLE_NAME;
        Cursor cursor = database.rawQuery(query, null);
        if (cursor.moveToFirst()) {
            do {
                out.append(cursor.getString(2))
                        .append(": ")
                        .append(cursor.getString(1))
                        .append("\n");

            } while (cursor.moveToNext());
        }
        cursor.close();
        return out.toString();
    }

    public synchronized long addLogEntry(String logEntry) {
        ContentValues values = new ContentValues();
        values.put(TEXT_KEY, logEntry);
        long out = database.insert(TABLE_NAME, null, values);
        if (logObservable.countObservers() >= 1) {
            logObservable.changed();
            logObservable.notifyObservers(String.valueOf(out));
        }
        return out;
    }

    public void registerLogObserver(Observer observer) {
        logObservable.addObserver(observer);
    }

    public void unregisterLogObserver(Observer observer) {
        logObservable.deleteObserver(observer);
    }

    private static class LogObservable extends Observable {
        @Override
        public void notifyObservers(Object data) {
            super.notifyObservers(data);
        }

        public void changed() {
            setChanged();
        }
    }
}
