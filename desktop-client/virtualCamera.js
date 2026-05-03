/*
 * Copyright © 2026 Soubhagyajit Borah
 * License: GNU General Public License v3.0
 */

const { spawn, exec } = require('child_process');
const { ipcMain, app } = require('electron');
const path = require('path');
const fs = require('fs');

let senderProcess = null;
let isVirtualCameraRunning = false;

/**
 * Automatically detects the resolution of the incoming stream.
 */
async function getStreamResolution(url) {
    return new Promise((resolve) => {
        const cmd = `ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=s=x:p=0 "${url}"`;
        exec(cmd, (error, stdout) => {
            if (error || !stdout) {
                console.warn("[V-Cam] Resolution detection failed, falling back to 1280x720.");
                resolve({ width: 1280, height: 720 });
            } else {
                const [w, h] = stdout.trim().split('x').map(Number);
                console.log(`[V-Cam] Detected Stream Resolution: ${w}x${h}`);
                resolve({ width: w, height: h });
            }
        });
    });
}

async function startVirtualCamera(streamUrl, userWidth, userHeight, fps = 30) {
    if (isVirtualCameraRunning) return { success: false, message: 'Already running' };

    try {
        const isDev = !app.isPackaged;
        const baseDir = isDev ? __dirname : process.resourcesPath;
        const senderPath = path.join(baseDir, 'sender.exe');

        // Logic: Use user-provided resolution, otherwise auto-detect
        const detected = await getStreamResolution(streamUrl);
        const width = userWidth || detected.width;
        const height = userHeight || detected.height;

        // Spawn sender.exe — it handles stream capture + softcam feeding internally
        senderProcess = spawn(senderPath, [
            width.toString(),
            height.toString(),
            fps.toString()
        ], {
            cwd: baseDir,
            env: { ...process.env, PATH: `${baseDir};${process.env.PATH}` },
            stdio: ['ignore', 'pipe', 'pipe']
        });

        senderProcess.stdout.on('data', (data) => console.log(`[C++ Bridge] ${data}`));
        senderProcess.stderr.on('data', (data) => console.error(`[C++ Bridge] ${data}`));

        senderProcess.on('close', (code) => {
            console.log(`[V-Cam] sender.exe exited (Code: ${code})`);
            stopVirtualCamera();
        });

        senderProcess.on('error', (err) => {
            console.error(`[V-Cam] Failed to start sender.exe: ${err.message}`);
            stopVirtualCamera();
        });

        isVirtualCameraRunning = true;
        return { success: true, resolution: `${width}x${height}` };

    } catch (error) {
        return { success: false, message: error.message };
    }
}

function stopVirtualCamera() {
    let closed = false;
    if (senderProcess) {
        // 'taskkill /t' ensures any child processes spawned by sender.exe are also killed
        spawn('taskkill', ['/pid', senderProcess.pid, '/f', '/t'], { shell: true });
        senderProcess = null;
        closed = true;
    }
    isVirtualCameraRunning = false;
    return { success: closed };
}

async function checkVirtualCamera() {
    const isDev = !app.isPackaged;
    const baseDir = isDev ? __dirname : process.resourcesPath;
    const senderPath = path.join(baseDir, 'sender.exe');
    const dllPath = path.join(baseDir, 'softcam.dll');

    const senderExists = fs.existsSync(senderPath);
    const dllExists = fs.existsSync(dllPath);

    if (senderExists && dllExists) {
        return { installed: true, message: 'C++ Bridge Ready' };
    } else {
        return {
            installed: false,
            message: `Missing: ${!senderExists ? 'sender.exe ' : ''}${!dllExists ? 'softcam.dll' : ''}`
        };
    }
}

function setupVirtualCameraIPC() {
    ipcMain.on('check-virtual-camera', async (event) => {
        const result = await checkVirtualCamera();
        event.reply('virtual-camera-check-result', result);
    });

    ipcMain.on('start-virtual-camera', async (event, streamUrl, width, height, fps) => {
        const result = await startVirtualCamera(streamUrl, width, height, fps);

        event.reply('virtual-camera-status', {
            success: result.success,
            message: result.message || '',
            resolution: result.resolution || ''
        });
    });

    ipcMain.on('stop-virtual-camera', (event) => {
        const result = stopVirtualCamera();
        event.reply('virtual-camera-status', result);
    });

    ipcMain.on('get-virtual-camera-status', (event) => {
        event.reply('virtual-camera-running', isVirtualCameraRunning);
    });
}

function cleanupVirtualCamera() {
    stopVirtualCamera();
}

module.exports = {
    setupVirtualCameraIPC,
    cleanupVirtualCamera,
    startVirtualCamera,
    stopVirtualCamera,
    checkVirtualCamera
};