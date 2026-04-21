/*
 * Copyright © 2026 Soubhagyajit Borah
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 */

const { exec, spawn } = require('child_process');
const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { app, dialog } = require('electron');

const platform = os.platform();
const tempDir = path.join(app.getPath('temp'), 'mobile-webcam-installer');

// Ensure temp directory exists
if (!fs.existsSync(tempDir)) {
    fs.mkdirSync(tempDir, { recursive: true });
}

// Check if Python is installed
async function checkPython() {
    return new Promise((resolve) => {
        exec('python --version', (error, stdout) => {
            if (!error && stdout.includes('Python 3')) {
                resolve({ installed: true, version: stdout.trim() });
            } else {
                exec('python3 --version', (error, stdout) => {
                    if (!error) {
                        resolve({ installed: true, version: stdout.trim(), command: 'python3' });
                    } else {
                        resolve({ installed: false });
                    }
                });
            }
        });
    });
}

// Install Python
async function installPython(onProgress) {
    if (platform === 'win32') {
        onProgress('Downloading Python installer...', 10);
        
        // Download Python installer
        const pythonUrl = 'https://www.python.org/ftp/python/3.11.7/python-3.11.7-amd64.exe';
        const installerPath = path.join(tempDir, 'python-installer.exe');
        
        await downloadFile(pythonUrl, installerPath, (downloaded, total) => {
            const percent = Math.round((downloaded / total) * 50);
            onProgress(`Downloading Python... ${percent}%`, 10 + percent);
        });
        
        onProgress('Installing Python (this may take a few minutes)...', 60);
        
        // Install Python silently with pip and add to PATH
        await new Promise((resolve, reject) => {
            exec(`"${installerPath}" /quiet InstallAllUsers=0 PrependPath=1 Include_pip=1`, 
                { maxBuffer: 1024 * 1024 * 10 }, 
                (error) => {
                    if (error) reject(error);
                    else resolve();
                }
            );
        });
        
        onProgress('Python installed!', 100);
        
    } else if (platform === 'darwin') {
        onProgress('Installing Python via Homebrew...', 50);
        await runCommand('brew', ['install', 'python@3.11']);
        onProgress('Python installed!', 100);
        
    } else {
        onProgress('Installing Python...', 50);
        
        // Try apt first
        try {
            await runCommand('apt-get', ['install', '-y', 'python3', 'python3-pip']);
        } catch {
            try {
                await runCommand('dnf', ['install', '-y', 'python3', 'python3-pip']);
            } catch {
                await runCommand('pacman', ['-S', '--noconfirm', 'python', 'python-pip']);
            }
        }
        
        onProgress('Python installed!', 100);
    }
}

// Check if pyvirtualcam is installed
async function checkPyvirtualcam() {
    return new Promise((resolve) => {
        const pythonCmd = platform === 'win32' ? 'python' : 'python3';
        exec(`${pythonCmd} -c "import pyvirtualcam"`, (error) => {
            resolve(!error);
        });
    });
}

// Install pyvirtualcam and dependencies
async function installPyvirtualcam(onProgress) {
    const pythonCmd = platform === 'win32' ? 'python' : 'python3';
    
    onProgress('Installing pyvirtualcam and dependencies...', 20);
    
    // Install dependencies
    const packages = ['opencv-python', 'numpy', 'requests', 'pyvirtualcam'];
    
    for (let i = 0; i < packages.length; i++) {
        const pkg = packages[i];
        const percent = 20 + ((i + 1) / packages.length) * 80;
        onProgress(`Installing ${pkg}...`, percent);
        
        await new Promise((resolve, reject) => {
            exec(`${pythonCmd} -m pip install ${pkg}`, 
                { maxBuffer: 1024 * 1024 * 10 },
                (error) => {
                    if (error) reject(error);
                    else resolve();
                }
            );
        });
    }
    
    onProgress('pyvirtualcam installed!', 100);
}

