/*
 * Copyright © 2026 Soubhagyajit Borah
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 */

const { app, BrowserWindow, ipcMain, Menu, dialog, shell } = require('electron');
const path = require('path');
const { exec } = require('child_process');
const virtualCamera = require('./virtualCamera');
const autoInstaller = require('./autoinstaller');

let mainWindow = null;
let viewWindow = null;
let installWindow = null;

// ─── Window creation ──────────────────────────────────────────────────────────

async function createWindow() {
    mainWindow = new BrowserWindow({
        width: 1200,
        height: 800,
        title: 'Mobile Webcam',
        webPreferences: {
            nodeIntegration: true,
            contextIsolation: false,
        }
    });

    mainWindow.loadFile('index.html');

    virtualCamera.setupVirtualCameraIPC();

    await setupAdb();
    await checkAndSetupComponents();
}

// ─── ADB ──────────────────────────────────────────────────────────────────────

async function isAdbAvailable() {
    return new Promise((resolve) => {
        exec('adb --version', (error) => resolve(!error));
    });
}

async function setupAdb() {
    const adbPresent = await isAdbAvailable();

    if (!adbPresent) {
        console.log('[ADB] Not found — USB mode disabled, WiFi mode ready.');
        showAdbInstructions(1);
        return;
    }

    exec('adb forward tcp:8080 tcp:8080', (error) => {
        if (error) {
            console.error(`[ADB] Forward error: ${error.message}`);
            showAdbInstructions(2);
        } else {
            console.log('[ADB] ✓ Port forwarding active: 8080 → 8080');
        }
    });
}

function showAdbInstructions(ins) {
    const configs = {
        1: {
            title: 'ADB Not Found',
            message: 'Android Debug Bridge (ADB) is not installed',
            detail: 'For USB mode, you need ADB.\n\n' +
                    'Install Android Platform Tools:\n' +
                    'https://developer.android.com/studio/releases/platform-tools\n\n' +
                    'WiFi mode will still work without ADB.'
        },
        2: {
            title: 'Connection Error',
            message: 'Device Not Found',
            detail: "Can't connect to device — make sure USB is properly connected.\n" +
                    'You can ignore this if you want to use WiFi mode.'
        }
    };

    const cfg = configs[ins];
    if (!cfg || !mainWindow) return;

    dialog.showMessageBox(mainWindow, {
        type: 'info',
        title: cfg.title,
        message: cfg.message,
        detail: cfg.detail,
        buttons: ['OK']
    });
}

// ─── Component setup ──────────────────────────────────────────────────────────

async function checkAndSetupComponents() {
    try {
        // Quick pre-checks so we can decide whether to prompt at all
        const { ok: filesOk, missing } = autoInstaller.checkRequiredFiles();
        const vcRedistOk = await autoInstaller.checkVcRedist();
        const softcamOk  = await autoInstaller.checkSoftcamRegistered();

        console.log(`[Status] Required files : ${filesOk   ? 'OK' : 'MISSING — ' + missing.join(', ')}`);
        console.log(`[Status] MSVC runtime   : ${vcRedistOk ? 'OK' : 'MISSING'}`);
        console.log(`[Status] softcam driver : ${softcamOk  ? 'OK' : 'NOT REGISTERED'}`);

        // If files are missing there's nothing the installer can do — tell the user to reinstall
        if (!filesOk) {
            dialog.showMessageBox(mainWindow, {
                type: 'error',
                title: 'Installation Corrupted',
                message: 'Required files are missing',
                detail: `The following files are missing from the application folder:\n\n` +
                        missing.map(f => `• ${f}`).join('\n') +
                        '\n\nPlease reinstall Mobile Webcam.',
                buttons: ['OK']
            });
            return;
        }

        // Everything already good — nothing to do
        if (vcRedistOk && softcamOk) {
            console.log('[Setup] ✓ All components ready');
            return;
        }

        // Ask the user
        const choice = await autoInstaller.showSetupDialog(mainWindow);

        if (choice === 0) {
            startAutoInstall();
        } else if (choice === 2) {
            showManualInstructions();
        }
        // choice === 1 → user skipped

    } catch (error) {
        console.error('[Setup] Check error:', error);
    }
}

