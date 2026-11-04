#include <jni.h>
#include <atomic>
#include <cerrno>
#include <csignal>
#include <cstdarg>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <new>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
#include "../../../../../../../main/java/dev/pocketengine/features/local/runtime/native/child_owner.h"

// Linked only into isolated test JVMs. The fixture never reaps or signals a
// guessed/reused PID. Owner death is delivered through a real, already bound
// pidfd; only this test JVM becomes a subreaper for its explicitly created tree.
struct Facts {
    std::atomic<int> app{0},owner{0},child{0},phase{0},observations{0},reap_count{0},reap_status{0},late_signals{0},child_errors{0};
    std::atomic<int> app_signals{0},attempts{0},owner_reaps{0},owner_status{0},owner_signals{0},triggered{0},inherited_closed{0},pidfd_probes{0};
};
static Facts* facts=nullptr;
static int owner_fd=-1,extra_fd=-1,control_fd=-1;
extern "C" int __real_waitid(idtype_t,id_t,siginfo_t*,int);
extern "C" pid_t __real_waitpid(pid_t,int*,int);
extern "C" int __real_kill(pid_t,int);
extern "C" ssize_t __real_send(int,const void*,size_t,int);
extern "C" ssize_t __real_recv(int,void*,size_t,int);
extern "C" long __real_syscall(long,...);
#if defined(POCKETWORLD_TRANSPORT_WAIT_FIXTURE)
extern "C" DIR* __real_opendir(const char*);
extern "C" DIR* __wrap_opendir(const char* path){return __real_opendir(path);}
#endif