// Check if virtual camera backend is installed
async function checkVirtualCamBackend() {
    if (platform === 'win32') {
        // Check for OBS Virtual Camera (comes with pyvirtualcam on Windows)
        return new Promise((resolve) => {
            exec('reg query "HKLM\\SOFTWARE\\OBS Studio"', (error) => {
                resolve(!error);
            });
        });
    } else if (platform === 'darwin') {
        // Check for OBS Virtual Camera
        return new Promise((resolve) => {
            exec('ls /Library/CoreMediaIO/Plug-Ins/DAL/ 2>/dev/null | grep OBS', (error, stdout) => {
                resolve(stdout.includes('OBS'));
            });
        });
    } else {
        // Check for v4l2loopback
        return new Promise((resolve) => {
            exec('lsmod | grep v4l2loopback', (error, stdout) => {
                resolve(stdout.includes('v4l2loopback'));
            });
        });
    }
}

// Install virtual camera backend
async function installVirtualCamBackend(onProgress) {
    if (platform === 'win32') {
        onProgress('Installing OBS Studio (includes virtual camera)...', 30);
        
        // Download OBS installer
        const obsUrl = 'https://github.com/obsproject/obs-studio/releases/download/29.1.3/OBS-Studio-29.1.3-Full-Installer-x64.exe';
        const installerPath = path.join(tempDir, 'obs-installer.exe');
        
        await downloadFile(obsUrl, installerPath, (downloaded, total) => {
            const percent = Math.round((downloaded / total) * 50);
            onProgress(`Downloading OBS Studio... ${percent}%`, 30 + percent);
        });
        
        onProgress('Installing OBS Studio...', 80);
        
        await new Promise((resolve, reject) => {
            exec(`"${installerPath}" /S`, (error) => {
                if (error) reject(error);
                else resolve();
            });
        });
        
        onProgress('OBS Studio installed!', 100);
        
    } else if (platform === 'darwin') {
        onProgress('Installing OBS Virtual Camera...', 50);
        
        try {
            await runCommand('brew', ['install', '--cask', 'obs-virtualcam']);
        } catch {
            // Fallback to full OBS
            await runCommand('brew', ['install', '--cask', 'obs']);
        }
        
        onProgress('OBS Virtual Camera installed!', 100);
        
    } else {
        onProgress('Installing v4l2loopback...', 50);
        
        try {
            await runCommand('apt-get', ['install', '-y', 'v4l2loopback-dkms']);
            await runCommand('modprobe', ['v4l2loopback', 'devices=1', 'video_nr=10', 'card_label=MobileWebcam', 'exclusive_caps=1']);
        } catch {
            try {
                await runCommand('dnf', ['install', '-y', 'v4l2loopback']);
                await runCommand('modprobe', ['v4l2loopback', 'devices=1', 'video_nr=10', 'card_label=MobileWebcam', 'exclusive_caps=1']);
            } catch {
                await runCommand('pacman', ['-S', '--noconfirm', 'v4l2loopback-dkms']);
                await runCommand('modprobe', ['v4l2loopback', 'devices=1', 'video_nr=10', 'card_label=MobileWebcam', 'exclusive_caps=1']);
            }
        }
        
        onProgress('v4l2loopback installed!', 100);
    }
}

// Helper: Download file
function downloadFile(url, dest, onProgress) {
    return new Promise((resolve, reject) => {
        const file = fs.createWriteStream(dest);
        const protocol = url.startsWith('https') ? https : http;
        
        protocol.get(url, (response) => {
            if (response.statusCode === 302 || response.statusCode === 301) {
                // Follow redirect
                return downloadFile(response.headers.location, dest, onProgress)
                    .then(resolve)
                    .catch(reject);
            }
            
            const totalSize = parseInt(response.headers['content-length'], 10);
            let downloadedSize = 0;

            response.on('data', (chunk) => {
                downloadedSize += chunk.length;
                if (onProgress) {
                    onProgress(downloadedSize, totalSize);
                }
            });

            response.pipe(file);

            file.on('finish', () => {
                file.close();
                resolve(dest);
            });
        }).on('error', (err) => {
            fs.unlink(dest, () => {});
            reject(err);
        });
    });
}

