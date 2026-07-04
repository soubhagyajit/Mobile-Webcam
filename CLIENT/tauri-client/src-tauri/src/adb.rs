use std::process::Command;
use std::{env, path::PathBuf};
use serde::Serialize;
use tauri::path::BaseDirectory;
use tauri::{AppHandle, Manager};

#[derive(Clone, Serialize, Debug)]
pub struct Device {
    id: String,
    model: String,
}

#[tauri::command]
pub fn adb_get_devices(handle: AppHandle) -> Vec<Device> {
    let adb = get_file_path(&handle, "adb/adb.exe");
    let output = Command::new(adb).arg("devices").output().expect("Failed to execute adb devices command");
    // println!("ADB devices output: {:?}", output);
    let output_str = String::from_utf8_lossy(&output.stdout);
    // println!("ADB devices output string: {}", output_str);
    let mut devices = Vec::new();
    for line in output_str.lines().skip(1) {
        if !line.trim().is_empty() {
            let id = line.split_whitespace().next().unwrap_or("");
            let output = Command::new(get_file_path(&handle, "adb/adb.exe")).args(["-s", id, "shell", "getprop", "ro.product.model"]).output().expect("Failed to execute adb shell command");
            let model = String::from_utf8_lossy(&output.stdout).trim().to_string();
            println!("Device: {}, Model: {}", id, model);
            devices.push(Device {id: id.to_string(), model });
        }
    }
    // println!("ADB devices found: {:?}", devices);
    devices
}

#[tauri::command]
pub fn adb_connect_device(handle: AppHandle, device_id: String, device_model: String) -> Result<String, String> {
    let result = adb_disconnect_device(&handle, &device_id, &device_model);
    match result {
        Ok(_) => (),
        Err(err) => {
            if err.contains("listener 'tcp:8080' not found"){
                // for now I don't know what should I do here
                // println!("No existing port forwarding to remove for device {}: {}", device_model, err);
            } else {
                return Err(format!("Failed to disconnect device {}: {}", device_model, err));
            }
        }
    }
    let adb = get_file_path(&handle, "adb/adb.exe");
    let output = Command::new(adb).args(["-s", &device_id, "forward", "tcp:8080", "tcp:8080"]).output().expect("Failed to execute adb forward command");
    if output.status.success() {
        Ok(format!("Device {} is ready", device_model))
    } else {
        Err(format!("Device {} not ready, failed to set up port forwarding: {}", device_model, String::from_utf8_lossy(&output.stderr)))
    }
}

// #[tauri::command]
fn adb_disconnect_device(handle: &AppHandle, device_id: &str, device_model: &str) -> Result<String, String> {
    let adb = get_file_path(&handle, "adb/adb.exe");
    let output = Command::new(adb).args(["-s", &device_id, "forward", "--remove", "tcp:8080"]).output().expect("Failed to execute adb forward command");
    if output.status.success() {
        Ok(format!("Device {} is disconnected", device_model))
    } else {
        Err(format!("Failed to disconnect device {}: {}", device_model, String::from_utf8_lossy(&output.stderr)))
    }
}

fn get_file_path(handle: &AppHandle, file: &str) -> PathBuf {
    if cfg!(debug_assertions) {
        let base = env::current_exe().unwrap().parent().unwrap().to_path_buf();
        // println!("Looking for: {}", base.display());
        return base.join(file);
    } else {
        handle
            .path()
            .resolve(file, BaseDirectory::Resource)
            .unwrap()
    }
}

