#include <jni.h>
#include <cerrno>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <map>
#include <sstream>
#include <string>
#include <vector>

// Native duplex IO mode of the same original supervisor/library. The old pipe7
// and PTY entries are untouched. No task, grant, daemon, JSON handle or FD export.
namespace {
long long clock_ms(){timespec time{};clock_gettime(CLOCK_MONOTONIC,&time);return static_cast<long long>(time.tv_sec)*1000+time.tv_nsec/1000000;}
void close_fd(int& fd){if(fd>=0){close(fd);fd=-1;}}
std::string bytes(JNIEnv* env,jbyteArray input){if(!input)return {};std::string value(static_cast<size_t>(env->GetArrayLength(input)),'\0');env->GetByteArrayRegion(input,0,static_cast<jsize>(value.size()),reinterpret_cast<jbyte*>(value.data()));return value;}
std::vector<std::string> strings(JNIEnv* env,jobjectArray input){std::vector<std::string> out;for(jsize i=0;i<env->GetArrayLength(input);i++){auto value=static_cast<jbyteArray>(env->GetObjectArrayElement(input,i));out.push_back(bytes(env,value));env->DeleteLocalRef(value);}return out;}
std::vector<char*> pointers(std::vector<std::string>& values){std::vector<char*> result;for(auto& value:values)result.push_back(value.data());result.push_back(nullptr);return result;}
void child_error(int fd,int error){const char* data=reinterpret_cast<const char*>(&error);size_t at=0;while(at<sizeof(error)){ssize_t n=write(fd,data+at,sizeof(error)-at);if(n>0)at+=static_cast<size_t>(n);else if(errno!=EINTR)break;}_exit(127);}
struct Process {pid_t pid=0,parent=0,group=0,session=0;unsigned long long born=0;char state=0;};
using Tracked=std::map<pid_t,unsigned long long>;
bool process(pid_t pid,Process& out){
    std::string path="/proc/"+std::to_string(pid);struct stat owner{};if(stat(path.c_str(),&owner)<0||owner.st_uid!=getuid())return false;
    int fd=open((path+"/stat").c_str(),O_RDONLY|O_CLOEXEC);if(fd<0)return false;char buffer[4097];ssize_t n;do{n=read(fd,buffer,sizeof(buffer)-1);}while(n<0&&errno==EINTR);close(fd);if(n<1||n==static_cast<ssize_t>(sizeof(buffer)-1))return false;
    std::string value(buffer,static_cast<size_t>(n));size_t end=value.rfind(')');if(end==std::string::npos)return false;std::istringstream input(value.substr(end+1));std::vector<std::string> fields;std::string item;while(input>>item)fields.push_back(item);if(fields.size()<20||fields[0].size()!=1)return false;
    char* tail=nullptr;auto number=[&](size_t at)->unsigned long long{errno=0;auto v=strtoull(fields[at].c_str(),&tail,10);if(errno||!tail||*tail!='\0')return 0;return v;};
    out.pid=pid;out.state=fields[0][0];out.parent=static_cast<pid_t>(number(1));out.group=static_cast<pid_t>(number(2));out.session=static_cast<pid_t>(number(3));out.born=number(19);return out.born>0&&out.session>0;
}
struct Scan {std::vector<Process> processes;bool complete=true;};
Scan scan(pid_t leader,Tracked& tracked,bool& escaped){
    Scan out;DIR* directory=opendir("/proc");if(!directory){out.complete=false;return out;}int count=0;
    while(true){errno=0;dirent* entry=readdir(directory);if(!entry){if(errno)out.complete=false;break;}char* end=nullptr;long pid=strtol(entry->d_name,&end,10);if(!end||*end||pid<2||pid>2147483647)continue;if(++count>8192){out.complete=false;break;}Process p;
        if(process(static_cast<pid_t>(pid),p))out.processes.push_back(p);
        else{std::string path="/proc/"+std::to_string(pid);struct stat present{};int checked=stat(path.c_str(),&present);if((checked==0&&present.st_uid==getuid())||(tracked.count(static_cast<pid_t>(pid))&&checked<0&&errno!=ENOENT&&errno!=ESRCH))out.complete=false;}
    }
    closedir(directory);
    for(const auto& p:out.processes)if(p.session==leader)tracked.emplace(p.pid,p.born);
    for(int pass=0;pass<256;pass++){bool added=false;for(const auto& p:out.processes){auto parent=tracked.find(p.parent);if(parent==tracked.end()||tracked.count(p.pid))continue;bool same=false;for(const auto& actual:out.processes)if(actual.pid==p.parent&&actual.born==parent->second)same=true;if(same){tracked.emplace(p.pid,p.born);added=true;}}if(!added)break;}
    if(tracked.size()>256)out.complete=false;
    for(const auto& p:out.processes){auto known=tracked.find(p.pid);if(known!=tracked.end()&&known->second==p.born&&p.session!=leader)escaped=true;}
    return out;
}
bool members(const Scan& current,pid_t leader,const Tracked& tracked){for(const auto& p:current.processes){auto known=tracked.find(p.pid);if(p.pid!=leader&&(p.session==leader||(known!=tracked.end()&&known->second==p.born)))return true;}return false;}
// The leader stays unreaped, reserving its PID/PGID. Other processes are signalled only through a
// pidfd after before/after birth checks; unsupported kernels fail closed rather than guessing IDs.
void signal_members(pid_t leader,const Scan& current,const Tracked& tracked,int signal){
    kill(-leader,signal);kill(leader,signal);
#if defined(__NR_pidfd_open) && defined(__NR_pidfd_send_signal)
    for(const auto& p:current.processes){auto known=tracked.find(p.pid);if(p.pid==leader||known==tracked.end()||known->second!=p.born)continue;int fd=static_cast<int>(syscall(__NR_pidfd_open,p.pid,0));if(fd<0)continue;Process actual;if(process(p.pid,actual)&&actual.born==known->second)syscall(__NR_pidfd_send_signal,fd,signal,nullptr,0);close(fd);}
#else
    (void)current;(void)tracked;
#endif
}
unsigned int u32(const std::string& value,size_t at){unsigned int n=0;for(size_t i=0;i<4;i++)n=(n<<8)|static_cast<unsigned char>(value[at+i]);return n;}
unsigned long long u64(const std::string& value,size_t at){unsigned long long n=0;for(size_t i=0;i<8;i++)n=(n<<8)|static_cast<unsigned char>(value[at+i]);return n;}
bool make_pipe(int pair[2]){
    if(pipe2(pair,O_CLOEXEC)<0)return false;
    for(int i=0;i<2;i++)if(pair[i]<3){int next=fcntl(pair[i],F_DUPFD_CLOEXEC,3);if(next<0)return false;close(pair[i]);pair[i]=next;}
    return true;
}
bool set_nonblocking(int fd){int flags=fcntl(fd,F_GETFL);return flags>=0&&fcntl(fd,F_SETFL,flags|O_NONBLOCK)==0;}
// Never change the process-wide SIGPIPE disposition or consume an older pending signal.
ssize_t write_pipe(int fd,const char* data,size_t size){
    sigset_t blocked,prior,pending;sigemptyset(&blocked);sigaddset(&blocked,SIGPIPE);
    int error=pthread_sigmask(SIG_BLOCK,&blocked,&prior);if(error){errno=error;return -1;}
    bool before=sigpending(&pending)==0&&sigismember(&pending,SIGPIPE)==1;
    ssize_t written=write(fd,data,size);int saved=errno;
    if(written<0&&saved==EPIPE&&!before){timespec immediate{};while(sigtimedwait(&blocked,nullptr,&immediate)<0&&errno==EINTR){}}
    int restored=pthread_sigmask(SIG_SETMASK,&prior,nullptr);if(restored&&written<0)saved=restored;
    errno=saved;return written;
}
struct Input {jlong token=0;unsigned int kind=0;std::string data;size_t written=0;bool active=false;};
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_pocketengine_features_local_runtime_NativeProcessSupervisor_pipeProtocol(JNIEnv*,jclass){return 1;}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_pocketengine_features_local_runtime_NativeProcessSupervisor_runPipes(
        JNIEnv* env,jclass,jobjectArray command,jobjectArray environment,jbyteArray directory,
        jlong timeout_ms,jlong grace_ms,jobject observer){
    auto argv=strings(env,command),env_values=strings(env,environment);auto argv_ptr=pointers(argv),env_ptr=pointers(env_values);std::string cwd=bytes(env,directory);if(env->ExceptionCheck())return nullptr;
    jclass type=env->GetObjectClass(observer);
    jmethodID output=env->GetMethodID(type,"output","(I[B)Z"),cancelled=env->GetMethodID(type,"cancelled","()Z"),started=env->GetMethodID(type,"started","()Z"),next=env->GetMethodID(type,"nextStdinCommand","()[B"),input_current=env->GetMethodID(type,"stdinCommandCurrent","(J)Z"),ack=env->GetMethodID(type,"stdinCommandResult","(JIIZ)V");if(env->ExceptionCheck())return nullptr;
    int input[2]={-1,-1},out[2]={-1,-1},err[2]={-1,-1},launch[2]={-1,-1},launch_error=0;pid_t pid=-1;
    bool entry_cancelled=env->CallBooleanMethod(observer,cancelled)==JNI_TRUE;
    if(env->ExceptionCheck()){env->ExceptionClear();entry_cancelled=true;}if(entry_cancelled)launch_error=ECANCELED;
    if(!launch_error&&(!make_pipe(input)||!make_pipe(out)||!make_pipe(err)||!make_pipe(launch)))launch_error=errno;
    if(!launch_error){pid=fork();if(pid<0)launch_error=errno;else if(pid==0){
        close(input[1]);close(out[0]);close(err[0]);close(launch[0]);
        if(setsid()<0)child_error(launch[1],errno);
        sigset_t empty;sigemptyset(&empty);sigprocmask(SIG_SETMASK,&empty,nullptr);
        struct sigaction normal{};normal.sa_handler=SIG_DFL;sigemptyset(&normal.sa_mask);for(int signal:{SIGTERM,SIGINT,SIGHUP,SIGQUIT,SIGPIPE})sigaction(signal,&normal,nullptr);
        if(dup2(input[0],STDIN_FILENO)<0||dup2(out[1],STDOUT_FILENO)<0||dup2(err[1],STDERR_FILENO)<0)child_error(launch[1],errno);
        close(input[0]);close(out[1]);close(err[1]);
        if(chdir(cwd.c_str())<0)child_error(launch[1],errno);
        execve(argv_ptr[0],argv_ptr.data(),env_ptr.data());child_error(launch[1],errno);
    }}
    close_fd(input[0]);close_fd(out[1]);close_fd(err[1]);close_fd(launch[1]);
    bool stopped=entry_cancelled,timed_out=false,io_complete=true,leader_done=false,reaped=false,start_notified=false,escaped=false,session_absent=pid<=0,owns_leader=pid>0;
    long long begin=clock_ms(),stop_at=0,leader_at=0;int status=0;Tracked tracked;Input pending;
    auto request_stop=[&](){if(!stopped){stopped=true;stop_at=clock_ms();}close_fd(input[1]);};
    auto acknowledge=[&](int error,bool effects){env->CallVoidMethod(observer,ack,pending.token,static_cast<jint>(pending.written),error,effects?JNI_TRUE:JNI_FALSE);pending.active=false;if(env->ExceptionCheck()){env->ExceptionClear();io_complete=false;request_stop();}};
    if(pid>0){
        if(!set_nonblocking(input[1])||!set_nonblocking(out[0])||!set_nonblocking(err[0])||!set_nonblocking(launch[0])){io_complete=false;request_stop();}
        while(true){
            long long time=clock_ms();Scan current=scan(pid,tracked,escaped);
            bool wants_stop=env->CallBooleanMethod(observer,cancelled)==JNI_TRUE;
            if(env->ExceptionCheck()){env->ExceptionClear();wants_stop=true;io_complete=false;}
            if(time-begin>=timeout_ms){timed_out=true;wants_stop=true;}
            if(!current.complete||escaped)wants_stop=true;
            // An external reaper/lost ownership is not permission to signal a reused numeric PID.
            if(!leader_done){siginfo_t info{};int waited=waitid(P_PID,static_cast<id_t>(pid),&info,WEXITED|WNOHANG|WNOWAIT);
                if(waited==0&&info.si_pid==pid){leader_done=true;leader_at=time;}
                else if(waited<0&&errno!=EINTR){owns_leader=false;io_complete=false;request_stop();break;}}
            if(wants_stop)request_stop();
            if(stopped&&owns_leader)signal_members(pid,current,tracked,time-stop_at>=grace_ms?SIGKILL:SIGTERM);
            if(leader_done&&owns_leader){close_fd(input[1]);signal_members(pid,current,tracked,time-leader_at>=grace_ms?SIGKILL:SIGTERM);}
            if(start_notified&&!stopped&&!leader_done&&!pending.active&&input[1]>=0){
                auto packet=static_cast<jbyteArray>(env->CallObjectMethod(observer,next));
                if(env->ExceptionCheck()){env->ExceptionClear();io_complete=false;request_stop();}
                else if(packet){std::string value=bytes(env,packet);env->DeleteLocalRef(packet);
                    if(env->ExceptionCheck()){env->ExceptionClear();io_complete=false;request_stop();}
                    else if(value.size()<16||value.size()>16+131072||u32(value,12)!=value.size()-16){io_complete=false;request_stop();}
                    else{pending=Input{};pending.kind=u32(value,0);pending.token=static_cast<jlong>(u64(value,4));pending.data=value.substr(16);pending.active=true;
                        if(pending.token<1||pending.kind<1||pending.kind>2||(pending.kind==1&&pending.data.empty())||(pending.kind==2&&!pending.data.empty())){io_complete=false;acknowledge(EINVAL,false);request_stop();}
                    }
                }
            }
            pollfd descriptors[4]={{out[0],POLLIN,0},{err[0],POLLIN,0},{launch[0],POLLIN,0},{input[1],static_cast<short>(pending.active?POLLOUT:0),0}};
            int polled=poll(descriptors,4,25);if(polled<0&&errno!=EINTR){io_complete=false;request_stop();}
            if(pending.active&&!stopped&&!leader_done){
                bool valid=env->CallBooleanMethod(observer,input_current,pending.token)==JNI_TRUE;
                if(env->ExceptionCheck()){env->ExceptionClear();valid=false;io_complete=false;}
                if(!valid){acknowledge(ECANCELED,pending.written>0);request_stop();}
                else if(input[1]<0){acknowledge(EPIPE,pending.written>0);request_stop();}
                else if(pending.kind==2){close_fd(input[1]);acknowledge(0,true);}
                else {ssize_t n=write_pipe(input[1],pending.data.data()+pending.written,pending.data.size()-pending.written);
                    if(n>0){pending.written+=static_cast<size_t>(n);if(pending.written==pending.data.size())acknowledge(0,true);}
                    else if(n<0&&errno!=EAGAIN&&errno!=EWOULDBLOCK&&errno!=EINTR){int error=errno;acknowledge(error,pending.written>0);request_stop();}
                }
            }
            for(int source=0;source<3;source++){
                int& fd=source==0?out[0]:source==1?err[0]:launch[0];if(fd<0)continue;
                for(int batch=0;batch<16;batch++){
                    char buffer[8192];ssize_t n=read(fd,buffer,sizeof(buffer));
                    if(n==0){close_fd(fd);
                        if(source==2&&!launch_error&&!leader_done&&!stopped&&!start_notified){
                            Process leader;if(!process(pid,leader)||leader.session!=pid){io_complete=false;request_stop();}
                            else if(leader.state!='Z'&&leader.state!='X'){tracked.emplace(pid,leader.born);bool accepted=env->CallBooleanMethod(observer,started)==JNI_TRUE;if(env->ExceptionCheck()){env->ExceptionClear();accepted=false;}if(!accepted){io_complete=false;request_stop();}else start_notified=true;}
                        }break;}
                    if(n<0){if(errno==EINTR)continue;if(errno!=EAGAIN&&errno!=EWOULDBLOCK){io_complete=false;close_fd(fd);request_stop();}break;}
                    if(source==2){if(n==static_cast<ssize_t>(sizeof(int)))std::memcpy(&launch_error,buffer,sizeof(int));else{launch_error=EIO;io_complete=false;request_stop();}continue;}
                    if(io_complete){jbyteArray data=env->NewByteArray(static_cast<jsize>(n));bool accepted=false;
                        if(data){env->SetByteArrayRegion(data,0,static_cast<jsize>(n),reinterpret_cast<jbyte*>(buffer));if(!env->ExceptionCheck())accepted=env->CallBooleanMethod(observer,output,source+1,data)==JNI_TRUE;env->DeleteLocalRef(data);}
                        if(env->ExceptionCheck()){env->ExceptionClear();}
                        if(!accepted){io_complete=false;request_stop();}
                    }
                }
            }
            if(leader_done&&out[0]<0&&err[0]<0&&launch[0]<0&&current.complete&&!members(current,pid,tracked))break;
            if((leader_done&&time-leader_at>grace_ms+2000)||(stopped&&time-stop_at>grace_ms+3000)){if(out[0]>=0||err[0]>=0||launch[0]>=0)io_complete=false;break;}
        }
        if(owns_leader){Scan current=scan(pid,tracked,escaped);signal_members(pid,current,tracked,SIGKILL);
            pid_t waited;do{waited=waitpid(pid,&status,WNOHANG);}while(waited<0&&errno==EINTR);reaped=waited==pid;if(!reaped)io_complete=false;
            Scan final_scan=scan(pid,tracked,escaped);session_absent=reaped&&final_scan.complete&&!escaped&&!members(final_scan,pid,tracked);
        }
        if(pending.active)acknowledge(timed_out?ETIMEDOUT:EPIPE,pending.written>0);
    }
    close_fd(input[0]);close_fd(input[1]);close_fd(out[0]);close_fd(out[1]);close_fd(err[0]);close_fd(err[1]);close_fd(launch[0]);close_fd(launch[1]);
    bool group_absent=pid<=0||(reaped&&kill(-pid,0)<0&&errno==ESRCH);
    const jlong result[8]={reaped&&WIFEXITED(status)?WEXITSTATUS(status):-1,reaped&&WIFSIGNALED(status)?WTERMSIG(status):0,launch_error,stopped?1:0,timed_out?1:0,group_absent?1:0,io_complete?1:0,session_absent?1:0};
    jlongArray answer=env->NewLongArray(8);if(answer)env->SetLongArrayRegion(answer,0,8,result);env->DeleteLocalRef(type);return answer;
}
