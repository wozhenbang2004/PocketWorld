#include <jni.h>
#include "network_filter.h"
#include "process_identity.h"
#include <cerrno>
#include <csignal>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <string>
#include <vector>

// Independently authored POSIX process ownership. Not a VM or UID/security boundary.
namespace {
long long now_ms() {
    timespec t{}; clock_gettime(CLOCK_MONOTONIC,&t);
    return static_cast<long long>(t.tv_sec)*1000+t.tv_nsec/1000000;
}
void close_fd(int& fd) { if(fd>=0) { close(fd); fd=-1; } }
std::string bytes(JNIEnv* env,jbyteArray input) {
    if(!input) return {};
    const jsize size=env->GetArrayLength(input);
    std::string result(static_cast<size_t>(size),'\0');
    env->GetByteArrayRegion(input,0,size,reinterpret_cast<jbyte*>(result.data()));
    return result;
}
std::vector<std::string> strings(JNIEnv* env,jobjectArray input) {
    std::vector<std::string> values;
    for(jsize i=0;i<env->GetArrayLength(input);i++) {
        auto value=static_cast<jbyteArray>(env->GetObjectArrayElement(input,i));
        values.push_back(bytes(env,value)); env->DeleteLocalRef(value);
    }
    return values;
}
std::vector<char*> pointers(std::vector<std::string>& values) {
    std::vector<char*> result;
    for(auto& value:values) result.push_back(value.data());
    result.push_back(nullptr); return result;
}
void nonblocking(int fd) { fcntl(fd,F_SETFL,fcntl(fd,F_GETFL)|O_NONBLOCK); }
void child_error(int fd,int error) {
    const char* data=reinterpret_cast<const char*>(&error);
    size_t written=0;
    while(written<sizeof(error)) {
        ssize_t n=write(fd,data+written,sizeof(error)-written);
        if(n>0) written+=static_cast<size_t>(n);
        else if(errno!=EINTR) break;
    }
    _exit(127);
}
}