static bool app_process(){return facts&&getpid()==facts->app.load();}
static void note_child_reap(pid_t child,int status){facts->reap_status=status;facts->child=child;facts->reap_count++;}
static void app_cannot_reap_child() {
    facts->attempts++;
    std::thread competitor([](){int status=0;pid_t result=__real_waitpid(facts->child.load(),&status,WNOHANG);
        if(result<0&&errno==ECHILD)facts->child_errors++;else if(result>0)note_child_reap(result,status);
        // The owner is a private zero-exit-signal clone child; a normal App/JVM
        // reaper cannot steal that either, even after it exits.
        result=__real_waitpid(facts->owner.load(),&status,WNOHANG);
        if(result<0&&errno==ECHILD)facts->child_errors++;
    });competitor.join();
}
static void remove_exact_owner_and_reap_original() {
    if(owner_fd<0)return;
    const pid_t owner=facts->owner.load(),child=facts->child.load();
    // The descriptor was bound while the owner's private hello was received.
    if(__real_syscall(__NR_pidfd_send_signal,owner_fd,SIGKILL,nullptr,0)<0)return;
    close(owner_fd);owner_fd=-1;
    std::thread competitor([owner,child](){
        int status=0;pid_t result;do{result=__real_waitpid(owner,&status,pocketprocess::owner_wait_flag);}while(result<0&&errno==EINTR);
        if(result==owner){facts->owner_reaps++;facts->owner_status=status;}
        // Owner death reparents this exact original zombie to this test-only
        // subreaper. The successful observation is NOT replaced with ECHILD.
        do{result=__real_waitpid(child,&status,0);}while(result<0&&errno==EINTR);
        if(result==child)note_child_reap(result,status);
        siginfo_t check{};if(__real_waitid(P_PID,static_cast<id_t>(child),&check,WEXITED|WNOHANG|WNOWAIT)<0&&errno==ECHILD)facts->child_errors++;
    });competitor.join();
}
extern "C" int __wrap_waitid(idtype_t kind,id_t id,siginfo_t* info,int flags) {
    int result=__real_waitid(kind,id,info,flags);
    if(facts&&result==0&&kind==P_PID&&info&&info->si_pid==static_cast<pid_t>(id)&&(flags&WNOWAIT)) {
        facts->child=static_cast<pid_t>(id);facts->observations++;
    }
    if(app_process()&&result==0&&kind==pocketprocess::pidfd_wait_type&&info&&info->si_pid==facts->owner.load()&&!(flags&WNOWAIT)) {
        facts->owner_reaps++;facts->owner_status=info->si_code==CLD_EXITED?info->si_status<<8:info->si_status;
    }
    return result;
}
extern "C" pid_t __wrap_waitpid(pid_t child,int* status,int flags) {
    pid_t result=__real_waitpid(child,status,flags);
    if(facts&&result>0&&status) {
        if(getpid()==facts->owner.load()&&result==facts->child.load())note_child_reap(result,*status);
        if(app_process()&&result==facts->owner.load()){facts->owner_reaps++;facts->owner_status=*status;}
    }
    return result;
}
extern "C" ssize_t __wrap_send(int socket,const void* data,size_t count,int flags) {
    if(facts&&count==sizeof(pocketprocess::OwnerMessage)) {
        const auto& message=*static_cast<const pocketprocess::OwnerMessage*>(data);
        if(message.magic==pocketprocess::OwnerMessage{}.magic) {
            if(!app_process()&&message.operation==pocketprocess::OwnerOperation::hello) {
                facts->owner=getpid();facts->child=message.child;
                if(extra_fd>=0&&fcntl(extra_fd,F_GETFD)<0&&errno==EBADF)facts->inherited_closed=1;
            }
            if(app_process()&&message.operation==pocketprocess::OwnerOperation::signal&&facts->triggered.load()==0) {
                const int phase=facts->phase.load();
                if((phase==1||phase==2)&&facts->observations.load()>=phase) {
                    // Exactly the final gap: all App observation/proc checks
                    // succeeded, and its next action is the private signal RPC.
                    facts->triggered=1;remove_exact_owner_and_reap_original();
                } else if(phase==4) {
                    facts->triggered=1;shutdown(socket,SHUT_RDWR);
                }
            }
        }
    }
    return __real_send(socket,data,count,flags);
}
extern "C" ssize_t __wrap_recv(int socket,void* data,size_t count,int flags) {
    ssize_t result=__real_recv(socket,data,count,flags);
    if(app_process()&&result==static_cast<ssize_t>(sizeof(pocketprocess::OwnerMessage))) {
        const auto& message=*static_cast<const pocketprocess::OwnerMessage*>(data);
        if(message.magic==pocketprocess::OwnerMessage{}.magic) {
            control_fd=socket;
            if(message.operation==pocketprocess::OwnerOperation::hello&&message.child>1) {
                owner_fd=static_cast<int>(__real_syscall(__NR_pidfd_open,facts->owner.load(),0));
            }
            if(message.operation==pocketprocess::OwnerOperation::observe&&message.exited&&facts->phase.load()==3&&facts->attempts.load()==0)app_cannot_reap_child();
        }
    }
    return result;
}
extern "C" int __wrap_kill(pid_t target,int signal) {
    if(facts&&signal!=0&&(target==facts->child.load()||target==-facts->child.load())) {
        if(app_process())facts->app_signals++;
        else if(getpid()==facts->owner.load())facts->owner_signals++;
        if(facts->reap_count.load()>0){facts->late_signals++;errno=ESRCH;return -1;}
    }
    return __real_kill(target,signal);
}
// Only direct syscalls used by the actual supervisor/header are interposed.
// Phase 5 proves the original group still works when optional pidfds fail.
extern "C" long __wrap_syscall(long number,...) {
    va_list args;va_start(args,number);long result=-1;
    if(number==__NR_clone){int flags=va_arg(args,int);void* stack=va_arg(args,void*);void* parent=va_arg(args,void*);void* child=va_arg(args,void*);int tls=va_arg(args,int);result=__real_syscall(number,flags,stack,parent,child,tls);}
    else if(number==__NR_getdents64){int fd=va_arg(args,int);void* data=va_arg(args,void*);size_t count=va_arg(args,size_t);result=__real_syscall(number,fd,data,count);}
    else if(number==__NR_pidfd_open){int pid=va_arg(args,int);int flags=va_arg(args,int);if(facts)facts->pidfd_probes++;if(facts&&facts->phase.load()==5){errno=ENOSYS;result=-1;}else result=__real_syscall(number,pid,flags);}
    else if(number==__NR_pidfd_send_signal){int fd=va_arg(args,int),signal=va_arg(args,int);void* info=va_arg(args,void*);int flags=va_arg(args,int);if(facts&&facts->phase.load()==5){errno=ENOSYS;result=-1;}else result=__real_syscall(number,fd,signal,info,flags);}
    else errno=ENOSYS;
    va_end(args);return result;
}
static void arm(JNIEnv* env,int phase) {
    if(!facts){void* shared=mmap(nullptr,sizeof(Facts),PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);if(shared==MAP_FAILED){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"own fixture mmap failed");return;}facts=new(shared) Facts();}
    facts->app=getpid();facts->phase=phase;
    if(prctl(PR_SET_CHILD_SUBREAPER,1,0,0,0)<0){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"own fixture subreaper failed");return;}
    extra_fd=open("/dev/null",O_RDONLY|O_CLOEXEC);
}
static jlongArray proof(JNIEnv* env) {
    if(owner_fd>=0){close(owner_fd);owner_fd=-1;}
    const bool control_closed=control_fd<0||(fcntl(control_fd,F_GETFD)<0&&errno==EBADF);
    const bool original_extra_open=extra_fd>=0&&fcntl(extra_fd,F_GETFD)>=0;
    if(extra_fd>=0){close(extra_fd);extra_fd=-1;}
    const jlong values[]={facts->observations.load(),facts->reap_count.load(),facts->child.load(),facts->reap_status.load(),facts->late_signals.load(),facts->child_errors.load(),
        facts->app_signals.load(),facts->attempts.load(),facts->owner_reaps.load(),facts->owner_status.load(),facts->owner_signals.load(),facts->triggered.load(),facts->inherited_closed.load(),control_closed?1:0,original_extra_open?1:0,facts->pidfd_probes.load()};
    jlongArray result=env->NewLongArray(16);if(result)env->SetLongArrayRegion(result,0,16,values);return result;
}
extern "C" JNIEXPORT void JNICALL Java_dev_pocketengine_features_local_runtime_LostWaitOwnershipProbe_arm(JNIEnv* env,jclass,jint phase){arm(env,phase);}
extern "C" JNIEXPORT jlongArray JNICALL Java_dev_pocketengine_features_local_runtime_LostWaitOwnershipProbe_facts(JNIEnv* env,jclass){return proof(env);}
extern "C" JNIEXPORT void JNICALL Java_dev_pocketengine_features_local_runtime_NativeTransportWaitOwnershipProbe_arm(JNIEnv* env,jclass,jint phase){arm(env,phase);}
extern "C" JNIEXPORT jlongArray JNICALL Java_dev_pocketengine_features_local_runtime_NativeTransportWaitOwnershipProbe_facts(JNIEnv* env,jclass){return proof(env);}
