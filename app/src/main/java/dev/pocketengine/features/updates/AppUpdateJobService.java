package dev.pocketengine.features.updates;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Android schedules checks, not forced downloads. No background installation or arbitrary push payload. */
public final class AppUpdateJobService extends JobService {
    @Override public boolean onStartJob(JobParameters parameters) {
        AppUpdateManager manager = AppUpdateManager.get(this);
        if (!manager.configured() || !manager.automatic()) return false;
        manager.check(false, () -> jobFinished(parameters, false));
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        // A foreground owner download shares this manager. Do not cancel it for an unrelated check job.
        return false;
    }
}
