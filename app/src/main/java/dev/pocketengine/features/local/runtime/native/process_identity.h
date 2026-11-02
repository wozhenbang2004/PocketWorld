#pragma once
#include <cerrno>
#include <cstdlib>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <sstream>
#include <string>
#include <vector>

// Native currentness only. A persisted PID/JSON is never enough to recover this authority.
struct PocketworldProcessIdentity {long pid=0,session=0;unsigned long long born=0;char state=0;};
inline bool pocketworld_process_identity(long pid,PocketworldProcessIdentity& result){
    if(pid<1||pid>2147483647)return false;
    // A host-mounted procfs from another PID namespace is not an identity authority here.
    char own[32];ssize_t own_size=readlink("/proc/self",own,sizeof(own)-1);if(own_size<1||own_size>=static_cast<ssize_t>(sizeof(own)-1))return false;own[own_size]='\0';
    char* own_end=nullptr;errno=0;long own_pid=strtol(own,&own_end,10);if(errno||!own_end||*own_end||own_pid!=getpid())return false;
    std::string directory="/proc/"+std::to_string(pid);struct stat owner{};
    if(stat(directory.c_str(),&owner)<0||owner.st_uid!=getuid())return false;
    int fd=open((directory+"/stat").c_str(),O_RDONLY|O_CLOEXEC);if(fd<0)return false;
    char bytes[4096];ssize_t size;do{size=read(fd,bytes,sizeof(bytes));}while(size<0&&errno==EINTR);close(fd);
    if(size<1||size==static_cast<ssize_t>(sizeof(bytes)))return false;
    std::string value(bytes,static_cast<size_t>(size));size_t end=value.rfind(')');if(end==std::string::npos)return false;
    std::istringstream input(value.substr(end+1));std::vector<std::string> fields;std::string part;while(input>>part)fields.push_back(part);
    if(fields.size()<20||fields[0].size()!=1)return false;
    char* tail=nullptr;errno=0;unsigned long long born=strtoull(fields[19].c_str(),&tail,10);if(errno||!tail||*tail||!born)return false;
    errno=0;unsigned long long session=strtoull(fields[3].c_str(),&tail,10);if(errno||!tail||*tail||!session||session>2147483647)return false;
    result.pid=pid;result.session=static_cast<long>(session);result.born=born;result.state=fields[0][0];return true;
}
inline bool pocketworld_same_owned_session(long leader,unsigned long long born,long session,long peer){
    PocketworldProcessIdentity before{},client{},after{};
    return leader>0&&session==leader&&pocketworld_process_identity(leader,before)&&before.born==born&&before.session==session&&before.state!='Z'
        &&pocketworld_process_identity(peer,client)&&client.session==session&&client.state!='Z'
        &&pocketworld_process_identity(leader,after)&&after.born==born&&after.session==session&&after.state!='Z';
}
