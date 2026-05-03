/*
 * Copyright © 2026 Soubhagyajit Borah
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 */

const { exec, execFile } = require('child_process');
const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { app, dialog, BrowserWindow } = require('electron');

const platform = os.platform();
const tempDir = path.join(app.getPath('temp'), 'mobile-webcam-installer');

if (!fs.existsSync(tempDir)) {
    fs.mkdirSync(tempDir, { recursive: true });
}

// ─── Helpers ────────────────────────────────────────────────────────────────

function getBaseDir() {
    return app.isPackaged ? process.resourcesPath : __dirname + '/resources';
}

/**
 * All DLLs that must exist beside sender.exe.
 * opencv_videoio_ffmpeg is optional — only needed if OpenCV's FFMPEG backend
 * is used (it is, for CAP_ANY on HTTP streams), so we treat it as required.
 */
const REQUIRED_FILES = [
    'sender.exe',
    'softcam.dll',
    'opencv_world4120.dll',
    'opencv_videoio_ffmpeg4120_64.dll',
];

// ─── Check functions ─────────────────────────────────────────────────────────

/**
 * Verifies that all required files are present in baseDir.
 * Returns { ok: bool, missing: string[] }
 */
function checkRequiredFiles() {
    const baseDir = getBaseDir();
    const missing = REQUIRED_FILES.filter(f => !fs.existsSync(path.join(baseDir, f)));
    return { ok: missing.length === 0, missing };
}

/**
 * Checks whether softcam.dll is already registered in the Windows registry.
 * softcam writes its CLSID under HKCR\CLSID on registration.
 */
async function checkSoftcamRegistered() {
    if (platform !== 'win32') return true;

    return new Promise((resolve) => {
        // Query DirectShow video capture devices via PowerShell.
        // If "AWC Virtual Camera" appears, softcam.dll is registered and active.
        const ps = `Get-PnpDevice -Class Camera | Select-Object -ExpandProperty FriendlyName`;
        exec(`powershell -NoProfile -Command "${ps}"`, (error, stdout) => {
            if (!error && stdout.includes('AWC Virtual Cam')) {
                console.log('[V-Cam] AWC Virtual Cam found — driver registered ✓');
                return resolve(true);
            }

            // Fallback: check via DirectShow registry category (Video Capture Sources)
            // HKCR\CLSID\{860BB310-...}\Instance lists all registered capture devices
            exec(
                'reg query "HKCR\\CLSID\\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\\Instance" /s 2>nul',
                (err2, stdout2) => {
                    resolve(!err2 && stdout2.includes('AWC Virtual Cam'));
                }
            );
        });
    });
}

/**
 * Checks whether the MSVC 2015–2022 x64 runtime is installed.
 * Looks for the VC++ redist entry in Add/Remove Programs.
 */
async function checkVcRedist() {
    if (platform !== 'win32') return true;
    return new Promise((resolve) => {
        exec(
            'reg query "HKLM\\SOFTWARE\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\x64" /v Version 2>nul',
            (error, stdout) => {
                // Key exists and version >= 14.40 (VS 2022)
                if (error || !stdout) return resolve(false);
                const match = stdout.match(/v(\d+)\.(\d+)/i);
                if (match && parseInt(match[1]) >= 14) {
                    resolve(true);
                } else {
                    resolve(false);
                }
            }
        );
    });
}

// ─── Install / register functions ────────────────────────────────────────────

/**
 * Registers softcam.dll with regsvr32 using its absolute path.
 * The absolute path is critical — the registry stores it so apps can load the DLL.
 */
async function registerSoftcam(onProgress) {
    if (platform !== 'win32') return;

    const dllPath = path.join(getBaseDir(), 'softcam.dll');

    onProgress('Registering virtual camera driver...', 30);

    return new Promise((resolve, reject) => {
        execFile('regsvr32', ['/s', dllPath], { shell: true }, (error) => {
            if (error) {
                console.error('[Installer] regsvr32 failed:', error.message);
                // regsvr32 fails silently with /s — try with a UAC elevation via PowerShell
                const psCmd = `Start-Process regsvr32 -ArgumentList '/s \\"${dllPath}\\"' -Verb RunAs -Wait`;
                exec(`powershell -Command "${psCmd}"`, (err2) => {
                    if (err2) reject(new Error('Failed to register softcam.dll — try running as administrator.'));
                    else resolve();
                });
            } else {
                resolve();
            }
        });
    });
}

/**
 * Unregisters softcam.dll — called on uninstall or cleanup.
 */
async function unregisterSoftcam() {
    if (platform !== 'win32') return;
    const dllPath = path.join(getBaseDir(), 'softcam.dll');
    return new Promise((resolve) => {
        execFile('regsvr32', ['/u', '/s', dllPath], { shell: true }, () => resolve());
    });
}

/**
 * Downloads and silently installs the Microsoft MSVC 2015–2022 x64 redistributable.
 */
async function installVcRedist(onProgress) {
    if (platform !== 'win32') return;

    const url = 'https://aka.ms/vs/17/release/vc_redist.x64.exe';
    const dest = path.join(tempDir, 'vc_redist.x64.exe');

    onProgress('Downloading MSVC runtime...', 10);

    await downloadFile(url, dest, (downloaded, total) => {
        const pct = total ? Math.round((downloaded / total) * 60) : 0;
        onProgress(`Downloading MSVC runtime... ${pct}%`, 10 + pct);
    });

    onProgress('Installing MSVC runtime (this may take a moment)...', 72);

    await new Promise((resolve, reject) => {
        // /install /quiet /norestart — standard silent flags for the VC++ redist
        execFile(dest, ['/install', '/quiet', '/norestart'], (error) => {
            if (error && error.code !== 1638) {
                // 1638 = "another version already installed" — treat as success
                reject(new Error(`MSVC install failed (code ${error.code}): ${error.message}`));
            } else {
                resolve();
            }
        });
    });

    onProgress('MSVC runtime installed!', 100);
}

