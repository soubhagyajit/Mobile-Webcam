/*
 * Copyright © 2026 Soubhagyajit Borah
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 */

const { spawn } = require('child_process');
const { ipcMain, app } = require('electron');
const path = require('path');
const os = require('os');

let pythonProcess = null;
let isVirtualCameraRunning = false;

function getPythonCommand() {
    return os.platform() === 'win32' ? 'python' : 'python3';
}

// Start virtual camera
function startVirtualCamera(streamUrl, width = 1280, height = 720, fps = 30) {
    if (isVirtualCameraRunning) {
        return {
            success: false,
            message: 'Virtual camera already running'
        };
    }
    
    try {
        const pythonCmd = getPythonCommand();
        const isDev = !app.isPackaged;
        const scriptPath = isDev 
            ? path.join(__dirname, 'virtual_webcam.py') 
            : path.join(process.resourcesPath, 'virtual_webcam.py');
        
        console.log(`[Virtual Camera] Using script at: ${scriptPath}`);
        
        // Start Python script
        pythonProcess = spawn(pythonCmd, [
            scriptPath,
            streamUrl,
            width.toString(),
            height.toString(),
            fps.toString()
        ]);
        
        // Capture output
        pythonProcess.stdout.on('data', (data) => {
            console.log(`[Virtual Camera] ${data.toString().trim()}`);
        });
        
        pythonProcess.stderr.on('data', (data) => {
            console.error(`[Virtual Camera Error] ${data.toString().trim()}`);
        });
        
        pythonProcess.on('close', (code) => {
            console.log(`[Virtual Camera] Process exited with code ${code}`);
            isVirtualCameraRunning = false;
        });
        
        pythonProcess.on('error', (err) => {
            console.error(`[Virtual Camera] Failed to start: ${err.message}`);
            isVirtualCameraRunning = false;
        });
        
        isVirtualCameraRunning = true;
        
        return {
            success: true,
            message: 'Virtual camera started successfully'
        };
        
    } catch (error) {
        return {
            success: false,
            message: `Failed to start: ${error.message}`
        };
    }
}
// Stop virtual camera
function stopVirtualCamera() {
    if (pythonProcess) {
        pythonProcess.kill('SIGTERM');
        pythonProcess = null;
        isVirtualCameraRunning = false;
        
        return {
            success: true,
            message: 'Virtual camera stopped'
        };
    }
    
    return {
        success: false,
        message: 'Virtual camera not running'
    };
}

// Check if virtual camera is available
async function checkVirtualCamera() {
    return new Promise((resolve) => {
        const pythonCmd = getPythonCommand();
        const checkScript = 'import pyvirtualcam; print("OK")';
        
        const proc = spawn(pythonCmd, ['-c', checkScript]);
        
        let output = '';
        proc.stdout.on('data', (data) => {
            output += data.toString();
        });
        
        proc.on('close', (code) => {
            if (code === 0 && output.includes('OK')) {
                resolve({
                    installed: true,
                    message: 'pyvirtualcam is ready'
                });
            } else {
                resolve({
                    installed: false,
                    message: 'pyvirtualcam not installed. Click to install automatically.'
                });
            }
        });
        
        proc.on('error', () => {
            resolve({
                installed: false,
                message: 'Python not found. Click to install automatically.'
            });
        });
    });
}

// IPC Handlers
function setupVirtualCameraIPC() {
    ipcMain.on('check-virtual-camera', async (event) => {
        const result = await checkVirtualCamera();
        event.reply('virtual-camera-check-result', result);
    });
    
    ipcMain.on('start-virtual-camera', (event, streamUrl, width, height, fps) => {
        const result = startVirtualCamera(streamUrl, width, height, fps);
        event.reply('virtual-camera-status', result);
    });
    
    ipcMain.on('stop-virtual-camera', (event) => {
        const result = stopVirtualCamera();
        event.reply('virtual-camera-status', result);
    });
    
    ipcMain.on('get-virtual-camera-status', (event) => {
        event.reply('virtual-camera-running', isVirtualCameraRunning);
    });
}

// Cleanup
function cleanupVirtualCamera() {
    if (pythonProcess) {
        pythonProcess.kill('SIGTERM');
        pythonProcess = null;
    }
    isVirtualCameraRunning = false;
}

module.exports = {
    setupVirtualCameraIPC,
    cleanupVirtualCamera,
    startVirtualCamera,
    stopVirtualCamera,
    checkVirtualCamera
};