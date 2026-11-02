#pragma once
#include <cerrno>
#include <cstddef>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>

// This restricts new IP sockets in this process lineage. Same-UID files and local IPC
// remain reachable; it is deliberately not advertised as a complete sandbox.
inline int pocketworld_install_offline_filter(){
#if defined(__aarch64__)
    constexpr unsigned int expected_arch=AUDIT_ARCH_AARCH64;
#elif defined(__x86_64__)
    constexpr unsigned int expected_arch=AUDIT_ARCH_X86_64;
#else
    errno=ENOTSUP;return -1;
#endif
#if defined(__aarch64__) || defined(__x86_64__)
    constexpr unsigned int denied=SECCOMP_RET_ERRNO|EACCES;
    // Reject alternate-ABI syscalls, network sockets, io_uring and Binder transactions.
    // BINDER_WRITE_READ encodes a 48-byte binder_write_read on the 64-bit targets.
    struct sock_filter rules[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,arch)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,expected_arch,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
#if defined(__x86_64__)
        BPF_JUMP(BPF_JMP|BPF_JGE|BPF_K,0x40000000U,0,1),
        BPF_STMT(BPF_RET|BPF_K,denied),
#endif
#ifdef __NR_io_uring_setup
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_io_uring_setup,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|ENOSYS),
#endif
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_ioctl,0,4),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[1])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,0xc0306201U,0,1),
        BPF_STMT(BPF_RET|BPF_K,denied),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_socket,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,AF_UNIX,1,0),
        BPF_STMT(BPF_RET|BPF_K,denied),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program{static_cast<unsigned short>(sizeof(rules)/sizeof(rules[0])),rules};
    if(prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0)!=0)return -1;
    return prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program);
#endif
}
