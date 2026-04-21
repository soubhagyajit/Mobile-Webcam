/*
 * Copyright © 2026 Soubhagyajit Borah
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 */

const { app, BrowserWindow, ipcMain,Menu } = require('electron');
const path = require('path');
const { exec } = require('child_process');
const virtualCamera = require('./virtualCamera');
const autoInstaller = require('./autoinstaller');

let mainWindow = null;
let viewWindow = null;
let installWindow = null;

async function createWindow() {
    mainWindow = new BrowserWindow({
        width: 1200,
        height: 800,
        title: 'Mobile Webcam',
        // autoHideMenuBar:true,
        webPreferences: {
            nodeIntegration: true,
            contextIsolation: false,
        }
    });

    mainWindow.loadFile('index.html');
    
    // Setup ADB
    setupAdb();
    
    // Setup virtual camera IPC
    virtualCamera.setupVirtualCameraIPC();
    
    // Check if setup is needed
    await checkAndSetupComponents();
}

async function isAdbAvailable() {
    return new Promise((resolve) => {
        exec('adb --version', (error) => {
            if (error) {
                resolve(false);
            } else {
                resolve(true);
            }
        });
    });
}

async function setupAdb() {
    const adbPresent = await isAdbAvailable();
    
    if (!adbPresent) {
        console.log('ADB not found. USB mode will be disabled, but WiFi mode is ready.');
        showAdbInstructions(1);
        return; // Exit quietly - no dialog!
    }
    exec('adb forward tcp:8080 tcp:8080', (error, stdout, stderr) => {
        if (error) {
            console.error(`ADB Error: ${error.message}`);
            // Show ADB installation dialog if needed
            showAdbInstructions(2);
            return;
        }
        console.log('✓ ADB Forwarding successful: 8080 -> 8080');
    });
}

function showAdbInstructions(ins) {
    const { dialog } = require('electron');
    let text = "";
    let tle = "";
    let msg = "";
    if(ins == 1){
        tle="ADB not Found";
        msg = "Android Debug Bridge (ADB) is not installed";
        text='For USB mode, you need ADB.\n\n' +
                'Install Android Studio or Platform Tools:\n' +
                'https://developer.android.com/studio/releases/platform-tools\n\n' +
                'WiFi mode will still work without ADB.';
    }
    else if (ins == 2){
        tle="Connection error";
        msg = "Device not Found";
        text="Can't connect to device, make sure USB is properly connected. Ignore it if you want to use wifi.";
    }
    dialog.showMessageBox(mainWindow, {
        type: 'info',
        title: tle,
        message: msg,
        detail: text,
        buttons: ['OK']
    });
}

async function checkAndSetupComponents() {
    try {
        // Check if Python and pyvirtualcamera is installed
        const pythonInstalled = await autoInstaller.checkPython();
        const checkPyvirtualcam = await autoInstaller.checkPyvirtualcam();
        console.log(`[Status] Python: ${pythonInstalled ? 'OK' : 'MISSING'}`);
        console.log(`[Status] Driver: ${checkPyvirtualcam ? 'OK' : 'MISSING'}`);
        if (!pythonInstalled || !checkPyvirtualcam) {
            // Show setup dialog
            const choice = await autoInstaller.showSetupDialog(mainWindow, pythonInstalled, checkPyvirtualcam);
            
            if (choice === 0) {
                // User chose automatic installation
                startAutoInstall();
                // if (!pythonInstalled){autoInstaller.installPython();}
                // if (!checkPyvirtualcam){autoInstaller.installPyvirtualcam();}

            } else if (choice === 2) {
                // User chose manual instructions
                showManualInstructions();
            }
            // choice === 1 means skip
        } else {
            console.log('✓ All components already installed');
        }
    } catch (error) {
        console.error('Setup check error:', error);
    }
}

function startAutoInstall() {
    installWindow = new BrowserWindow({
        width: 480,
        height: 640,
        resizable: true,
        frame: false,
        parent: mainWindow,
        modal: true,
        webPreferences: {
            nodeIntegration: true,
            contextIsolation: false
        }
    });
    
    installWindow.loadFile('installer.html');
    
    installWindow.on('closed', () => {
        installWindow = null;
    });
}

function showManualInstructions() {
    const { shell } = require('electron');
    const { dialog } = require('electron');
    
    dialog.showMessageBox(mainWindow, {
        type: 'info',
        title: 'Manual Installation Instructions',
        message: 'Virtual Camera Setup',
        detail: 'Please install these components manually:\n\n' +
                '1. Python (v3.10 or higher):\n' +
                '   https://www.python.org/downloads/\n' +
                '   *IMPORTANT: Check "Add Python to PATH" during installation.*\n\n' +
                
                '2. Virtual Camera Driver:\n' +
                '   • Windows/Mac: Install OBS Studio\n' +
                '     https://obsproject.com/download\n' +
                '   • Linux: Install v4l2loopback\n' +
                '     sudo apt install v4l2loopback-dkms\n\n' +
                
                '3. Required Python Libraries:\n' +
                '   Open terminal/command prompt and run:\n' +
                '   pip install opencv-python pyvirtualcam requests numpy\n\n' +
                
                'Restart the app after installation.',
        buttons: ['Open Python Website','Virtual Camera Driver','Close']
    }).then((result) => {
        if (result.response === 0) {
            shell.openExternal('https://www.python.org/downloads');
        }
        if (result.response === 1) {
            shell.openExternal('https://obsproject.com/download');
        }
    });
}

// IPC: Start auto installation
ipcMain.on('start-auto-install', async (event) => {
    try {
        const result = await autoInstaller.autoSetupPython((message, percent) => {
            event.reply('install-progress', { message, percent });
        });
        
        event.reply('install-complete', result);
        
        // if (result.success) {
        //     // Wait 2 seconds then close installer
        //     setTimeout(() => {
        //         if (installWindow) {
        //             installWindow.close();
        //         }
        //     }, 2000);
        // }
    } catch (error) {
        event.reply('install-complete', {
            success: false,
            errors: [{ step: 'Installation', error: error.message }]
        });
    }
});

// Feed window handlers
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

    viewWindow.loadFile('feed.html', { query: { "url": streamUrl } });

    viewWindow.on('closed', () => {
        viewWindow = null;
    });
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

// Manual reinstall trigger
ipcMain.on('reinstall-components', () => {
    startAutoInstall();
});

const menuTemplate = [
  {
    label: 'File',
    submenu: [
      { label: 'Installation Window', click: () => { startAutoInstall() } },
      { type: 'separator' },
      { role: 'quit' }
    ]
  },
  {
    label: 'Help',
    submenu: [
      { label: 'Website', click: async () => { 
          const { shell } = require('electron');
          await shell.openExternal('https://www.soubhagyajit.com');
        } 
      }
    ]
  }
];
app.whenReady().then(() => {
  const menu = Menu.buildFromTemplate(menuTemplate);
  Menu.setApplicationMenu(menu);
  createWindow();
});

app.on('window-all-closed', () => {
    virtualCamera.cleanupVirtualCamera();
    if (process.platform !== 'darwin') {
        app.quit();
    }
});

app.on('before-quit', () => {
    virtualCamera.cleanupVirtualCamera();
});

console.log(`
╔═══════════════════════════════════════════════════╗
║   Mobile Webcam Desktop Client                    ║
║   Auto-installer enabled                          ║
╚═══════════════════════════════════════════════════╝
`);