#include <jni.h>
#include <cerrno>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <string>
#include <vector>

// Project-owned implementation using POSIX directory capabilities. No upstream application code.
namespace {
struct Fd {
    int value;
    explicit Fd(int value=-1):value(value){}
    ~Fd(){if(value>=0)close(value);}
    Fd(const Fd&)=delete;Fd& operator=(const Fd&)=delete;
    void take(int next){if(value>=0)close(value);value=next;}
};
std::string string_bytes(JNIEnv* env,jbyteArray input){
    if(!input)return {};
    jsize size=env->GetArrayLength(input);std::string value(static_cast<size_t>(size),'\0');
    env->GetByteArrayRegion(input,0,size,reinterpret_cast<jbyte*>(value.data()));return value;
}
void refuse(JNIEnv* env,const char* code){env->ThrowNew(env->FindClass("java/lang/SecurityException"),code);}
bool components(const std::string& path,std::vector<std::string>& result,bool absolute){
    if(path.empty()||path.find('\0')!=std::string::npos||(path.front()=='/')!=absolute)return false;
    size_t begin=absolute?1:0;
    while(begin<path.size()){
        size_t end=path.find('/',begin);if(end==std::string::npos)end=path.size();
        auto part=path.substr(begin,end-begin);if(part.empty()||part=="."||part=="..")return false;result.push_back(part);begin=end+1;
    }return !result.empty()&&path.back()!='/';
}
std::string fd_path(int fd){
    char name[80];snprintf(name,sizeof(name),"/proc/self/fd/%d",fd);char value[8192];ssize_t count=readlink(name,value,sizeof(value));
    return count>0&&count<static_cast<ssize_t>(sizeof(value))?std::string(value,static_cast<size_t>(count)):std::string();
}
bool same(const struct stat& a,const struct stat& b){
    return S_ISREG(b.st_mode)&&b.st_nlink==1&&a.st_dev==b.st_dev&&a.st_ino==b.st_ino&&a.st_size==b.st_size
        &&a.st_mtim.tv_sec==b.st_mtim.tv_sec&&a.st_mtim.tv_nsec==b.st_mtim.tv_nsec
        &&a.st_ctim.tv_sec==b.st_ctim.tv_sec&&a.st_ctim.tv_nsec==b.st_ctim.tv_nsec;
}
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_pocketengine_features_local_runtime_WorkspaceFileAccess_capture(JNIEnv* env,jclass,jbyteArray root_bytes,jbyteArray relative_bytes,jint maximum){
    const auto root=string_bytes(env,root_bytes),relative=string_bytes(env,relative_bytes);if(env->ExceptionCheck())return nullptr;
    std::vector<std::string> root_parts,parts;
    if(maximum<1||maximum>64*1024*1024||!components(root,root_parts,true)||!components(relative,parts,false)){refuse(env,"workspace_file_path_invalid");return nullptr;}
    Fd directory(open("/",O_RDONLY|O_DIRECTORY|O_CLOEXEC));
    for(const auto& part:root_parts){int next=openat(directory.value,part.c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);if(next<0){refuse(env,"workspace_directory_changed_or_linked");return nullptr;}directory.take(next);}
    const auto root_path=fd_path(directory.value);if(root_path!=root){refuse(env,"workspace_root_identity_unavailable");return nullptr;}
    for(size_t i=0;i+1<parts.size();i++){int next=openat(directory.value,parts[i].c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);if(next<0){refuse(env,"workspace_directory_changed_or_linked");return nullptr;}directory.take(next);}
    Fd file(openat(directory.value,parts.back().c_str(),O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK));
    if(file.value<0){refuse(env,"workspace_file_open_refused");return nullptr;}
    struct stat before{},after{};
    const auto expected=root+"/"+relative;
    if(fstat(file.value,&before)<0||!S_ISREG(before.st_mode)||before.st_nlink!=1||before.st_size<0||before.st_size>maximum||fd_path(file.value)!=expected){refuse(env,"workspace_ordinary_single_link_file_required");return nullptr;}
    std::vector<jbyte> bytes(static_cast<size_t>(before.st_size));size_t read_bytes=0;
    while(read_bytes<bytes.size()){
        ssize_t count=pread(file.value,bytes.data()+read_bytes,bytes.size()-read_bytes,static_cast<off_t>(read_bytes));
        if(count<0&&errno==EINTR)continue;
        if(count<=0){refuse(env,"workspace_file_changed_during_capture");return nullptr;}read_bytes+=static_cast<size_t>(count);
    }
    if(fstat(file.value,&after)<0||!same(before,after)||fd_path(file.value)!=expected){refuse(env,"workspace_file_changed_during_capture");return nullptr;}
    jbyteArray result=env->NewByteArray(static_cast<jsize>(bytes.size()));if(result&&!bytes.empty())env->SetByteArrayRegion(result,0,static_cast<jsize>(bytes.size()),bytes.data());return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pocketengine_features_local_runtime_WorkspaceFileAccess_mutate(JNIEnv* env,jclass,jbyteArray root_bytes,jbyteArray relative_bytes,jbyteArray before_bytes,jbyteArray after_bytes,jbyteArray temporary_bytes){
    const auto root=string_bytes(env,root_bytes),relative=string_bytes(env,relative_bytes),before=string_bytes(env,before_bytes),after=string_bytes(env,after_bytes),temporary=string_bytes(env,temporary_bytes);
    if(env->ExceptionCheck())return;
    std::vector<std::string> root_parts,parts;
    if(!components(root,root_parts,true)||!components(relative,parts,false)||temporary.size()!=44||temporary.rfind("pending-",0)!=0||temporary.find('/')!=std::string::npos||before.size()>262144||after.size()>262144||(!before_bytes&&!after_bytes)){refuse(env,"workspace_patch_identity_invalid");return;}
    Fd directory(open("/",O_RDONLY|O_DIRECTORY|O_CLOEXEC));
    for(const auto& part:root_parts){int next=openat(directory.value,part.c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);if(next<0){refuse(env,"workspace_directory_changed_or_linked");return;}directory.take(next);}
    if(fd_path(directory.value)!=root){refuse(env,"workspace_root_identity_unavailable");return;}
    for(size_t i=0;i+1<parts.size();i++){
        int next=openat(directory.value,parts[i].c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
        if(next<0&&errno==ENOENT&&after_bytes){if(mkdirat(directory.value,parts[i].c_str(),0700)<0&&errno!=EEXIST){refuse(env,"workspace_patch_parent_unavailable");return;}next=openat(directory.value,parts[i].c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);}
        if(next<0){refuse(env,"workspace_directory_changed_or_linked");return;}directory.take(next);
    }
    const auto expected=root+"/"+relative;struct stat named{};
    if(!before_bytes){if(fstatat(directory.value,parts.back().c_str(),&named,AT_SYMLINK_NOFOLLOW)==0||errno!=ENOENT){refuse(env,"workspace_patch_expected_absent");return;}}
    else{
        Fd original(openat(directory.value,parts.back().c_str(),O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK));struct stat initial{},final{};
        if(original.value<0||fstat(original.value,&initial)<0||!S_ISREG(initial.st_mode)||initial.st_nlink!=1||initial.st_size!=static_cast<off_t>(before.size())||fd_path(original.value)!=expected){refuse(env,"workspace_patch_target_changed");return;}
        std::string observed(before.size(),'\0');size_t offset=0;
        while(offset<observed.size()){ssize_t count=pread(original.value,observed.data()+offset,observed.size()-offset,static_cast<off_t>(offset));if(count<0&&errno==EINTR)continue;if(count<=0){refuse(env,"workspace_patch_target_changed");return;}offset+=static_cast<size_t>(count);}
        if(observed!=before||fstat(original.value,&final)<0||!same(initial,final)||fstatat(directory.value,parts.back().c_str(),&named,AT_SYMLINK_NOFOLLOW)<0||initial.st_dev!=named.st_dev||initial.st_ino!=named.st_ino){refuse(env,"workspace_patch_target_changed");return;}
    }
    const auto parent=expected.substr(0,expected.rfind('/'));
    if(fd_path(directory.value)!=parent){refuse(env,"workspace_patch_parent_changed");return;}
    if(!after_bytes){if(unlinkat(directory.value,parts.back().c_str(),0)<0){refuse(env,"workspace_patch_delete_failed");return;}if(fsync(directory.value)<0||fd_path(directory.value)!=parent)env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"workspace_patch_commit_unknown");return;}
    Fd staged(openat(directory.value,temporary.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC|O_NOFOLLOW,0600));
    if(staged.value<0){refuse(env,"workspace_patch_stage_failed");return;}
    size_t offset=0;bool written=true;
    while(offset<after.size()){ssize_t count=write(staged.value,after.data()+offset,after.size()-offset);if(count<0&&errno==EINTR)continue;if(count<=0){written=false;break;}offset+=static_cast<size_t>(count);}
    if(!written||fsync(staged.value)<0||fd_path(directory.value)!=parent){unlinkat(directory.value,temporary.c_str(),0);refuse(env,"workspace_patch_stage_failed");return;}
    int result;
    if(before_bytes)result=renameat(directory.value,temporary.c_str(),directory.value,parts.back().c_str());
    else{result=linkat(directory.value,temporary.c_str(),directory.value,parts.back().c_str(),0);if(result==0)unlinkat(directory.value,temporary.c_str(),0);}
    if(result<0){unlinkat(directory.value,temporary.c_str(),0);refuse(env,"workspace_patch_commit_refused");return;}
    if(fsync(directory.value)<0||fd_path(directory.value)!=parent)env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"workspace_patch_commit_unknown");
}
