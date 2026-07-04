use serde::Serialize;
use std::env::consts::OS;
use std::fs::write;
use std::process::Command;
use std::{env, path::PathBuf};
use tauri::Emitter;
use tauri::path::BaseDirectory;
use tauri::{AppHandle, Manager};
// use std::{thread, time::Duration};

#[derive(Clone, Serialize)]
struct Progress {
    message: String,
    percent: u8,
}

const REQUIRED_FILES: &[&str] = &[
//     "sender.exe",
    "softcam.dll",
    // "opencv_world4120.dll",
    // "opencv_videoio_ffmpeg4120_64.dll",
];

#[tauri::command]
pub async fn init_installer(handle: AppHandle) {
    println!("Installer code on Rust initiated!");
 
    //step - 1:

    let (ok, missing) = check_required_files(&handle);
    report(&handle, "Checking required files...", 0);
    if ok {
        println!("All files found!");
        report(&handle, "All required files found ✓", 10);
    } else {
        let msg = format!("Missing files: {:?}", missing);
        println!("{}", msg);
        report(&handle, &format! {"Error: {}", msg}, 0);
    }

    //step - 2:
    report(&handle, "Checking MSVC runtime...", 12);
    if check_msvc(&handle).await {
        println!("Required MSVC Version found.");
        report(&handle, &format! {"MSVC runtime already installed ✓"}, 50);
    } else {
        println!("MSVC runtime not found — installing...");
        report(&handle, &format! {"MSVC runtime not found."}, 12);
        match install_msvc(&handle).await {
            Ok(_) => {
                report(&handle, "MSVC installed successfully.", 50);
            }
            Err(e) => {
                report(&handle, &format!("Failed: {}", e), 12);
            }
        }
    }

    //step - 3:
    report(&handle, "Checking webcam driver installation...", 60);
    if check_softcam_registered() {
        println!("Driver installed!");
        report(&handle, &format! {"Webcam Driver installed!"}, 100);
    } else {
        println!("Driver not installed");
        report(&handle, &format! {"Webcam Driver not installed"}, 60);
        report(&handle, "Installing webcam driver...", 75);
        match register_driver(&handle).await {
            Ok(_) => {
                report(&handle, "Driver installed successfully", 100);
            }
            Err(e) => {
                report(&handle, &format!("Failed: {}", e), 75);
            }
        };
    }
    report(&handle, "Exiting...", 100);
    handle.emit("close-installer", true).unwrap();
}

// ------------ Helper functions ------------

// returns a valid path
fn get_file_path(handle: &AppHandle, file: &str) -> PathBuf {
    if cfg!(debug_assertions) {
        let base = env::current_exe().unwrap().parent().unwrap().to_path_buf();
        println!("Looking for: {}", base.display());
        return base.join(file);
    } else {
        handle
            .path()
            .resolve(file, BaseDirectory::Resource)
            .unwrap()
    }
}

//report to frontend
fn report(handle: &AppHandle, msg: &str, pct: u8) {
    println!("[Installer] {}", msg);
    handle
        .emit(
            "installer-progress",
            Progress {
                message: msg.to_string(),
                percent: pct,
            },
        )
        .unwrap();
}

//downloads file
async fn download_file(url: &str, dest: &PathBuf, _handle: &AppHandle) -> Result<(), String> {
    let bytes = reqwest::get(url)
        .await
        .map_err(|e| e.to_string())?
        .bytes()
        .await
        .map_err(|e| e.to_string())?;
    write(&dest, bytes).map_err(|e| e.to_string())?;
    Ok(())
}

// step - 1 : checking required files
fn check_required_files(handle: &AppHandle) -> (bool, Vec<String>) {
    let mut missing = Vec::new();

    for file in REQUIRED_FILES {
        let path = get_file_path(handle, file);
        if !path.exists() {
            println!("{}", handle.path().resource_dir().unwrap().display());

            println!(
                "{}",
                handle
                    .path()
                    .resolve("softcam.dll", BaseDirectory::Resource)
                    .unwrap()
                    .display()
            );

            missing.push(file.to_string());
        }
    }

    (missing.is_empty(), missing)
}

//step - 2 : checking MSVC
async fn check_msvc(handle: &AppHandle) -> bool {
    if OS != "win32" {
        println!("OK");
        println!("OS: {}", OS)
    } else {
        println!("WIN32 NOT SUPPORTED");
        report(
            &handle,
            &format! {"Win32 not supported. Exiting installer..."},
            10,
        );
        return false;
    }

    let output = Command::new("reg")
        .args([
            "query",
            r"HKLM\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64",
            "/v",
            "Version",
        ])
        .output()
        .unwrap();
    let formated = String::from_utf8_lossy(&output.stdout);
    println!("{:?}", formated.split_whitespace());
    if let Some(version) = formated.split_whitespace().find(|s| s.starts_with('v')) {
        println!("Found Version: {}", version);
        let major: u32 = version
            .trim_start_matches('v')
            .split('.')
            .next()
            .unwrap()
            .parse()
            .unwrap();
        return major >= 14;
    } else {
        false
    }
}

//installing MSVC if not installed
async fn install_msvc(handle: &tauri::AppHandle) -> Result<bool, String> {
    let url = "https://aka.ms/vs/17/release/vc_redist.x64.exe";

    let dest = std::env::temp_dir().join("vc_redist.x64.exe");

    report(
        &handle,
        "Downloading MSVC runtime (this may take a moment depending on your internet connection)...",
        20,
    );

    download_file(url, &dest, &handle).await?;

    report(
        &handle,
        "Installing MSVC runtime (You might needed to take action on another window)...",
        45,
    );

    let status = Command::new(&dest)
        .args(["/install", "/quiet", "/norestart"])
        .status()
        .map_err(|e| e.to_string())?;
    if status.success()
        || status.code().unwrap_or(-1) == 1638
        || status.code().unwrap_or(-1) == 3010
    {
        println!("{}", status);
        println!("{:?}", status.code());
        Ok(true)
    } else {
        Err(format!("Installer exited with {}", status))
    }
}

//step - 3:
fn check_softcam_registered() -> bool {
    let output = Command::new("reg")
        .args([
            "query",
            r"HKCR\CLSID\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\Instance",
            "/s",
        ])
        .output();

    if let Ok(out) = output {
        let txt = String::from_utf8_lossy(&out.stdout);
        return txt.contains("AWC Virtual Cam");
    }

    false
}

async fn register_driver(handle: &AppHandle) -> Result<(), String> {
    if OS != "windows" {
        return Ok(());
    }

    let dll_path = get_file_path(handle, "softcam.dll");

    report(handle, "Registering virtual camera driver...", 90);

    let status = Command::new("regsvr32")
        .args(["/s", dll_path.to_str().unwrap()])
        .status()
        .map_err(|e| e.to_string())?;

    report(handle, "Registering virtual camera driver...", 99);
    if status.success() {
        return Ok(());
    }

    // Retry with admin rights
    let ps_cmd = format!(
        "Start-Process regsvr32 -ArgumentList '/s \"{}\"' -Verb RunAs -Wait",
        dll_path.display()
    );

    let status = Command::new("powershell")
        .args(["-Command", &ps_cmd])
        .status()
        .map_err(|e| e.to_string())?;

    if status.success() {
        Ok(())
    } else {
        Err("Failed to register softcam.dll. Try running as administrator.".to_string())
    }
}