static jlongArray supervise(
        JNIEnv* env,jobjectArray command,jobjectArray environment,jbyteArray directory,
        jlong timeout_ms,jlong grace_ms,jobject observer,bool offline) {
    auto argv=strings(env,command),env_values=strings(env,environment);
    auto argv_ptr=pointers(argv),env_ptr=pointers(env_values);
    const std::string cwd=bytes(env,directory);
    if(env->ExceptionCheck()) return nullptr;
    jclass observer_class=env->GetObjectClass(observer);
    jmethodID output=env->GetMethodID(observer_class,"output","(I[B)Z");
    jmethodID cancelled=env->GetMethodID(observer_class,"cancelled","()Z");
    jmethodID started=env->GetMethodID(observer_class,"started","()Z");
    jmethodID identity=env->GetMethodID(observer_class,"processIdentity","(JJJ)Z");
    if(env->ExceptionCheck()) return nullptr;
    int pipes[3][2]={{-1,-1},{-1,-1},{-1,-1}};
    int launch_error=0;
    bool entry_cancelled=env->CallBooleanMethod(observer,cancelled)==JNI_TRUE;
    if(env->ExceptionCheck()) {env->ExceptionClear();entry_cancelled=true;}
    if(entry_cancelled) launch_error=ECANCELED;
    for(auto& pair:pipes) {
        if(pipe2(pair,O_CLOEXEC)<0) { launch_error=errno; break; }
    }
    pid_t pid=-1;
    // argv/env storage and pipe setup are complete before fork; the child only uses async-safe calls.
    if(!launch_error) {
        pid=fork();
        if(pid<0) launch_error=errno;
        else if(pid==0) {
            close(pipes[0][0]); close(pipes[1][0]); close(pipes[2][0]);
            if(setsid()<0) child_error(pipes[2][1],errno);
            sigset_t empty;sigemptyset(&empty);sigprocmask(SIG_SETMASK,&empty,nullptr);
            struct sigaction normal{};normal.sa_handler=SIG_DFL;sigemptyset(&normal.sa_mask);
            for(int signal:{SIGTERM,SIGINT,SIGHUP,SIGQUIT,SIGPIPE}) sigaction(signal,&normal,nullptr);
            int input=open("/dev/null",O_RDONLY|O_CLOEXEC);
            if(input<0 || dup2(input,STDIN_FILENO)<0 || dup2(pipes[0][1],STDOUT_FILENO)<0
                    || dup2(pipes[1][1],STDERR_FILENO)<0) child_error(pipes[2][1],errno);
            close(input); close(pipes[0][1]); close(pipes[1][1]);
            if(chdir(cwd.c_str())<0) child_error(pipes[2][1],errno);
            if(offline&&pocketworld_install_offline_filter()!=0)child_error(pipes[2][1],errno?errno:ENOTSUP);
            execve(argv_ptr[0],argv_ptr.data(),env_ptr.data());
            child_error(pipes[2][1],errno);
        }
    }
    for(auto& pair:pipes) close_fd(pair[1]);
    bool stopped=entry_cancelled,timed_out=false,io_complete=true,leader_done=false,kill_sent=false,start_notified=false;
    long long start=now_ms(),stop_at=0,leader_at=0;
    int status=0;
    if(pid>0) {
        for(auto& pair:pipes) nonblocking(pair[0]);
        while(true) {
            const auto time=now_ms();
            bool wants_stop=env->CallBooleanMethod(observer,cancelled)==JNI_TRUE;
            if(env->ExceptionCheck()) { env->ExceptionClear(); wants_stop=true; io_complete=false; }
            if(time-start>=timeout_ms) { timed_out=true; wants_stop=true; }
            if(wants_stop && !stopped) {
                stopped=true; stop_at=time;
                // The child may not have reached setsid yet. Signal the owned leader as well.
                kill(-pid,SIGTERM); kill(pid,SIGTERM);
            }
            if(stopped && !kill_sent && time-stop_at>=grace_ms) {
                kill(-pid,SIGKILL); kill(pid,SIGKILL); kill_sent=true;
            }
            if(!leader_done) {
                siginfo_t info{};
                // Leave the leader unreaped until group cleanup, preventing its PID/PGID reuse here.
                if(waitid(P_PID,static_cast<id_t>(pid),&info,WEXITED|WNOHANG|WNOWAIT)==0 && info.si_pid==pid) {
                    leader_done=true; leader_at=time;
                    // A successful shell may have left background children holding pipes or resources.
                    kill(-pid,SIGTERM);
                }
            }
            if(leader_done && time-leader_at>=grace_ms) kill(-pid,SIGKILL);
            pollfd descriptors[3];
            for(int i=0;i<3;i++) descriptors[i]={pipes[i][0],POLLIN,0};
            int polled=poll(descriptors,3,50);
            if(polled<0 && errno!=EINTR) { io_complete=false; if(!stopped) { stopped=true;stop_at=time; } }
            for(int i=0;i<3;i++) {
                if(pipes[i][0]<0) continue;
                // Limit each turn so a noisy process cannot starve cancellation checks.
                for(int batch=0;batch<16;batch++) {
                    char buffer[8192];
                    const auto count=read(pipes[i][0],buffer,sizeof(buffer));
                    if(count==0) {
                        close_fd(pipes[i][0]);
                        // EOF on the CLOEXEC error pipe with no reported errno, while
                        // the owned leader is still observed live. This acknowledges
                        // process ownership/launch only, not a listening server or URL.
                        if(i==2&&!launch_error&&!leader_done&&!stopped&&!start_notified) {
                            start_notified=true;
                            PocketworldProcessIdentity owned{};bool accepted=true;
                            if(pocketworld_process_identity(pid,owned)&&owned.session==pid)accepted=env->CallBooleanMethod(observer,identity,static_cast<jlong>(pid),static_cast<jlong>(owned.born),static_cast<jlong>(owned.session))==JNI_TRUE;
                            if(env->ExceptionCheck()){env->ExceptionClear();accepted=false;}
                            if(accepted)accepted=env->CallBooleanMethod(observer,started)==JNI_TRUE;
                            if(env->ExceptionCheck()) {env->ExceptionClear();accepted=false;}
                            if(!accepted) {io_complete=false;stopped=true;stop_at=now_ms();kill(-pid,SIGTERM);kill(pid,SIGTERM);}
                        }
                        break;
                    }
                    if(count<0) {
                        if(errno==EINTR) continue;
                        if(errno!=EAGAIN && errno!=EWOULDBLOCK) {io_complete=false;close_fd(pipes[i][0]);}
                        break;
                    }
                    if(i==2) {
                        if(count==sizeof(int)) std::memcpy(&launch_error,buffer,sizeof(int));
                        else { launch_error=EIO; io_complete=false; }
                        continue;
                    }
                    if(io_complete) {
                        jbyteArray data=env->NewByteArray(static_cast<jsize>(count));
                        if(!data) { env->ExceptionClear(); io_complete=false; }
                        else {
                            env->SetByteArrayRegion(data,0,static_cast<jsize>(count),reinterpret_cast<jbyte*>(buffer));
                            const bool accepted=env->CallBooleanMethod(observer,output,i+1,data)==JNI_TRUE;
                            env->DeleteLocalRef(data);
                            if(env->ExceptionCheck()) { env->ExceptionClear(); io_complete=false; }
                            if(!accepted) io_complete=false;
                        }
                        if(!io_complete && !stopped) { stopped=true;stop_at=now_ms();kill(-pid,SIGTERM);kill(pid,SIGTERM); }
                    }
                }
            }
            if(leader_done && pipes[0][0]<0 && pipes[1][0]<0 && pipes[2][0]<0) break;
            if(leader_done && time-leader_at>grace_ms+2000) {io_complete=false;break;}
        }
        // Even detached stdout does not make a background member an owned persistent job.
        kill(-pid,SIGKILL);
        while(waitpid(pid,&status,0)<0) { if(errno!=EINTR) {io_complete=false;break;} }
    }
    for(auto& pair:pipes) {close_fd(pair[0]);close_fd(pair[1]);}
    bool group_absent=pid<=0 || (kill(-pid,0)<0 && errno==ESRCH);
    const jlong result[7]={pid>0 && WIFEXITED(status)?WEXITSTATUS(status):-1,
        pid>0 && WIFSIGNALED(status)?WTERMSIG(status):0,launch_error,
        stopped?1:0,timed_out?1:0,group_absent?1:0,io_complete?1:0};
    jlongArray array=env->NewLongArray(7);
    if(array) env->SetLongArrayRegion(array,0,7,result);
    env->DeleteLocalRef(observer_class);
    return array;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_pocketengine_features_local_runtime_NativeProcessSupervisor_run(JNIEnv* env,jclass,jobjectArray command,jobjectArray environment,jbyteArray directory,jlong timeout,jlong grace,jobject observer){
    return supervise(env,command,environment,directory,timeout,grace,observer,false);
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_pocketengine_features_local_runtime_NativeProcessSupervisor_runOffline(JNIEnv* env,jclass,jobjectArray command,jobjectArray environment,jbyteArray directory,jlong timeout,jlong grace,jobject observer){
    return supervise(env,command,environment,directory,timeout,grace,observer,true);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_pocketengine_features_local_runtime_NativeProcessSupervisor_sameOwnedSession(JNIEnv*,jclass,jlong leader,jlong born,jlong session,jlong peer){
    return born>0&&pocketworld_same_owned_session(static_cast<long>(leader),static_cast<unsigned long long>(born),static_cast<long>(session),static_cast<long>(peer))?JNI_TRUE:JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_pocketengine_features_local_runtime_NativeProcessSupervisor_currentIdentityAvailable(JNIEnv*,jclass){PocketworldProcessIdentity current{};return pocketworld_process_identity(getpid(),current)?JNI_TRUE:JNI_FALSE;}
