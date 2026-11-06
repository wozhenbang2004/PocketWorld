package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;

/** Display membership/order only, never frozen content or permission to act. */
final class TaskListSelection {
    private TaskListSelection() {}

    static ArrayList<String> recentIds(SQLiteDatabase db) {
        boolean owns=!db.inTransaction();if(owns)db.beginTransactionNonExclusive();
        try {
            ArrayList<String> ids=new ArrayList<>();
            // Freeze only the original recent-200 ID order. Do not materialize
            // 200 large documents before presentation or silently expand the window.
            // This IMMEDIATE reservation covers metadata selection only.
            try(Cursor rows=db.rawQuery("SELECT task_id FROM tasks ORDER BY updated_at DESC LIMIT 200",null)){
                while(rows.moveToNext())ids.add(rows.getString(0));
            }
            if(owns)db.setTransactionSuccessful();return ids;
        }finally{if(owns)db.endTransaction();}
    }
}
