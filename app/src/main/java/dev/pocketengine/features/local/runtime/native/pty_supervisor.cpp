#include <jni.h>
#include "child_owner.h"
// child_owner.h SHA256: c394fb6a7464f34ab8ab148964c2555a3bafbefcd5d1b45496ef0c77c88f4f8a
#include <cerrno>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>
#include <map>
#include <sstream>
#include <string>
#include <vector>

// PTY IO with a private single-threaded owner of the original child/session.
// No task, grant, scheduler, recoverable PID or UID sandbox.
namespace {
long long clock_ms(){timespec time{};clock_gettime(CLOCK_MONOTONIC,&time);return static_cast<long long>(time.tv_sec)*1000+time.tv_nsec/1000000;}
void close_fd(int& fd){if(fd>=0){close(fd);fd=-1;}}
std::string bytes(JNIEnv* env,jbyteArray input){if(!input)return {};std::string value(static_cast<size_t>(env->GetArrayLength(input)),'\0');env->GetByteArrayRegion(input,0,static_cast<jsize>(value.size()),reinterpret_cast<jbyte*>(value.data()));return value;}
std::vector<std::string> strings(JNIEnv* env,jobjectArray input){std::vector<std::string> out;for(jsize i=0;i<env->GetArrayLength(input);i++){auto value=static_cast<jbyteArray>(env->GetObjectArrayElement(input,i));out.push_back(bytes(env,value));env->DeleteLocalRef(value);}return out;}
std::vector<char*> pointers(std::vector<std::string>& values){std::vector<char*> result;for(auto& value:values)result.push_back(value.data());result.push_back(nullptr);return result;}
void child_error(int fd,int error){const char* data=reinterpret_cast<const char*>(&error);size_t at=0;while(at<sizeof(error)){ssize_t n=write(fd,data+at,sizeof(error)-at);if(n>0)at+=static_cast<size_t>(n);else if(errno!=EINTR)break;}_exit(127);}
void nonblocking(int fd){if(fd>=0)fcntl(fd,F_SETFL,fcntl(fd,F_GETFL)|O_NONBLOCK);}
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
// The true parent owns the original group. A different group in that same
// session needs its own stable member fd. Escaped numeric identities are never
// promoted to signal handles; their final cleanup remains explicitly unknown.
bool signal_members(pid_t leader,const Scan& current,const Tracked& tracked,int signal,pocketprocess::ChildOwner& owner){
#if defined(__NR_pidfd_open) && defined(__NR_pidfd_send_signal)
    bool capability_checked=false,available=false;
    for(const auto& p:current.processes){
        auto known=tracked.find(p.pid);
        if(p.pid==leader||p.session!=leader||p.group==leader||known==tracked.end()||known->second!=p.born)continue;
        if(!capability_checked){available=pocketprocess::pidfd_signals_available();capability_checked=true;}
        if(!available)continue; // Never substitute a numeric signal on old kernels/API 30.
        int fd=static_cast<int>(syscall(__NR_pidfd_open,p.pid,0));if(fd<0)continue;
        // Bind first, then read membership, then verify that the fd's target has
        // not been reaped. The original session must still have its owned leader.
        Process actual;bool original=pocketprocess::pidfd_not_reaped(fd)&&process(p.pid,actual)
                &&actual.born==known->second&&actual.session==leader&&pocketprocess::pidfd_not_reaped(fd);
        if(original){bool exited=false;if(!owner.observe(exited)){close(fd);return false;}
            if(syscall(__NR_pidfd_send_signal,fd,signal,nullptr,0)<0&&errno!=ESRCH){close(fd);return false;}}
        close(fd);
    }
#else
    (void)leader;(void)current;(void)tracked;(void)signal;(void)owner;
#endif
    return true;
}
unsigned int u32(const std::string& value,size_t at){unsigned int n=0;for(size_t i=0;i<4;i++)n=(n<<8)|static_cast<unsigned char>(value[at+i]);return n;}
unsigned long long u64(const std::string& value,size_t at){unsigned long long n=0;for(size_t i=0;i<8;i++)n=(n<<8)|static_cast<unsigned char>(value[at+i]);return n;}
struct Input {jlong token=0;unsigned int kind=0,rows=0,columns=0;std::string data;size_t written=0;bool active=false;};
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_pocketengine_features_local_runtime_NativeProcessSupervisor_ptyProtocol(JNIEnv*,jclass){return 3;}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_pocketengine_features_local_runtime_NativeProcessSupervisor_runPty(
        JNIEnv* env,jclass,jobjectArray command,jobjectArray environment,jbyteArray directory,
        jlong timeout_ms,jlong grace_ms,jint rows,jint columns,jobject observer){
    auto argv=strings(env,command),env_values=strings(env,environment);auto argv_ptr=pointers(argv),env_ptr=pointers(env_values);std::string cwd=bytes(env,directory);if(env->ExceptionCheck())return nullptr;
    jclass type=env->GetObjectClass(observer);jmethodID output=env->GetMethodID(type,"output","(I[B)Z"),cancelled=env->GetMethodID(type,"cancelled","()Z"),started=env->GetMethodID(type,"started","()Z"),identity=env->GetMethodID(type,"ptyIdentity","(JJJ)V"),next=env->GetMethodID(type,"nextTerminalCommand","()[B"),input_current=env->GetMethodID(type,"terminalCommandCurrent","(J)Z"),ack=env->GetMethodID(type,"terminalCommandResult","(JIIIIZ)V");if(env->ExceptionCheck())return nullptr;
    int master=-1,slave=-1,launch[2]={-1,-1},launch_error=0;pid_t pid=-1;pocketprocess::ChildOwner owner;bool entry_cancelled=env->CallBooleanMethod(observer,cancelled)==JNI_TRUE;
    if(env->ExceptionCheck()){env->ExceptionClear();entry_cancelled=true;}if(entry_cancelled)launch_error=ECANCELED;
    if(!launch_error){master=posix_openpt(O_RDWR|O_NOCTTY|O_CLOEXEC);if(master<0)launch_error=errno;}
    if(!launch_error&&(grantpt(master)<0||unlockpt(master)<0))launch_error=errno;
    char slave_name[128];if(!launch_error){int error=ptsname_r(master,slave_name,sizeof(slave_name));if(error)launch_error=error;}
    if(!launch_error){slave=open(slave_name,O_RDWR|O_NOCTTY|O_CLOEXEC);if(slave<0)launch_error=errno;}
    if(!launch_error){winsize size{};size.ws_row=static_cast<unsigned short>(rows);size.ws_col=static_cast<unsigned short>(columns);if(ioctl(slave,TIOCSWINSZ,&size)<0)launch_error=errno;}
    if(!launch_error&&pipe2(launch,O_CLOEXEC)<0)launch_error=errno;
    if(!launch_error){const int transport[]={master,slave,launch[0],launch[1]};pid=owner.spawn(transport,4,grace_ms,launch_error);if(pid==0){
        close(master);close(launch[0]);if(setsid()<0||ioctl(slave,TIOCSCTTY,0)<0)child_error(launch[1],errno);
        sigset_t empty;sigemptyset(&empty);sigprocmask(SIG_SETMASK,&empty,nullptr);struct sigaction normal{};normal.sa_handler=SIG_DFL;sigemptyset(&normal.sa_mask);for(int signal:{SIGTERM,SIGINT,SIGHUP,SIGQUIT,SIGPIPE,SIGTSTP,SIGTTIN,SIGTTOU})sigaction(signal,&normal,nullptr);
        if(dup2(slave,STDIN_FILENO)<0||dup2(slave,STDOUT_FILENO)<0||dup2(slave,STDERR_FILENO)<0){child_error(launch[1],errno);}
        if(slave>STDERR_FILENO)close(slave);
        if(chdir(cwd.c_str())<0)child_error(launch[1],errno);
        execve(argv_ptr[0],argv_ptr.data(),env_ptr.data());child_error(launch[1],errno);
    }}
    close_fd(slave);close_fd(launch[1]);nonblocking(master);nonblocking(launch[0]);
    bool stopped=entry_cancelled,timed_out=false,io_complete=pid>0||owner.certain_no_child(),leader_done=false,reaped=false,start_notified=false,escaped=false,session_absent=pid<=0&&owner.certain_no_child(),owns_leader=pid>0;long long begin=clock_ms(),stop_at=0,leader_at=0;int status=0;unsigned long long leader_born=0;Tracked tracked;Input pending;
    auto request_stop=[&](){if(!stopped){stopped=true;stop_at=clock_ms();}};
    auto acknowledge=[&](int error,int actual_rows,int actual_columns,bool effects){env->CallVoidMethod(observer,ack,pending.token,static_cast<jint>(pending.written),error,actual_rows,actual_columns,effects?JNI_TRUE:JNI_FALSE);pending.active=false;if(env->ExceptionCheck()){env->ExceptionClear();io_complete=false;request_stop();}};
    auto observe_leader=[&](){
        if(!owns_leader)return false;
        bool exited=false;
        Process actual;
        if(!owner.observe(exited)||!process(pid,actual)||actual.parent!=owner.parent()||actual.born!=leader_born){owns_leader=false;io_complete=false;request_stop();return false;}
        if(exited&&!leader_done){leader_done=true;leader_at=clock_ms();}
        return true;
    };
    auto signal_owned=[&](const Scan& current,int signal){
        if(!observe_leader())return false;
        // The signal is executed by the child's single-threaded true parent.
        // A last successful App-side observation never authorizes numeric kill.
        if(!owner.signal(signal)){owns_leader=false;io_complete=false;request_stop();return false;}
        if(!signal_members(pid,current,tracked,signal,owner)){owns_leader=false;io_complete=false;request_stop();return false;}return true;
    };
    if(pid>0){
        Process original;if(process(pid,original)&&original.parent==owner.parent())leader_born=original.born;else{owns_leader=false;io_complete=false;request_stop();}
        while(owns_leader){
            long long time=clock_ms();Scan current=scan(pid,tracked,escaped);
            bool wants_stop=env->CallBooleanMethod(observer,cancelled)==JNI_TRUE;if(env->ExceptionCheck()){env->ExceptionClear();wants_stop=true;io_complete=false;}if(time-begin>=timeout_ms){timed_out=true;wants_stop=true;}if(!current.complete||escaped)wants_stop=true;if(wants_stop)request_stop();
            if(!observe_leader())break;
            if(stopped&&!signal_owned(current,time-stop_at>=grace_ms?SIGKILL:SIGTERM))break;
            if(leader_done&&!signal_owned(current,time-leader_at>=grace_ms?SIGKILL:SIGTERM))break;
            if(start_notified&&!stopped&&!leader_done&&!pending.active){
                auto packet=static_cast<jbyteArray>(env->CallObjectMethod(observer,next));if(env->ExceptionCheck()){env->ExceptionClear();io_complete=false;request_stop();}
                else if(packet){std::string value=bytes(env,packet);env->DeleteLocalRef(packet);if(env->ExceptionCheck()){env->ExceptionClear();io_complete=false;request_stop();}
                    else if(value.size()<24||value.size()>24+4096||u32(value,20)!=value.size()-24){io_complete=false;request_stop();}
                    else{pending=Input{};pending.kind=u32(value,0);pending.token=static_cast<jlong>(u64(value,4));pending.rows=u32(value,12);pending.columns=u32(value,16);pending.data=value.substr(24);pending.active=true;
                        if(pending.token<1||pending.kind<1||pending.kind>3||(pending.kind==1&&(pending.data.empty()||pending.rows||pending.columns))||(pending.kind!=1&&!pending.data.empty())||(pending.kind==2&&(pending.rows||pending.columns))||(pending.kind==3&&(pending.rows<2||pending.rows>200||pending.columns<4||pending.columns>400))){io_complete=false;request_stop();acknowledge(EINVAL,0,0,false);}
                        else if(env->CallBooleanMethod(observer,input_current,pending.token)!=JNI_TRUE){if(env->ExceptionCheck()){env->ExceptionClear();io_complete=false;}acknowledge(ECANCELED,0,0,false);request_stop();}
                        else if(pending.kind==3){winsize desired{},actual{};desired.ws_row=static_cast<unsigned short>(pending.rows);desired.ws_col=static_cast<unsigned short>(pending.columns);if(ioctl(master,TIOCSWINSZ,&desired)<0)acknowledge(errno,0,0,false);else if(ioctl(master,TIOCGWINSZ,&actual)<0)acknowledge(errno,0,0,true);else acknowledge(0,actual.ws_row,actual.ws_col,true);}
                        else if(pending.kind==2){termios mode{};if(tcgetattr(master,&mode)<0)acknowledge(errno,0,0,false);else if(!(mode.c_lflag&ICANON)||mode.c_cc[VEOF]==_POSIX_VDISABLE)acknowledge(EOPNOTSUPP,0,0,false);else pending.data.assign(1,static_cast<char>(mode.c_cc[VEOF]));}
                    }
                }
            }
            pollfd descriptors[2]={{master,static_cast<short>(POLLIN|(pending.active?POLLOUT:0)),0},{launch[0],POLLIN,0}};int polled=poll(descriptors,2,50);if(polled<0&&errno!=EINTR){io_complete=false;request_stop();}
            if(pending.active&&!stopped&&!leader_done){if(env->CallBooleanMethod(observer,input_current,pending.token)!=JNI_TRUE){if(env->ExceptionCheck()){env->ExceptionClear();io_complete=false;}acknowledge(ECANCELED,0,0,pending.written>0);request_stop();}else if(master<0)acknowledge(EBADF,0,0,false);else{ssize_t n=write(master,pending.data.data()+pending.written,pending.data.size()-pending.written);if(n>0){pending.written+=static_cast<size_t>(n);if(pending.written==pending.data.size())acknowledge(0,0,0,true);}else if(n<0&&errno!=EAGAIN&&errno!=EWOULDBLOCK&&errno!=EINTR)acknowledge(errno,0,0,pending.written>0);}}
            for(int source=0;source<2;source++){
                int& fd=source==0?master:launch[0];if(fd<0)continue;
                for(int batch=0;batch<16;batch++){
                    char buffer[8192];ssize_t n=read(fd,buffer,sizeof(buffer));
                    if(n==0||(n<0&&source==0&&errno==EIO)){close_fd(fd);if(source==1&&!launch_error&&!leader_done&&!stopped&&!start_notified){
                        Process leader;if(!observe_leader()||!process(pid,leader)||leader.born!=leader_born||leader.parent!=owner.parent()||leader.session!=pid){io_complete=false;request_stop();}
                        else if(leader.state!='Z'&&leader.state!='X'){tracked.emplace(pid,leader.born);env->CallVoidMethod(observer,identity,static_cast<jlong>(pid),static_cast<jlong>(leader.born),static_cast<jlong>(leader.session));bool accepted=false;if(!env->ExceptionCheck())accepted=env->CallBooleanMethod(observer,started)==JNI_TRUE;if(env->ExceptionCheck())env->ExceptionClear();if(!accepted){io_complete=false;request_stop();}else start_notified=true;}
                    }break;}
                    if(n<0){if(errno==EINTR)continue;if(errno!=EAGAIN&&errno!=EWOULDBLOCK){io_complete=false;close_fd(fd);request_stop();}break;}
                    if(source==1){if(n==static_cast<ssize_t>(sizeof(int)))std::memcpy(&launch_error,buffer,sizeof(int));else{launch_error=EIO;io_complete=false;request_stop();}continue;}
                    if(io_complete){jbyteArray data=env->NewByteArray(static_cast<jsize>(n));bool accepted=false;if(data){env->SetByteArrayRegion(data,0,static_cast<jsize>(n),reinterpret_cast<jbyte*>(buffer));if(!env->ExceptionCheck())accepted=env->CallBooleanMethod(observer,output,1,data)==JNI_TRUE;env->DeleteLocalRef(data);}if(env->ExceptionCheck())env->ExceptionClear();if(!accepted){io_complete=false;request_stop();}}
                }
            }
            if(!owns_leader)break;
            if(leader_done&&master<0&&launch[0]<0&&current.complete&&!members(current,pid,tracked))break;
            if((leader_done&&time-leader_at>grace_ms+2000)||(stopped&&time-stop_at>grace_ms+3000)){if(master>=0||launch[0]>=0)io_complete=false;break;}
        }
        if(owns_leader){Scan current=scan(pid,tracked,escaped);
            if(signal_owned(current,SIGKILL)){
                pid_t waited=owner.reap(status);reaped=waited==pid;
                if(!reaped)io_complete=false;
                Scan final_scan=scan(pid,tracked,escaped);session_absent=reaped&&final_scan.complete&&!escaped&&!members(final_scan,pid,tracked);
            }
        }
        if(pending.active)acknowledge(EPIPE,0,0,pending.written>0);
    }
    close_fd(master);close_fd(slave);close_fd(launch[0]);close_fd(launch[1]);bool owner_finished=owner.finish();if(!owner_finished){io_complete=false;session_absent=false;}
    bool group_absent=owner_finished&&((pid<=0&&owner.certain_no_child())||(reaped&&kill(-pid,0)<0&&errno==ESRCH));
    const jlong result[8]={reaped&&WIFEXITED(status)?WEXITSTATUS(status):-1,reaped&&WIFSIGNALED(status)?WTERMSIG(status):0,launch_error,stopped?1:0,timed_out?1:0,group_absent?1:0,io_complete?1:0,session_absent?1:0};
    jlongArray answer=env->NewLongArray(8);if(answer)env->SetLongArrayRegion(answer,0,8,result);env->DeleteLocalRef(type);return answer;
}
