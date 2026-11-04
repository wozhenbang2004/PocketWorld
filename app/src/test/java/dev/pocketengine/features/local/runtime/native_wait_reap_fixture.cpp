#include <jni.h>
#include <atomic>
#include <cerrno>
#include <csignal>
#include <cstring>
#include <dirent.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <thread>

// Test link only, in a separate JVM. Reap only a real zombie returned by this
// original supervisor's exact P_PID wait; never install an App-wide reaper.
static std::atomic<int> after_observations{0},observations{0},reap_count{0},reap_status{0},late_signals{0},child_errors{0};
static std::atomic<pid_t> reaped_pid{0};
static std::atomic<int> transport_phase{0};
static std::atomic<pid_t> observed_pid{0};
extern "C" int __real_waitid(idtype_t,id_t,siginfo_t*,int);
extern "C" int __real_kill(pid_t,int);
#if defined(POCKETWORLD_TRANSPORT_WAIT_FIXTURE)
extern "C" DIR* __real_opendir(const char*);
#endif
static void reap_exact(pid_t pid) {
    std::thread other([pid]() {
        int status=0;pid_t waited;
        do {waited=waitpid(pid,&status,0);} while(waited<0&&errno==EINTR);
        if(waited==pid) {reap_status=status;reaped_pid=waited;reap_count++;}
    });
    other.join();
}
extern "C" int __wrap_waitid(idtype_t kind,id_t id,siginfo_t* info,int flags) {
    int result=__real_waitid(kind,id,info,flags);
    if(result==0&&kind==P_PID&&info&&info->si_pid==static_cast<pid_t>(id)
            &&(flags&WNOWAIT)&&transport_phase.load()==2&&reap_count.load()==0) {
        observations++;observed_pid=static_cast<pid_t>(id);
    }
    if(result==0&&kind==P_PID&&info&&info->si_pid==static_cast<pid_t>(id)
            &&(flags&WNOWAIT)&&after_observations.load()>0&&reap_count.load()==0
            &&++observations>=after_observations.load()) {
        // The other thread performs a real kernel waitpid, not a forged errno.
        reap_exact(static_cast<pid_t>(id));
        *info={};result=__real_waitid(kind,id,info,flags);
    }
    if(result<0&&errno==ECHILD&&reaped_pid.load()==static_cast<pid_t>(id))child_errors++;
    return result;
}
#if defined(POCKETWORLD_TRANSPORT_WAIT_FIXTURE)
extern "C" DIR* __wrap_opendir(const char* path) {
    // Consume the original child only after the supervisor has observed its real
    // zombie. This next /proc scan also covers the final cleanup scan when all IO
    // was drained in that turn. There is no PID reuse or unrelated signal target.
    pid_t original=observed_pid.load();
    if(transport_phase.load()==2&&std::strcmp(path,"/proc")==0&&original>0&&reap_count.load()==0) {
        reap_exact(original);siginfo_t info{};
        if(__real_waitid(P_PID,static_cast<id_t>(original),&info,WEXITED|WNOHANG|WNOWAIT)<0&&errno==ECHILD)child_errors++;
    }
    return __real_opendir(path);
}
#endif
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
    observations=0;reap_count=0;reap_status=0;late_signals=0;child_errors=0;reaped_pid=0;observed_pid=0;transport_phase=0;after_observations=count;
}
extern "C" JNIEXPORT jlongArray JNICALL Java_dev_pocketengine_features_local_runtime_LostWaitOwnershipProbe_facts(JNIEnv* env,jclass) {
    const jlong values[]={observations.load(),reap_count.load(),reaped_pid.load(),reap_status.load(),late_signals.load(),child_errors.load()};
    jlongArray result=env->NewLongArray(6);if(result)env->SetLongArrayRegion(result,0,6,values);return result;
}
extern "C" JNIEXPORT void JNICALL Java_dev_pocketengine_features_local_runtime_NativeTransportWaitOwnershipProbe_arm(JNIEnv* env,jclass type,jint phase) {
    Java_dev_pocketengine_features_local_runtime_LostWaitOwnershipProbe_arm(env,type,phase==1?1:0);transport_phase=phase;
}
extern "C" JNIEXPORT jlongArray JNICALL Java_dev_pocketengine_features_local_runtime_NativeTransportWaitOwnershipProbe_facts(JNIEnv* env,jclass type) {
    return Java_dev_pocketengine_features_local_runtime_LostWaitOwnershipProbe_facts(env,type);
}
