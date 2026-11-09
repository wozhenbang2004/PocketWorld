package dev.pocketengine.features.remoteinbox;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** OS-managed offline catch-up. Android chooses its schedule; it is not real-time push. */
public final class RemoteInboxJobService extends JobService {
    private static final int JOB_ID = 30217;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<?> work;

    public static void schedule(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs == null) throw new IllegalStateException("设备不支持后台同步");
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, RemoteInboxJobService.class))
            .setPeriodic(JobInfo.getMinPeriodMillis())
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true).build();
        if (jobs.schedule(job) != JobScheduler.RESULT_SUCCESS) throw new IllegalStateException("后台同步注册失败");
    }
    public static void cancel(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs != null) jobs.cancel(JOB_ID);
    }
    @Override public boolean onStartJob(JobParameters params) {
        work = executor.submit(() -> {
            try {
                if (RemoteInboxStore.get(this).configured()) RemoteInboxSync.run(this);
            } catch (Exception error) {
                Log.w("PocketRemoteInbox", "Scheduled inbox catch-up failed", error);
            } finally { jobFinished(params, false); }
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        Future<?> pending = work;
        if (pending != null) pending.cancel(true);
        return false;
    }
    @Override public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
