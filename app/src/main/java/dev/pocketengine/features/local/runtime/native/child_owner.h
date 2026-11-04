#pragma once

#include <cerrno>
#include <csignal>
#include <cstddef>
#include <cstdint>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#if defined(__ANDROID__)
#include <android/api-level.h>
#endif

// A private, per-invocation parent of the command, not an application reaper or
// daemon. Only this single-threaded process can wait for its child. In particular,
// an App/JVM waitpid(-1) cannot reap the command between inspection and signalling.
// The App sends requests over an unexported socket; saved PID/PGID values never
// authorize an App-side signal. The owner never reaps until final group cleanup.
namespace pocketprocess {

// Android 11's App seccomp policy can SIGSYS on pidfd syscalls. Its ordinary
// group cleanup still works through ChildOwner; do not probe those calls there.
// On newer platforms check the real syscalls, not just build-header constants.
inline bool pidfd_signals_available() {
#if defined(__ANDROID__)
    if(android_get_device_api_level()<31)return false;
#endif
#if defined(__NR_pidfd_open) && defined(__NR_pidfd_send_signal)
    int descriptor=static_cast<int>(syscall(__NR_pidfd_open,getpid(),0));
    if(descriptor<0)return false;
    const bool available=syscall(__NR_pidfd_send_signal,descriptor,0,nullptr,0)==0;
    close(descriptor);return available;
#else
    return false;
#endif
}

inline bool pidfd_not_reaped(int descriptor) {
    pollfd observation{descriptor,POLLIN,0};
    int result;do {result=poll(&observation,1,0);} while(result<0&&errno==EINTR);
    return result>=0&&(observation.revents&(POLLHUP|POLLERR|POLLNVAL))==0;
}

inline long long monotonic_ms() {
    timespec now{};clock_gettime(CLOCK_MONOTONIC,&now);
    return static_cast<long long>(now.tv_sec)*1000+now.tv_nsec/1000000;
}

constexpr int owner_wait_flag=static_cast<int>(0x80000000U); // Linux __WCLONE.
constexpr idtype_t pidfd_wait_type=static_cast<idtype_t>(3); // Linux P_PIDFD.
enum class OwnerOperation : int { hello=1, observe=2, signal=3, reap=4, finish=5 };
struct OwnerMessage {
    uint32_t magic=0x50574f34;
    OwnerOperation operation=OwnerOperation::hello;
    uint64_t sequence=0;
    int child=-1,error=0,exited=0,reaped=0,status=0,signal=0,include_leader=0;
};

inline bool owner_send(int fd,const OwnerMessage& message) {
    ssize_t count;
    do {count=send(fd,&message,sizeof(message),MSG_NOSIGNAL);} while(count<0&&errno==EINTR);
    return count==static_cast<ssize_t>(sizeof(message));
}

inline pid_t private_fork(int exit_signal) {
#if defined(__NR_clone) && (defined(__aarch64__) || defined(__arm__) || defined(__x86_64__) || defined(__i386__))
    // fork semantics (no CLONE_VM, copied stack), with an explicit wait domain.
    // Both child branches below use only async-safe operations before exec/_exit.
    return static_cast<pid_t>(syscall(__NR_clone,exit_signal,nullptr,nullptr,nullptr,0));
#else
    (void)exit_signal;errno=ENOSYS;return -1;
#endif
}

// No malloc, stdio, JNI or inherited at-fork-unsafe directory iterator is used
// in the owner. It must not keep unrelated App descriptors open for a long job.
inline bool close_inherited_fds(int control,const int* keep,size_t count) {
#if defined(__NR_getdents64)
    int directory=open("/proc/self/fd",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    if(directory<0)return false;
    struct Entry {uint64_t inode;int64_t offset;unsigned short length;unsigned char type;char name[1];};
    alignas(Entry) char buffer[4096];bool complete=true;
    while(true) {
        long length=syscall(__NR_getdents64,directory,buffer,sizeof(buffer));
        if(length<0){if(errno==EINTR)continue;complete=false;break;}
        if(length==0)break;
        for(long offset=0;offset<length;) {
            auto* entry=reinterpret_cast<Entry*>(buffer+offset);
            const size_t name_offset=offsetof(Entry,name);
            if(entry->length<=name_offset||offset+entry->length>length){complete=false;break;}
            int fd=0;bool numeric=true,terminated=false;
            for(size_t at=name_offset;at<entry->length;at++) {
                const char value=buffer[offset+static_cast<long>(at)];
                if(value=='\0'){terminated=true;break;}
                if(value<'0'||value>'9'||fd>214748363){numeric=false;break;}
                fd=fd*10+value-'0';
            }
            bool retained=fd<=STDERR_FILENO||fd==control||fd==directory;
            for(size_t at=0;at<count;at++)if(fd==keep[at])retained=true;
            if(numeric&&terminated&&!retained)close(fd);
            offset+=entry->length;
        }
        if(!complete)break;
    }
    int saved=errno;close(directory);errno=saved;return complete;
#else
    (void)control;(void)keep;(void)count;errno=ENOSYS;return false;
#endif
}

inline int child_observation(pid_t child,bool& exited) {
    siginfo_t info{};int result;
    do {result=waitid(P_PID,static_cast<id_t>(child),&info,WEXITED|WNOHANG|WNOWAIT);} while(result<0&&errno==EINTR);
    if(result<0)return errno;
    if(info.si_pid!=0&&info.si_pid!=child)return ECHILD;
    exited=info.si_pid==child;return 0;
}

// This runs ONLY in the single-threaded true parent. Its SIGCHLD disposition is
// reset, and no handler or other thread can reap this child. WNOWAIT therefore
// really pins this original PID and its group until this owner explicitly reaps.
inline int signal_child(pid_t child,bool reaped,int signal,bool include_leader) {
    if(reaped)return ECHILD;
    bool exited=false;int error=child_observation(child,exited);if(error)return error;
    if(kill(-child,signal)<0&&errno!=ESRCH)return errno;
    // Before setsid the group may not exist yet. The direct child is still ours.
    if(include_leader&&kill(child,signal)<0&&errno!=ESRCH)return errno;
    return 0;
}

inline bool close_owned_child(pid_t child,bool& reaped,int& status,long long grace_ms) {
    if(reaped)return true;
    if(signal_child(child,false,SIGTERM,true)!=0)return false;
    const long long deadline=monotonic_ms()+grace_ms;
    while(monotonic_ms()<deadline) {
        bool exited=false;if(child_observation(child,exited)!=0)return false;
        if(exited)break;
        poll(nullptr,0,10);
    }
    // Also clean descendants when the command itself has already exited.
    if(signal_child(child,false,SIGKILL,true)!=0)return false;
    const long long reap_deadline=monotonic_ms()+1000;
    do {
        pid_t result=waitpid(child,&status,WNOHANG);
        if(result==child){reaped=true;return true;}
        if(result<0&&errno!=EINTR)return false;
        poll(nullptr,0,10);
    } while(monotonic_ms()<reap_deadline);
    return false;
}

[[noreturn]] inline void owner_loop(int control,pid_t child,long long grace_ms) {
    bool reaped=false;int status=0;
    OwnerMessage hello;hello.child=child;
    bool connected=owner_send(control,hello);
    while(connected) {
        OwnerMessage request{};ssize_t count;
        do {count=recv(control,&request,sizeof(request),MSG_TRUNC);} while(count<0&&errno==EINTR);
        if(count!=static_cast<ssize_t>(sizeof(request))||request.magic!=hello.magic||request.child!=child)break;
        OwnerMessage reply;reply.operation=request.operation;reply.sequence=request.sequence;reply.child=child;
        bool exited=false;
        if(request.operation==OwnerOperation::observe) {
            reply.error=reaped?ECHILD:child_observation(child,exited);reply.exited=exited?1:0;
        } else if(request.operation==OwnerOperation::signal) {
            if((request.signal!=SIGTERM&&request.signal!=SIGKILL)||(request.include_leader!=0&&request.include_leader!=1))reply.error=EINVAL;
            else reply.error=signal_child(child,reaped,request.signal,request.include_leader==1);
        } else if(request.operation==OwnerOperation::reap) {
            // The final signal and reap share the true parent's ownership, even
            // if the App's last successful observation is arbitrarily old.
            reply.error=signal_child(child,reaped,SIGKILL,true);
            if(!reply.error) {
                pid_t waited;do {waited=waitpid(child,&status,WNOHANG);} while(waited<0&&errno==EINTR);
                if(waited==child)reaped=true;else if(waited<0)reply.error=errno;
            }
            reply.reaped=reaped?1:0;reply.status=status;
        } else if(request.operation==OwnerOperation::finish) {
            const bool cleaned=close_owned_child(child,reaped,status,grace_ms);
            reply.error=cleaned?0:EIO;reply.reaped=reaped?1:0;reply.status=status;
            owner_send(control,reply);close(control);_exit(cleaned?0:125);
        } else reply.error=EINVAL;
        connected=owner_send(control,reply);
    }
    const bool cleaned=close_owned_child(child,reaped,status,grace_ms);
    close(control);_exit(cleaned?0:125);
}

class ChildOwner {
    int control_=-1;
    int wait_handle_=-1;
    pid_t owner_=-1,child_=-1;
    uint64_t sequence_=0;
    long long grace_ms_=0;
    bool failed_=false,settled_=false,no_child_confirmed_=false;

    void bind_owner_wait() {
#if defined(__ANDROID__)
        if(android_get_device_api_level()<31)return;
#endif
#if defined(__NR_pidfd_open)
        int descriptor=static_cast<int>(syscall(__NR_pidfd_open,owner_,0));if(descriptor<0)return;
        siginfo_t info{};
        // This also probes actual P_PIDFD support. The private clone wait domain
        // keeps the owner waitable if an old kernel lacks either pidfd operation.
        if(waitid(pidfd_wait_type,static_cast<id_t>(descriptor),&info,WEXITED|WNOHANG|WNOWAIT|owner_wait_flag)==0)wait_handle_=descriptor;
        else close(descriptor);
#endif
    }

    bool receive(OwnerMessage& reply,long long timeout_ms) {
        const auto deadline=monotonic_ms()+timeout_ms;
        while(true) {
            long long remaining=deadline-monotonic_ms();if(remaining<=0){errno=ETIMEDOUT;return false;}
            pollfd descriptor{control_,POLLIN,0};int ready=poll(&descriptor,1,static_cast<int>(remaining));
            if(ready<0&&errno==EINTR)continue;
            if(ready<=0){if(ready==0)errno=ETIMEDOUT;return false;}
            ssize_t count=recv(control_,&reply,sizeof(reply),MSG_DONTWAIT|MSG_TRUNC);
            if(count<0&&(errno==EINTR||errno==EAGAIN))continue;
            if(count!=static_cast<ssize_t>(sizeof(reply))||reply.magic!=OwnerMessage{}.magic){errno=EPIPE;return false;}
            return true;
        }
    }
    bool call(OwnerMessage& message,long long timeout_ms=1000) {
        if(failed_||control_<0){errno=EPIPE;return false;}
        message.child=child_;message.sequence=++sequence_;
        OwnerMessage response;
        if(!owner_send(control_,message)||!receive(response,timeout_ms)||response.sequence!=message.sequence||response.operation!=message.operation||response.child!=child_) {
            failed_=true;int saved=errno;close(control_);control_=-1;errno=saved?saved:EPIPE;return false;
        }
        message=response;if(message.error){errno=message.error;return false;}return true;
    }
public:
    ChildOwner()=default;
    ChildOwner(const ChildOwner&)=delete;
    ChildOwner& operator=(const ChildOwner&)=delete;
    ~ChildOwner(){finish();}
    pid_t parent() const {return owner_;}

    // Returns 0 only in the original command child, which immediately performs
    // the existing async-safe descriptor/session/exec setup. No helper uses JNI.
    pid_t spawn(const int* transport,size_t count,long long grace_ms,int& launch_error) {
        grace_ms_=grace_ms;
        int sockets[2];if(socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,sockets)<0){launch_error=errno;return -1;}
        for(int at=0;at<2;at++)if(sockets[at]<3) {
            int moved=fcntl(sockets[at],F_DUPFD_CLOEXEC,3);
            if(moved<0){launch_error=errno;close(sockets[0]);close(sockets[1]);return -1;}
            close(sockets[at]);sockets[at]=moved;
        }
        // Normal App/JVM reapers cannot consume a zero-exit-signal clone child.
        // This private wait domain assumes no uncoordinated __WALL/__WCLONE
        // sweeper or arbitrary FD destruction inside the same App process.
        owner_=private_fork(0);
        if(owner_<0){launch_error=errno;close(sockets[0]);close(sockets[1]);return -1;}
        if(owner_==0) {
            close(sockets[0]);
            struct sigaction normal{};normal.sa_handler=SIG_DFL;sigemptyset(&normal.sa_mask);
            for(int number=1;number<NSIG;number++)if(number!=SIGKILL&&number!=SIGSTOP)sigaction(number,&normal,nullptr);
            sigset_t empty;sigemptyset(&empty);sigprocmask(SIG_SETMASK,&empty,nullptr);
            if(!close_inherited_fds(sockets[1],transport,count)) {
                OwnerMessage failure;failure.error=errno?errno:EIO;owner_send(sockets[1],failure);close(sockets[1]);_exit(0);
            }
            pid_t command=private_fork(SIGCHLD);
            if(command==0){close(sockets[1]);return 0;}
            int error=command<0?errno:0;
            for(size_t at=0;at<count;at++)if(transport[at]>=0)close(transport[at]);
            close(STDIN_FILENO);close(STDOUT_FILENO);close(STDERR_FILENO);
            if(error){OwnerMessage failure;failure.error=error;owner_send(sockets[1],failure);close(sockets[1]);_exit(0);}
            owner_loop(sockets[1],command,grace_ms);
        }
        close(sockets[1]);control_=sockets[0];OwnerMessage hello;
        if(!receive(hello,3000)||hello.operation!=OwnerOperation::hello||hello.sequence!=0) {
            launch_error=errno?errno:EIO;failed_=true;return -1;
        }
        bind_owner_wait();
        if(hello.error){launch_error=hello.error;no_child_confirmed_=true;return -1;}
        if(hello.child<=1){launch_error=EIO;failed_=true;return -1;}
        child_=hello.child;return child_;
    }
    bool observe(bool& exited) {
        OwnerMessage message;message.operation=OwnerOperation::observe;
        if(!call(message))return false;
        exited=message.exited==1;return true;
    }
    bool signal(int number,bool include_leader=true) {
        OwnerMessage message;message.operation=OwnerOperation::signal;message.signal=number;message.include_leader=include_leader?1:0;
        return call(message);
    }
    pid_t reap(int& status) {
        OwnerMessage message;message.operation=OwnerOperation::reap;
        if(!call(message))return -1;
        if(message.reaped){status=message.status;return child_;}return 0;
    }
    bool certain_no_child() const {return child_<0&&!failed_&&(owner_<0||no_child_confirmed_);}
    bool finish() {
        if(settled_)return !failed_;
        settled_=true;
        if(control_>=0&&!failed_&&child_>0) {
            OwnerMessage terminal;terminal.operation=OwnerOperation::finish;
            if(!call(terminal,grace_ms_+1500)||terminal.reaped!=1)failed_=true;
        }
        // Only this explicit, original-control-channel receipt proves cleanup.
        // Reaping the helper is housekeeping, never a substitute cleanup proof.
        if(control_>=0){close(control_);control_=-1;}
        if(owner_<=0)return !failed_;
        const auto deadline=monotonic_ms()+grace_ms_+1500;
        while(true) {
            int status=0;pid_t waited=-1;
            if(wait_handle_>=0) {
                siginfo_t info{};int result=waitid(pidfd_wait_type,static_cast<id_t>(wait_handle_),&info,WEXITED|WNOHANG|owner_wait_flag);
                if(result==0){waited=info.si_pid;if(waited>0)status=info.si_code==CLD_EXITED?info.si_status<<8:info.si_status| (info.si_code==CLD_DUMPED?0x80:0);}
            } else waited=waitpid(owner_,&status,WNOHANG|owner_wait_flag);
            if(waited==owner_){if(!WIFEXITED(status)||WEXITSTATUS(status)!=0)failed_=true;break;}
            if(waited<0&&errno!=EINTR){failed_=true;break;}
            if(monotonic_ms()>=deadline){failed_=true;break;}
            poll(nullptr,0,10);
        }
        if(wait_handle_>=0){close(wait_handle_);wait_handle_=-1;}
        return !failed_;
    }
};
}
