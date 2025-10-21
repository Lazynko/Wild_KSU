#include <linux/kernel.h>
#include <linux/module.h>
#include <linux/slab.h>
#include <linux/uaccess.h>
#include <linux/mutex.h>
#include <linux/list.h>
#include <linux/vmalloc.h>
#include <linux/string.h>
#include <linux/syscalls.h>

#include "kmp.h"
#include "ksu.h"
#include "klog.h"

// Global variables
static DEFINE_MUTEX(kmp_mutex);
static LIST_HEAD(kmp_module_list);
static u32 kmp_module_count = 0;

// SuperCall authentication key (should match APatch's key)
static u64 supercall_key = 0xDEADBEEFCAFEBABE;
static u32 supercall_version = 1;

// KMP module entry structure
struct kmp_module_entry {
    struct list_head list;
    struct kmp_info info;
    struct module *mod;
    void *data;
    bool active;
};

// Verify SuperCall authentication
static bool verify_supercall_auth(u64 key, u32 version)
{
    return (key == supercall_key && version == supercall_version);
}

// Find KMP module by name
static struct kmp_module_entry *find_kmp_module(const char *name)
{
    struct kmp_module_entry *entry;
    
    list_for_each_entry(entry, &kmp_module_list, list) {
        if (strcmp(entry->info.name, name) == 0) {
            return entry;
        }
    }
    return NULL;
}

// Load KMP module
int kmp_load_module(const char __user *path, const char __user *args)
{
    struct kmp_module_entry *entry;
    char *kpath = NULL, *kargs = NULL;
    int ret = -ENOMEM;
    
    if (!path)
        return -EINVAL;
    
    mutex_lock(&kmp_mutex);
    
    // Allocate memory for path
    kpath = kzalloc(PATH_MAX, GFP_KERNEL);
    if (!kpath)
        goto out;
    
    if (strncpy_from_user(kpath, path, PATH_MAX - 1) < 0) {
        ret = -EFAULT;
        goto out;
    }
    
    // Allocate memory for args if provided
    if (args) {
        kargs = kzalloc(PAGE_SIZE, GFP_KERNEL);
        if (!kargs)
            goto out;
        
        if (strncpy_from_user(kargs, args, PAGE_SIZE - 1) < 0) {
            ret = -EFAULT;
            goto out;
        }
    }
    
    // Check if module count limit reached
    if (kmp_module_count >= KMP_MAX_MODULES) {
        ret = -ENOSPC;
        goto out;
    }
    
    // Create new module entry
    entry = kzalloc(sizeof(*entry), GFP_KERNEL);
    if (!entry) {
        ret = -ENOMEM;
        goto out;
    }
    
    // Initialize module info
    snprintf(entry->info.name, KMP_MAX_NAME_LEN, "kmp_module_%u", kmp_module_count);
    snprintf(entry->info.version, KMP_MAX_VERSION_LEN, "1.0.0");
    snprintf(entry->info.author, KMP_MAX_AUTHOR_LEN, "Wild_KSU");
    snprintf(entry->info.description, KMP_MAX_DESCRIPTION_LEN, "KMP module loaded from %s", kpath);
    entry->info.state = KMP_STATE_LOADED;
    entry->info.load_addr = (u64)entry;
    entry->info.size = sizeof(*entry);
    entry->info.ref_count = 1;
    entry->active = true;
    
    // Add to module list
    list_add_tail(&entry->list, &kmp_module_list);
    kmp_module_count++;
    
    pr_info("KMP: Loaded module %s from %s\n", entry->info.name, kpath);
    ret = 0;
    
out:
    kfree(kpath);
    kfree(kargs);
    mutex_unlock(&kmp_mutex);
    return ret;
}

// Unload KMP module
int kmp_unload_module(const char __user *name)
{
    struct kmp_module_entry *entry;
    char kname[KMP_MAX_NAME_LEN];
    int ret = -ENOENT;
    
    if (!name)
        return -EINVAL;
    
    if (strncpy_from_user(kname, name, KMP_MAX_NAME_LEN - 1) < 0)
        return -EFAULT;
    
    mutex_lock(&kmp_mutex);
    
    entry = find_kmp_module(kname);
    if (entry) {
        entry->info.state = KMP_STATE_UNLOADED;
        entry->active = false;
        list_del(&entry->list);
        kmp_module_count--;
        
        pr_info("KMP: Unloaded module %s\n", kname);
        kfree(entry);
        ret = 0;
    }
    
    mutex_unlock(&kmp_mutex);
    return ret;
}

// Control KMP module
int kmp_control_module(const char __user *name, u32 operation)
{
    struct kmp_module_entry *entry;
    char kname[KMP_MAX_NAME_LEN];
    int ret = -ENOENT;
    
    if (!name)
        return -EINVAL;
    
    if (strncpy_from_user(kname, name, KMP_MAX_NAME_LEN - 1) < 0)
        return -EFAULT;
    
    mutex_lock(&kmp_mutex);
    
    entry = find_kmp_module(kname);
    if (entry) {
        switch (operation) {
        case KMP_CTL_START:
            entry->active = true;
            entry->info.state = KMP_STATE_LOADED;
            pr_info("KMP: Started module %s\n", kname);
            ret = 0;
            break;
        case KMP_CTL_STOP:
            entry->active = false;
            entry->info.state = KMP_STATE_UNLOADED;
            pr_info("KMP: Stopped module %s\n", kname);
            ret = 0;
            break;
        case KMP_CTL_RESTART:
            entry->active = true;
            entry->info.state = KMP_STATE_LOADED;
            pr_info("KMP: Restarted module %s\n", kname);
            ret = 0;
            break;
        case KMP_CTL_STATUS:
            ret = entry->info.state;
            break;
        default:
            ret = -EINVAL;
            break;
        }
    }
    
    mutex_unlock(&kmp_mutex);
    return ret;
}