function startAutoInstall() {
    // Don't open a second install window if one is already open
    if (installWindow) {
        installWindow.focus();
        return;
    }

    installWindow = new BrowserWindow({
        width: 480,
        height: 640,
        resizable: false,
        frame: false,
        parent: mainWindow,
        modal: true,
        webPreferences: {
            nodeIntegration: true,
            contextIsolation: false,
        }
    });

    installWindow.loadFile('installer.html');

    installWindow.on('closed', () => {
        installWindow = null;
    });
}

function showManualInstructions() {
    dialog.showMessageBox(mainWindow, {
        type: 'info',
        title: 'Manual Setup Instructions',
        message: 'Virtual Camera Setup',
        detail: 'Mobile Webcam needs two things to work:\n\n' +
                '1. Microsoft MSVC Runtime (VC++ 2022 x64)\n' +
                '   Download and run vc_redist.x64.exe from Microsoft.\n\n' +
                '2. Virtual Camera Driver (softcam.dll)\n' +
                '   Open a Command Prompt as Administrator and run:\n' +
                '   regsvr32 "<install folder>\\resources\\softcam.dll"\n\n' +
                'Restart the app after completing these steps.',
        buttons: ['Download VC++ Runtime', 'Close']
    }).then((result) => {
        if (result.response === 0) {
            shell.openExternal('https://aka.ms/vs/17/release/vc_redist.x64.exe');
        }
    });
}

// ─── IPC: Installer window ────────────────────────────────────────────────────

ipcMain.on('start-auto-install', async (event) => {
    try {
        const result = await autoInstaller.autoSetup((message, percent) => {
            // Guard: installer window might have been closed mid-install
            if (installWindow && !installWindow.isDestroyed()) {
                event.reply('install-progress', { message, percent });
            }
        });

        event.reply('install-complete', result);

        if (result.success && installWindow && !installWindow.isDestroyed()) {
            // Give the UI a moment to show the success state before closing
            setTimeout(() => {
                if (installWindow && !installWindow.isDestroyed()) {
                    installWindow.close();
                }
            }, 2000);
        }

    } catch (error) {
        event.reply('install-complete', {
            success: false,
            errors: [{ step: 'Installation', error: error.message }]
        });
    }
});

ipcMain.on('reinstall-components', () => {
    startAutoInstall();
});

// ─── IPC: Feed window ─────────────────────────────────────────────────────────

ipcMain.on('open-feed-window', (event, streamUrl) => {
    if (viewWindow) {
        viewWindow.focus();
        return;
    }

    viewWindow = new BrowserWindow({
        width: 800,
        height: 600,
        title: 'Live Feed',
        backgroundColor: '#000000',
        autoHideMenuBar: true,
        webPreferences: {
            nodeIntegration: true,
            contextIsolation: false,
        }
    });

    viewWindow.loadFile('feed.html', { query: { url: streamUrl } });
    viewWindow.on('closed', () => { viewWindow = null; });
});

ipcMain.on('close-feed-window', () => {
    if (viewWindow) {
        viewWindow.close();
        viewWindow = null;
    }
});

ipcMain.on('resize-feed-window', (event, data) => {
    if (viewWindow) {
        const targetWidth = 640;
        const targetHeight = Math.round(targetWidth / data.ratio);
        viewWindow.setAspectRatio(data.ratio);
        viewWindow.setContentSize(targetWidth, targetHeight);
        viewWindow.center();
    }
});

// ─── App menu ─────────────────────────────────────────────────────────────────

const menuTemplate = [
    {
        label: 'File',
        submenu: [
            {
                label: 'Run Setup Again',
                click: () => startAutoInstall()
            },
            { type: 'separator' },
            { role: 'quit' }
        ]
    },
    {
        label: 'Help',
        submenu: [
            {
                label: 'Website',
                click: () => shell.openExternal('https://www.soubhagyajit.com')
            },
            {
                label: 'Manual Setup Instructions',
                click: () => showManualInstructions()
            }
        ]
    }
];

// ─── App lifecycle ────────────────────────────────────────────────────────────

app.whenReady().then(() => {
    Menu.setApplicationMenu(Menu.buildFromTemplate(menuTemplate));
    createWindow();
});

app.on('window-all-closed', () => {
    virtualCamera.cleanupVirtualCamera();
    if (process.platform !== 'darwin') app.quit();
});

app.on('before-quit', () => {
    virtualCamera.cleanupVirtualCamera();
});

console.log(`
.---------------------------------------------------.
|   Mobile Webcam Desktop Client                    |
|   C++ Bridge + softcam — v3                       |
'---------------------------------------------------'
`);