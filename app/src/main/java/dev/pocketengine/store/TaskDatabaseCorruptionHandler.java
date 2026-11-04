package dev.pocketengine.store;

import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import java.util.function.Consumer;

/** Never delegate to Android's deleting handler. Throwing also prevents its second open. */
public final class TaskDatabaseCorruptionHandler implements DatabaseErrorHandler {
    private final Consumer<String> observed;
    public TaskDatabaseCorruptionHandler(Consumer<String> observed){this.observed=observed==null?reason->{}:observed;}
    @Override public void onCorruption(SQLiteDatabase original) {
        SQLiteDatabaseCorruptException blocked=new SQLiteDatabaseCorruptException("task_database_suspected_corruption_preserved");
        try{observed.accept("suspected_corruption");}catch(RuntimeException unavailable){blocked.addSuppressed(unavailable);}
        // Do not close, query, rename, delete, or reopen files from inside this callback.
        throw blocked;
    }
}
