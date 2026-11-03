#include <jni.h>
#include <atomic>
#include <cerrno>
#include <csignal>
#include <sys/types.h>
#include <sys/wait.h>
#include <thread>

// Test link only, in a separate JVM. Reap only a real zombie returned by this
// original supervisor's exact P_PID wait; never install an App-wide reaper.
static std::atomic<int> after_observations{0},observations{0},reap_count{0},reap_status{0},late_signals{0},child_errors{0};
static std::atomic<pid_t> reaped_pid{0};
extern "C" int __real_waitid(idtype_t,id_t,siginfo_t*,int);
extern "C" int __real_kill(pid_t,int);
extern "C" int __wrap_waitid(idtype_t kind,id_t id,siginfo_t* info,int flags) {
    int result=__real_waitid(kind,id,info,flags);
    if(result==0&&kind==P_PID&&info&&info->si_pid==static_cast<pid_t>(id)
            &&(flags&WNOWAIT)&&after_observations.load()>0&&reap_count.load()==0
            &&++observations>=after_observations.load()) {
        // The other thread performs a real kernel waitpid, not a forged errno.
        std::thread other([id]() {
            int status=0;pid_t waited;
            do {waited=waitpid(static_cast<pid_t>(id),&status,0);} while(waited<0&&errno==EINTR);
            if(waited==static_cast<pid_t>(id)) {reap_status=status;reaped_pid=waited;reap_count++;}
        });
        other.join();
        *info={};result=__real_waitid(kind,id,info,flags);
    }
    if(result<0&&errno==ECHILD&&reaped_pid.load()==static_cast<pid_t>(id))child_errors++;
    return result;
}
extern "C" int __wrap_kill(pid_t target,int signal) {
    const pid_t original=reaped_pid.load();
    if(signal!=0&&original>0&&(target==original||target==-original)) {
        // Prevent the deliberately failing old source from signalling a reused
        // number. A correct source must never even attempt this call.
        late_signals++;errno=ESRCH;return -1;
    }
    return __real_kill(target,signal);
}
extern "C" JNIEXPORT void JNICALL Java_dev_pocketengine_features_local_runtime_LostWaitOwnershipProbe_arm(JNIEnv*,jclass,jint count) {
    observations=0;reap_count=0;reap_status=0;late_signals=0;child_errors=0;reaped_pid=0;after_observations=count;
}
extern "C" JNIEXPORT jlongArray JNICALL Java_dev_pocketengine_features_local_runtime_LostWaitOwnershipProbe_facts(JNIEnv* env,jclass) {
    const jlong values[]={observations.load(),reap_count.load(),reaped_pid.load(),reap_status.load(),late_signals.load(),child_errors.load()};
    jlongArray result=env->NewLongArray(6);if(result)env->SetLongArrayRegion(result,0,6,values);return result;
}