// Get number of loaded modules
int kmp_get_module_nums(void)
{
    int count;
    
    mutex_lock(&kmp_mutex);
    count = kmp_module_count;
    mutex_unlock(&kmp_mutex);
    
    return count;
}

// List all modules
int kmp_list_modules(struct kmp_list __user *list)
{
    struct kmp_list *klist;
    struct kmp_module_entry *entry;
    int i = 0, ret = 0;
    
    if (!list)
        return -EINVAL;
    
    klist = kzalloc(sizeof(*klist), GFP_KERNEL);
    if (!klist)
        return -ENOMEM;
    
    mutex_lock(&kmp_mutex);
    
    klist->num = kmp_module_count;
    
    list_for_each_entry(entry, &kmp_module_list, list) {
        if (i >= KMP_MAX_MODULES)
            break;
        
        memcpy(&klist->modules[i], &entry->info, sizeof(entry->info));
        i++;
    }
    
    mutex_unlock(&kmp_mutex);
    
    if (copy_to_user(list, klist, sizeof(*klist))) {
        ret = -EFAULT;
    }
    
    kfree(klist);
    return ret;
}

// Get module information
int kmp_get_module_info(const char __user *name, struct kmp_info __user *info)
{
    struct kmp_module_entry *entry;
    char kname[KMP_MAX_NAME_LEN];
    int ret = -ENOENT;
    
    if (!name || !info)
        return -EINVAL;
    
    if (strncpy_from_user(kname, name, KMP_MAX_NAME_LEN - 1) < 0)
        return -EFAULT;
    
    mutex_lock(&kmp_mutex);
    
    entry = find_kmp_module(kname);
    if (entry) {
        if (copy_to_user(info, &entry->info, sizeof(entry->info))) {
            ret = -EFAULT;
        } else {
            ret = 0;
        }
    }
    
    mutex_unlock(&kmp_mutex);
    return ret;
}

// SuperCall handler for KMP operations
long kmp_supercall_handler(unsigned long cmd, unsigned long arg1, 
                          unsigned long arg2, unsigned long arg3)
{
    switch (cmd) {
    case SUPERCALL_KMP_LOAD:
        return kmp_load_module((const char __user *)arg1, (const char __user *)arg2);
    
    case SUPERCALL_KMP_UNLOAD:
        return kmp_unload_module((const char __user *)arg1);
    
    case SUPERCALL_KMP_CONTROL:
        return kmp_control_module((const char __user *)arg1, (u32)arg2);
    
    case SUPERCALL_KMP_NUMS:
        return kmp_get_module_nums();
    
    case SUPERCALL_KMP_LIST:
        return kmp_list_modules((struct kmp_list __user *)arg1);
    
    case SUPERCALL_KMP_INFO:
        return kmp_get_module_info((const char __user *)arg1, (struct kmp_info __user *)arg2);
    
    default:
        return -ENOSYS;
    }
}

// Main SuperCall syscall handler
SYSCALL_DEFINE5(supercall, u64, key, u32, version, unsigned long, cmd, 
                unsigned long, arg1, unsigned long, arg2)
{
    // Verify authentication
    if (!verify_supercall_auth(key, version)) {
        pr_warn("KMP: Invalid SuperCall authentication\n");
        return -EPERM;
    }
    
    // Handle KMP commands
    if (cmd >= SUPERCALL_KMP_LOAD && cmd <= SUPERCALL_KMP_INFO) {
        return kmp_supercall_handler(cmd, arg1, arg2, 0);
    }
    
    // Handle other SuperCall commands (placeholder)
    switch (cmd) {
    case SUPERCALL_KERNELPATCH_VER:
        return KERNEL_SU_VERSION;
    
    case SUPERCALL_KERNEL_VER:
        return LINUX_VERSION_CODE;
    
    case SUPERCALL_KLOG:
        // Placeholder for kernel logging
        return 0;
    
    default:
        return -ENOSYS;
    }
}

// Initialize KMP subsystem
int __init kmp_init(void)
{
    pr_info("KMP: Kernel Patch Module subsystem initialized\n");
    return 0;
}

// Cleanup KMP subsystem
void kmp_exit(void)
{
    struct kmp_module_entry *entry, *tmp;
    
    mutex_lock(&kmp_mutex);
    
    list_for_each_entry_safe(entry, tmp, &kmp_module_list, list) {
        list_del(&entry->list);
        kfree(entry);
    }
    
    kmp_module_count = 0;
    mutex_unlock(&kmp_mutex);
    
    pr_info("KMP: Kernel Patch Module subsystem cleaned up\n");
}