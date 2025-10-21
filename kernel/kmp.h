#ifndef __KSU_H_KMP
#define __KSU_H_KMP

#include <linux/types.h>
#include <linux/module.h>

// SuperCall syscall number (using a custom syscall number)
#define __NR_supercall 45

// SuperCall commands for KPM operations
#define SUPERCALL_KMP_LOAD 0x1000
#define SUPERCALL_KMP_UNLOAD 0x1001
#define SUPERCALL_KMP_CONTROL 0x1002
#define SUPERCALL_KMP_NUMS 0x1003
#define SUPERCALL_KMP_LIST 0x1004
#define SUPERCALL_KMP_INFO 0x1005

// Other SuperCall commands
#define SUPERCALL_SU 0x1010
#define SUPERCALL_SU_GET_SAFEMODE 0x1011
#define SUPERCALL_SU_RESET_PATH 0x1012
#define SUPERCALL_KERNELPATCH_VER 0x1020
#define SUPERCALL_KERNEL_VER 0x1021
#define SUPERCALL_KLOG 0x1030

// KMP module states
#define KMP_STATE_UNKNOWN 0
#define KMP_STATE_LOADING 1
#define KMP_STATE_LOADED 2
#define KMP_STATE_UNLOADING 3
#define KMP_STATE_UNLOADED 4
#define KMP_STATE_ERROR 5

// KMP control operations
#define KMP_CTL_START 1
#define KMP_CTL_STOP 2
#define KMP_CTL_RESTART 3
#define KMP_CTL_STATUS 4

// Maximum values
#define KMP_MAX_NAME_LEN 256
#define KMP_MAX_VERSION_LEN 64
#define KMP_MAX_AUTHOR_LEN 128
#define KMP_MAX_DESCRIPTION_LEN 512
#define KMP_MAX_MODULES 64

// KMP module information structure
struct kmp_info {
    char name[KMP_MAX_NAME_LEN];
    char version[KMP_MAX_VERSION_LEN];
    char author[KMP_MAX_AUTHOR_LEN];
    char description[KMP_MAX_DESCRIPTION_LEN];
    u32 state;
    u64 load_addr;
    u32 size;
    u32 ref_count;
};

// KMP module list structure
struct kmp_list {
    u32 num;
    struct kmp_info modules[KMP_MAX_MODULES];
};

// SuperCall authentication key structure
struct supercall_key {
    u64 key;
    u32 version;
};

// Function declarations
long kmp_supercall_handler(unsigned long cmd, unsigned long arg1, 
                          unsigned long arg2, unsigned long arg3);
int kmp_load_module(const char __user *path, const char __user *args);
int kmp_unload_module(const char __user *name);
int kmp_control_module(const char __user *name, u32 operation);
int kmp_get_module_nums(void);
int kmp_list_modules(struct kmp_list __user *list);
int kmp_get_module_info(const char __user *name, struct kmp_info __user *info);

// SuperCall helper functions
static inline long sc_kmp_load(u64 key, u32 version, const char *path, const char *args)
{
    return syscall(__NR_supercall, key, version, SUPERCALL_KMP_LOAD, path, args);
}

static inline long sc_kmp_unload(u64 key, u32 version, const char *name)
{
    return syscall(__NR_supercall, key, version, SUPERCALL_KMP_UNLOAD, name);
}

static inline long sc_kmp_control(u64 key, u32 version, const char *name, u32 operation)
{
    return syscall(__NR_supercall, key, version, SUPERCALL_KMP_CONTROL, name, operation);
}

static inline long sc_kmp_nums(u64 key, u32 version)
{
    return syscall(__NR_supercall, key, version, SUPERCALL_KMP_NUMS);
}

static inline long sc_kmp_list(u64 key, u32 version, struct kmp_list *list)
{
    return syscall(__NR_supercall, key, version, SUPERCALL_KMP_LIST, list);
}

static inline long sc_kmp_info(u64 key, u32 version, const char *name, struct kmp_info *info)
{
    return syscall(__NR_supercall, key, version, SUPERCALL_KMP_INFO, name, info);
}

#endif /* __KSU_H_KMP */