// Helper: Run command with admin privileges
function runCommand(command, args = []) {
    return new Promise((resolve, reject) => {
        if (platform === 'win32') {
            const psCommand = `Start-Process -FilePath "${command}" -ArgumentList "${args.join(' ')}" -Verb RunAs -Wait`;
            exec(`powershell -Command "${psCommand}"`, (error, stdout) => {
                if (error) reject(error);
                else resolve(stdout);
            });
        } else if (platform === 'darwin') {
            const script = `do shell script "${command} ${args.join(' ')}" with administrator privileges`;
            exec(`osascript -e '${script}'`, (error, stdout) => {
                if (error) reject(error);
                else resolve(stdout);
            });
        } else {
            exec(`pkexec ${command} ${args.join(' ')}`, (error, stdout) => {
                if (error) reject(error);
                else resolve(stdout);
            });
        }
    });
}

// Main auto-setup function
async function autoSetupPython(progressCallback) {
    const steps = [
        { 
            name: 'Python', 
            check: checkPython, 
            install: installPython 
        },
        { 
            name: 'pyvirtualcam', 
            check: checkPyvirtualcam, 
            install: installPyvirtualcam 
        },
        { 
            name: 'Virtual Camera Backend', 
            check: checkVirtualCamBackend, 
            install: installVirtualCamBackend 
        }
    ];
    
    const results = {
        success: true,
        installed: [],
        errors: []
    };
    
    for (const step of steps) {
        try {
            progressCallback(`Checking ${step.name}...`, 0);
            
            const checkResult = await step.check();
            const isInstalled = checkResult.installed !== undefined ? checkResult.installed : checkResult;
            
            if (!isInstalled) {
                progressCallback(`Installing ${step.name}...`, 0);
                await step.install(progressCallback);
                results.installed.push(step.name);
            } else {
                const version = checkResult.version || '';
                progressCallback(`${step.name} already installed ${version} ✓`, 100);
            }
        } catch (error) {
            results.success = false;
            results.errors.push({ step: step.name, error: error.message });
            progressCallback(`Error: ${step.name} - ${error.message}`, 0);
        }
    }
    
    return results;
}

async function showSetupDialog(mainWindow,py,vc) {
    let detailText = 'Would you like to automatically install the required components?\n\n';

    if (!py && !vc) {
        detailText += 'This will install:\n• Python\n• pyvirtualcamera library';
    } else if (!py) {
        detailText += 'This will install:\n• Python';
    } else if (!vc) {
        detailText += 'This will install:\n• pyvirtualcamera library';
    } else {
        detailText += 'All components are already installed.';
    }
    const response = await dialog.showMessageBox(mainWindow, {
        type: 'question',
        title: 'Virtual Camera Setup',
        message: 'Required components are missing.',
        detail: detailText,
        buttons: ['Install Automatically', 'Skip', 'Manual Instructions'],
        defaultId: 0,
        cancelId: 1
    });

    return response.response;
}

/**
 * Creates the dedicated window for the installation UI.
 */
function createInstallWindow() {
    const installWindow = new BrowserWindow({
        width: 640,
        height: 480,
        resizable: false,
        frame: true,
        webPreferences: {
            nodeIntegration: true,
            contextIsolation: false
        }
    });

    installWindow.loadFile('installer.html');
    return installWindow;
}
module.exports = {
    autoSetupPython,
    checkPython, 
    showSetupDialog,
    createInstallWindow,
    checkPyvirtualcam,
    checkVirtualCamBackend,
    installPython,
    installPyvirtualcam,
    installVirtualCamBackend
};