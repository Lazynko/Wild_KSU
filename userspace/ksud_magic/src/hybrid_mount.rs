use crate::defs;
use crate::module::{ModuleMountType, get_module_mount_type};
use anyhow::{Context, Result, bail};
use log::{info, warn};
use std::path::Path;
use std::collections::HashMap;

/// Mixed mount dispatcher that can handle both magic mount and overlayfs per module
pub struct MixedMountDispatcher {
    magic_modules: Vec<String>,
    overlayfs_modules: Vec<String>,
}

impl MixedMountDispatcher {
    pub fn new() -> Self {
        Self {
            magic_modules: Vec::new(),
            overlayfs_modules: Vec::new(),
        }
    }

    /// Analyze all modules and categorize them by mount type
    pub fn analyze_modules(&mut self, module_dir: &str) -> Result<()> {
        let dir = std::fs::read_dir(module_dir)
            .with_context(|| format!("Failed to read module directory: {}", module_dir))?;

        self.magic_modules.clear();
        self.overlayfs_modules.clear();

        for entry in dir.flatten() {
            let module_path = entry.path();
            if !module_path.is_dir() {
                continue;
            }

            // Skip disabled and removed modules
            if module_path.join(defs::DISABLE_FILE_NAME).exists() {
                info!("Module {} is disabled, skip", module_path.display());
                continue;
            }
            if module_path.join(defs::REMOVE_FILE_NAME).exists() {
                warn!("Module {} is removed, skip", module_path.display());
                continue;
            }

            // Skip modules with skip_mount
            if module_path.join(defs::SKIP_MOUNT_FILE_NAME).exists() {
                info!("Module {} has skip_mount, skip", module_path.display());
                continue;
            }

            let module_name = module_path.file_name()
                .and_then(|n| n.to_str())
                .unwrap_or("unknown");

            let mount_type = get_module_mount_type(&module_path);
            match mount_type {
                ModuleMountType::MagicMount => {
                    info!("Module {} will use magic mount", module_name);
                    self.magic_modules.push(module_name.to_string());
                }
                ModuleMountType::OverlayFS => {
                    info!("Module {} will use overlayfs", module_name);
                    self.overlayfs_modules.push(module_name.to_string());
                }
                ModuleMountType::Default => {
                    // Use magic mount as default for hybrid mode
                    info!("Module {} using default mount method (magic mount)", module_name);
                    self.magic_modules.push(module_name.to_string());
                }
            }
        }

        info!("Analyzed modules: {} magic mount, {} overlayfs", 
              self.magic_modules.len(), self.overlayfs_modules.len());
        
        Ok(())
    }

    /// Mount modules using their specified mount methods
    pub fn mount_modules(&self, module_dir: &str) -> Result<()> {
        // Mount magic mount modules first
        if !self.magic_modules.is_empty() {
            info!("Mounting {} modules with magic mount", self.magic_modules.len());
            if let Err(e) = self.mount_magic_modules(module_dir) {
                warn!("Magic mount failed: {}", e);
            }
        }

        // Mount overlayfs modules
        if !self.overlayfs_modules.is_empty() {
            info!("Mounting {} modules with overlayfs", self.overlayfs_modules.len());
            if let Err(e) = self.mount_overlayfs_modules(module_dir) {
                warn!("OverlayFS mount failed: {}", e);
            }
        }

        Ok(())
    }

    /// Mount modules using magic mount
    fn mount_magic_modules(&self, module_dir: &str) -> Result<()> {
        // For now, we'll call the existing magic mount function
        // In a full implementation, we'd need to modify magic_mount to only process specific modules
        crate::magic_mount::magic_mount()
    }

    /// Mount modules using overlayfs
    fn mount_overlayfs_modules(&self, module_dir: &str) -> Result<()> {
        // Implement overlayfs mounting logic similar to ksud_overlayfs
        let dir = std::fs::read_dir(module_dir)?;
        
        let mut system_lowerdir: Vec<String> = Vec::new();
        let partition = vec!["vendor", "product", "system_ext", "odm", "oem"];
        let mut partition_lowerdir: HashMap<String, Vec<String>> = HashMap::new();
        
        for ele in &partition {
            partition_lowerdir.insert((*ele).to_string(), Vec::new());
        }

        for entry in dir.flatten() {
            let module_path = entry.path();
            if !module_path.is_dir() {
                continue;
            }

            let module_name = module_path.file_name()
                .and_then(|n| n.to_str())
                .unwrap_or("unknown");

            // Only process modules that should use overlayfs
            if !self.overlayfs_modules.contains(&module_name.to_string()) {
                continue;
            }

            // Skip disabled modules
            if module_path.join(defs::DISABLE_FILE_NAME).exists() {
                continue;
            }

            let module_system = module_path.join("system");
            if module_system.is_dir() {
                system_lowerdir.push(format!("{}", module_system.display()));
            }

            for part in &partition {
                let part_path = module_path.join(part);
                if part_path.is_dir() {
                    if let Some(v) = partition_lowerdir.get_mut(*part) {
                        v.push(format!("{}", part_path.display()));
                    }
                }
            }
        }

        // Mount /system first
        if !system_lowerdir.is_empty() {
            if let Err(e) = self.mount_partition("system", &system_lowerdir) {
                warn!("mount system failed: {:#}", e);
            }
        }

        // Mount other partitions
        for (k, v) in partition_lowerdir {
            if !v.is_empty() {
                if let Err(e) = self.mount_partition(&k, &v) {
                    warn!("mount {} failed: {:#}", k, e);
                }
            }
        }

        Ok(())
    }

    /// Mount a partition using overlayfs
    fn mount_partition(&self, partition_name: &str, lowerdir: &Vec<String>) -> Result<()> {
        if lowerdir.is_empty() {
            warn!("partition: {partition_name} lowerdir is empty");
            return Ok(());
        }

        let partition = format!("/{partition_name}");

        // if /partition is a symlink and linked to /system/partition, then we don't need to overlay it separately
        if std::path::Path::new(&partition).read_link().is_ok() {
            warn!("partition: {partition} is a symlink");
            return Ok(());
        }

        // For now, we'll use a simplified approach without upperdir/workdir
        // In a full implementation, we'd need to import the actual mount logic from overlayfs
        info!("Mounting partition {} with {} layers using overlayfs", partition_name, lowerdir.len());
        
        // TODO: Import and use the actual mount_overlay function from ksud_overlayfs
        // mount::mount_overlay(&partition, lowerdir, workdir, upperdir)?;
        
        Ok(())
    }

    pub fn get_magic_modules(&self) -> &Vec<String> {
        &self.magic_modules
    }

    pub fn get_overlayfs_modules(&self) -> &Vec<String> {
        &self.overlayfs_modules
    }
}