// ─── Download helper ──────────────────────────────────────────────────────────

function downloadFile(url, dest, onProgress) {
    return new Promise((resolve, reject) => {
        const file = fs.createWriteStream(dest);
        const protocol = url.startsWith('https') ? https : http;

        const request = (targetUrl) => {
            protocol.get(targetUrl, (response) => {
                if (response.statusCode === 301 || response.statusCode === 302) {
                    return request(response.headers.location);
                }
                if (response.statusCode !== 200) {
                    return reject(new Error(`Download failed: HTTP ${response.statusCode}`));
                }

                const total = parseInt(response.headers['content-length'], 10);
                let downloaded = 0;

                response.on('data', (chunk) => {
                    downloaded += chunk.length;
                    if (onProgress) onProgress(downloaded, total);
                });

                response.pipe(file);
                file.on('finish', () => { file.close(); resolve(dest); });
            }).on('error', (err) => {
                fs.unlink(dest, () => {});
                reject(err);
            });
        };

        request(url);
    });
}

// ─── Main setup flow ──────────────────────────────────────────────────────────

/**
 * Runs the full setup sequence:
 *   1. Check all required files are present
 *   2. Install MSVC runtime if missing
 *   3. Register softcam.dll if not already registered
 */
async function autoSetup(progressCallback) {
    const results = { success: true, installed: [], errors: [] };

    const report = (msg, pct) => {
        console.log(`[Installer] ${msg}`);
        if (progressCallback) progressCallback(msg, pct);
    };

    // Step 1 — file presence check
    report('Checking required files...', 0);
    const { ok, missing } = checkRequiredFiles();
    if (!ok) {
        const msg = `Missing files in resources folder: ${missing.join(', ')}`;
        results.success = false;
        results.errors.push({ step: 'File check', error: msg });
        report(`Error: ${msg}`, 0);
        return results;
    }
    report('All required files found ✓', 10);

    // Step 2 — MSVC runtime
    try {
        report('Checking MSVC runtime...', 15);
        const hasRedist = await checkVcRedist();
        if (!hasRedist) {
            report('MSVC runtime not found — installing...', 20);
            await installVcRedist(report);
            results.installed.push('MSVC VC++ Runtime');
        } else {
            report('MSVC runtime already installed ✓', 50);
        }
    } catch (error) {
        // Non-fatal — sender.exe might still work if system DLLs are present
        results.errors.push({ step: 'MSVC Runtime', error: error.message });
        report(`Warning: MSVC install failed — ${error.message}`, 50);
    }

    // Step 3 — softcam.dll registration
    try {
        report('Checking softcam driver registration...', 55);
        const isRegistered = await checkSoftcamRegistered();
        if (!isRegistered) {
            report('Registering softcam virtual camera driver...', 60);
            await registerSoftcam(report);
            results.installed.push('softcam.dll (virtual camera driver)');
            report('Virtual camera driver registered ✓', 100);
        } else {
            report('Virtual camera driver already registered ✓', 100);
        }
    } catch (error) {
        results.success = false;
        results.errors.push({ step: 'softcam registration', error: error.message });
        report(`Error: softcam registration failed — ${error.message}`, 60);
    }

    return results;
}

// ─── Dialog ───────────────────────────────────────────────────────────────────

/**
 * Shows a dialog explaining what will be installed and asks for confirmation.
 * Returns 0 = proceed, 1 = skip, 2 = show manual instructions.
 */
async function showSetupDialog(mainWindow) {
    const { ok, missing } = checkRequiredFiles();
    const hasRedist = await checkVcRedist();
    const isRegistered = await checkSoftcamRegistered();

    const toInstall = [];
    if (!hasRedist) toInstall.push('• Microsoft MSVC Runtime (VC++ 2022 x64)');
    if (!isRegistered) toInstall.push('• softcam virtual camera driver (regsvr32)');

    if (ok && hasRedist && isRegistered) {
        return -1; // nothing to do
    }

    let detail = 'Mobile Webcam needs to set up the following:\n\n';
    detail += toInstall.length ? toInstall.join('\n') : 'No additional installs needed.';

    if (!ok) {
        detail += `\n\n⚠ Missing files: ${missing.join(', ')}\nPlease reinstall the application.`;
    }

    detail += '\n\nThis requires administrator privileges.';

    const response = await dialog.showMessageBox(mainWindow, {
        type: 'question',
        title: 'Mobile Webcam Setup',
        message: 'One-time setup required',
        detail,
        buttons: ['Set Up Now', 'Skip', 'Manual Instructions'],
        defaultId: 0,
        cancelId: 1,
    });

    return response.response;
}

// ─── Install window ───────────────────────────────────────────────────────────

function createInstallWindow() {
    const installWindow = new BrowserWindow({
        width: 480,
        height: 640,
        resizable: false,
        frame: true,
        webPreferences: {
            nodeIntegration: true,
            contextIsolation: false,
        },
    });

    installWindow.loadFile('installer.html');
    return installWindow;
}

// ─── Exports ──────────────────────────────────────────────────────────────────

module.exports = {
    autoSetup,
    showSetupDialog,
    createInstallWindow,
    checkRequiredFiles,
    checkSoftcamRegistered,
    checkVcRedist,
    registerSoftcam,
    unregisterSoftcam,
    installVcRedist,
    getBaseDir,
};