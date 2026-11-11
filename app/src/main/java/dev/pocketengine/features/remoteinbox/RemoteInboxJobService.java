package dev.pocketengine.features.remoteinbox;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import android.os.Build;
import android.util.Log;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONObject;

/** Durable catch-up, not an assertion of real-time delivery. Push only requests this work. */
public final class RemoteInboxJobService extends JobService {
    private static final int JOB_ID = 30217;
    private static final int CATCH_UP_ID = 30219;
    private static final int WAKE_ID = 30220;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final class Run {
        volatile boolean stopped;
        volatile Future<?> future;
    }
    private final ConcurrentHashMap<Integer, Run> work = new ConcurrentHashMap<>();

    public static void schedule(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs == null) throw new IllegalStateException("设备不支持后台同步");
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, RemoteInboxJobService.class))
            .setPeriodic(JobInfo.getMinPeriodMillis())
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true).build();
        if (jobs.schedule(job) != JobScheduler.RESULT_SUCCESS) throw new IllegalStateException("后台同步注册失败");
    }
    public static void requestCatchUp(Context context) throws Exception { request(context, false); }
    public static void requestPushCatchUp(Context context) throws Exception { request(context, true); }
    private static void request(Context context, boolean wake) throws Exception {
        RemoteInboxStore store = RemoteInboxStore.get(context);
        if (!store.configured()) return;
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs == null) throw new IllegalStateException("设备不支持后台同步");
        String identity = store.configId();
        int jobId = wake ? WAKE_ID : CATCH_UP_ID;
        JobInfo pending = jobs.getPendingJob(jobId);
        if (pending != null && identity.equals(pending.getExtras().getString("configId"))) return;
        PersistableBundle args = new PersistableBundle();
        args.putString("configId", identity);
        JobInfo.Builder builder = new JobInfo.Builder(jobId, new ComponentName(context, RemoteInboxJobService.class))
            .setExtras(args).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true).setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        if (wake && Build.VERSION.SDK_INT >= 31) {
            builder.setExpedited(true);
            if (jobs.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS) return;
            builder.setExpedited(false); // No expedited quota: keep durable ordinary catch-up instead.
        }
        // Android may defer or stop this job. The periodic job remains an independent recovery path.
        if (jobs.schedule(builder.build()) != JobScheduler.RESULT_SUCCESS)
            throw new IllegalStateException("补收任务未能登记，请打开通知中心同步");
    }
    public static void foreground(Context context) {
        Context app = context.getApplicationContext();
        try {
            if (!RemoteInboxStore.get(app).configured()) return;
            if (!isScheduled(app)) schedule(app);
            try { RemoteInboxPush.reconcile(app); }
            catch (Exception unavailable) { Log.w("PocketRemoteInbox", "Optional push reconciliation deferred"); }
            requestCatchUp(app);
        } catch (Exception failure) { Log.w("PocketRemoteInbox", "Inbox recovery could not be scheduled"); }
    }
    public static void cancel(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs != null) { jobs.cancel(JOB_ID); jobs.cancel(CATCH_UP_ID); jobs.cancel(WAKE_ID); }
    }
    public static boolean isScheduled(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        return jobs != null && jobs.getPendingJob(JOB_ID) != null;
    }
    @Override public boolean onStartJob(JobParameters params) {
        Run run = new Run();
        work.put(params.getJobId(), run);
        run.future = executor.submit(() -> {
            boolean retry = false;
            try {
                RemoteInboxStore store = RemoteInboxStore.get(this);
                if (run.stopped || !store.configured()) return;
                if (params.getJobId() != JOB_ID && !store.configId().equals(params.getExtras().getString("configId"))) return;
                try { RemoteInboxPush.maintain(this); }
                catch (Exception failure) { retry = true; Log.w("PocketRemoteInbox", "Push registration maintenance deferred"); }
                JSONObject result = RemoteInboxSync.run(this);
                String status = result.optString("status");
                retry |= status.equals("waiting_network") || status.equals("already_running") || status.equals("more_pending");
                if (status.equals("synced")) {
                    try { RemoteInboxPush.acknowledge(this); }
                    catch (Exception failure) { retry = true; Log.w("PocketRemoteInbox", "Push catch-up acknowledgement deferred"); }
                }
            } catch (Exception failure) {
                try { retry = !"credentials_required".equals(RemoteInboxStore.get(this).snapshot().optJSONObject("lastSync").optString("status")); }
                catch (Exception ignored) { retry = false; }
                Log.w("PocketRemoteInbox", "Scheduled inbox catch-up deferred");
            } finally {
                try { retry |= RemoteInboxPush.needsMaintenance(this); }
                catch (Exception ignored) { }
                if (retry && params.getJobId() == JOB_ID && !run.stopped) {
                    try { requestCatchUp(this); } catch (Exception ignored) { }
                }
                if (work.remove(params.getJobId(), run) && !run.stopped)
                    jobFinished(params, retry && params.getJobId() != JOB_ID);
            }
        });
        if (run.stopped) run.future.cancel(true);
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        Run pending = work.remove(params.getJobId());
        if (pending != null) {
            pending.stopped = true;
            if (pending.future != null) pending.future.cancel(true);
        }
        return params.getJobId() != JOB_ID;
    }
    @Override public void onDestroy() {
        for (Run pending : work.values()) pending.stopped = true;
        work.clear(); executor.shutdownNow(); super.onDestroy();
    }
}
