import "../App.css";
import { getVersion } from '@tauri-apps/api/app';
import { getCurrentWindow } from "@tauri-apps/api/window";
import { useEffect, useRef, useState } from "react";

function Home() {
    const [appVersion, setAppVersion] = useState('');
    const [mode, setMode] = useState('usb');
    const [serverIP, setServerIP] = useState('localhost');
    const [serverPort, setServerPort] = useState('8080');
    const [serverURL, setServerURL] = useState(`http://${serverIP}:${serverPort}`);
    const [toast, setToast] = useState({ message: '', isError: false, visible: false })
    const [status, setStatus] = useState({ message: 'Ready to connect.', state: 'idle' });
    const [isConnected, setIsConnected] = useState(false);


    const [connectButtonText, setConnectButtonText] = useState('Connect');
    const [connectButtonDisable, setConnectButtonDisable] = useState(false);


    const [showControls, setShowControls] = useState(false)
    const [virtualCamActive, setVirtualCamActive] = useState(false)
    const [modeIndicator, setModeIndicator] = useState('standby') // 'standby' | 'streaming'

    const [deviceFeatures, setDeviceFeatures] = useState(null)
    const [resolutions, setResolutions] = useState([])
    const [isFrontCamera, setIsFrontCamera] = useState(false)
    const [manualFocus, setManualFocus] = useState(false)
    const [focusMode, setFocusMode] = useState('0') // 0: AUTO, 2: MANUAL
    const [manualFocusValue, setManualFocusValue] = useState(0)
    const [exposure, setExposure] = useState({ value: 0, min: 0, max: 0, disabled: true })

    const [deviceSettings, setDeviceSettings] = useState(null)

    const videoRef = useRef(null);
    const syncSettingsInterval = useRef(null)
    const syncFeaturesInterval = useRef(null)

    const toastDiv = document.getElementById('toast');

    useEffect(() => {
        getVersion().then((v) => {
            setAppVersion(v)
            getCurrentWindow().setTitle(`AWC v${v}`)
        })
    }, [])

    useEffect(() => {
        const urlInput = document.getElementById('serverURL');
        if (mode === 'usb') {
            urlInput.classList.replace('flex', 'hidden');
            setServerIP('localhost');
            setServerPort('8080');
        }
        else if (mode === 'wifi') {
            urlInput.classList.replace('hidden', 'flex');
            setServerIP('192.168.31.12');
            setServerPort('8080');
        }
    }, [mode])

    useEffect(() => {
        setServerURL(`http://${serverIP}:${serverPort}`);
    }, [serverIP, serverPort])

    useEffect(() => {
        if (manualFocus == true) {
            setFocusMode('2');
        }
        else if (manualFocus == false) {
            setFocusMode('0');
        }
        else {
            setFocusMode('0');
        }
    }, [manualFocus])

    useEffect(() => {
        // console.log(deviceSettings)
        if (!isConnected) return;
        console.log("Entered");
        setManualFocusValue(deviceSettings.focus_distance);
        setFocusMode(deviceSettings.focus_mode.toString());
        setExposure(e => ({...e, value: deviceSettings.exposure_index}))
    }, [deviceSettings])
    const showToast = (message, isError = false) => {
        setToast({ message, isError, visible: true })
        setTimeout(() => setToast(t => ({ ...t, visible: false })), 3000)
    }

    const updateStatus = (message, state = 'idle') => {
        setStatus({ message, state })
    }

    const handleToggle = () => {
        if (isConnected) {
            handleDisconnect()
        }
        else {
            handleConnect()
        }
    }

    const handleConnect = () => {
        console.log("Connect button pressed");
        const url = serverURL;
        console.log("URL:", url);
        if (!url) return showToast('Enter a URL first', true);
        if (url) showToast('Entered URL: ' + url, true);

        updateStatus('Connecting...', 'idle');
        setConnectButtonDisable(true);
        const video = videoRef.current
        video.src = serverURL + '/video' + '?t=' + Date.now()

        setConnectButtonText('Connecting...')
        //   toggleConnectBtn.disabled = true;

        video.onload = async () => {
            setIsConnected(true)

            setShowControls(true)

            setConnectButtonDisable(false)
            setConnectButtonText('Disconnect')

            setModeIndicator('streaming')
            updateStatus('Feed Live', 'active')
            await initializeDeviceSync()
        }

        video.onerror = () => {
            updateStatus('Connection failed', 'error')
            handleDisconnect()
        }
    }

    const handleDisconnect = () => {
        setIsConnected(false);
        const video = videoRef.current;
        video.src = '';
        setIsConnected(false);
        setConnectButtonDisable(false)
        setConnectButtonText('Connect');
        setShowControls(false);
        // popoutBtn.disabled = true;
        // virtualCamBtn.disabled = true;
        // serverUrlInput.disabled = false;

        // cameraControls.classList.add('hidden');

        if (syncSettingsInterval.current || syncFeaturesInterval.current) {
            clearInterval(syncSettingsInterval.current)
            clearInterval(syncFeaturesInterval.current)
            syncSettingsInterval.current = null
            syncFeaturesInterval.current = null
            console.log('Device sync stopped');
        }

        updateStatus('Disconnected', 'idle');
        // modeIndicator.textContent = 'Standby';
        // modeIndicator.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-slate-700 text-slate-300 uppercase';
        // updateStatus('Disconnected', 'idle');

        // if (virtualCamActive) ipcRenderer.send('stop-virtual-camera');
        // ipcRenderer.send('close-feed-window');
    }

    const initializeDeviceSync = async () => {
        await fetchFeatures()
        await fetchSettings()
        syncSettingsInterval.current = setInterval(fetchSettings, 3000)
        syncFeaturesInterval.current = setInterval(fetchFeatures, 3000)
    }

    const fetchFeatures = async () => {
        const base = serverURL;
        if (!base) return

        try {
            const response = await fetch(`${base}/features`)
            if (!response.ok) throw new Error('Failed to fetch features')

            const features = await response.json()
            setDeviceFeatures(features)
            setResolutions(features.resolutions)
            setIsFrontCamera(features.camera === 'front')
            setManualFocus(features.manual_focus)
            if (features.exposure_lower !== undefined && features.exposure_upper !== undefined) {
                setExposure(e => ({ ...e, min: features.exposure_lower, max: features.exposure_upper, disabled: features.exposure_lower === features.exposure_upper }))
            }
        } catch (err) {
            console.error('Failed to fetch features:', err)
            showToast('Could not load device features', true)
        }
    }

    const fetchSettings = async () => {
        const base = serverURL;
        if (!base) return

        try {
            const response = await fetch(`${base}/settings`)
            if (!response.ok) throw new Error('Failed to fetch settings')

            const settings = await response.json()
            setDeviceSettings(settings)
            // console.log(settings)
        } catch (err) {
            console.error('Failed to fetch settings:', err)
        }
    }

    const sendControl = async (query, type = 'Command Send') => {
        const base = serverURL;
        if (!base) return;
        try {
            const response = await fetch(`${base}/control?${query}`);
            if (response.ok) {
                showToast(type);
                setTimeout(fetchSettings, 2000);
                setTimeout(fetchFeatures, 2000);
            }
            console.log(type, query);
        } catch (err) {
            console.error('Control failed', err);
            showToast('Command failed', true);
        }
    }

    const handleFlip = () => {
        const newValue = !isFrontCamera;
        setIsFrontCamera(newValue);
        sendControl(`flip=${newValue}`);
    }
    return (<>
        <div className="flex h-screen bg-slate-950 text-slate-200">
            {/* Sidebar */}
            <aside className="w-80 glass border-r border-slate-800 p-6 flex flex-col gap-6 z-10 overflow-y-auto">
                <div className="flex items-center gap-3">
                    <div className="w-10 h-10 bg-brand-500 rounded-xl flex items-center justify-center shadow-lg shadow-brand-500/20">
                        <svg className="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z" /></svg>
                    </div>
                    <h1 className="text-xl font-bold tracking-tight text-white">AWC <span className="text-[10px] font-medium px-2 py-0.5 bg-slate-800 rounded-full text-slate-400 align-middle ml-1">v{appVersion}</span></h1>
                </div>

                <hr className="border-slate-800" />

                <div className="space-y-4">
                    <div className="space-y-2">
                        <label className="text-xs font-semibold text-slate-500 uppercase tracking-wider">Select Mode (USB or WIFI):</label>
                        <select value={mode} onChange={(e) => { setMode(e.target.value) }} id="connectionMode" className="w-full bg-slate-800/50 border border-slate-700 rounded-lg p-2 text-sm focus:ring-2 focus:ring-brand-500 outline-none transition-all">
                            <option className="bg-white" value="usb">USB</option>
                            <option className="" value="wifi">WIFI</option>
                        </select>
                        <div className="gap-2 hidden" id="serverURL">
                            <input
                                type="text"
                                id="serverIP"
                                value={serverIP}
                                onChange={(e) => setServerIP(e.target.value)}
                                placeholder="IP"
                                className="w-full bg-slate-800/50 border border-slate-700 rounded-lg px-4 py-2.5 text-sm focus:ring-2 focus:ring-brand-500 outline-none transition-all"
                            />
                            <input
                                type="text"
                                value={serverPort}
                                id="serverPort"
                                onChange={(e) => setServerPort(e.target.value)}
                                placeholder="Port"
                                className="w-20 bg-slate-800/50 border border-slate-700 rounded-lg px-4 py-2.5 text-sm focus:ring-2 focus:ring-brand-500 outline-none transition-all"
                            />
                        </div>
                    </div>

                    <div className="grid grid-cols-1 gap-2">
                        <button onClick={handleToggle} id="toggleConnectBtn" disabled={connectButtonDisable} className={`w-full ${connectButtonDisable ? 'bg-slate-500 hover:bg-slate-600 cursor-not-allowed text-white' : (isConnected ? 'bg-red-500/10 hover:bg-red-500/20 border border-red-500/20 text-red-400 cursor-pointer' : 'bg-brand-500 hover:bg-brand-600 text-white cursor-pointer')} font-semibold py-2.5 rounded-lg transition-all active:scale-95 flex items-center justify-center gap-2`}>
                            <span id="toggleConnectBtnText">{connectButtonText}</span>
                        </button>
                    </div>
                </div>

                {showControls && (
                    <div className="space-y-4 pt-2 border-t border-slate-800">
                        <div className="flex items-center justify-between">
                            <label className="text-xs font-semibold text-slate-500 uppercase tracking-wider">Camera Controls</label>
                            <button onClick={fetchSettings} className="text-xs text-brand-400 hover:text-brand-300 transition-colors flex items-center gap-1">
                                <svg className="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" /></svg>
                                Sync
                            </button>
                        </div>

                        <button onClick={handleFlip} className="w-full border border-slate-700 hover:border-brand-500/50 hover:bg-brand-500/10 text-slate-300 py-2 rounded-lg text-sm transition-all flex items-center justify-center gap-2">
                            {isFrontCamera ? 'Switch to Back' : 'Switch to Front'}
                        </button>

                        <div className="space-y-1.5">
                            <div className="flex justify-between items-center">
                                <span className="text-[10px] text-slate-500 font-bold uppercase">Resolution</span>
                                <span className="text-[9px] text-brand-400 font-mono">{deviceSettings?.resolution_str || 'Loading...'}</span>
                            </div>
                            <select
                                value={deviceSettings?.resolution_str || ''}
                                onChange={(e) => sendControl(`resolution_str=${encodeURIComponent(e.target.value)}`, `Resolution: ${e.target.value}`)}
                                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-2 py-1.5 text-xs text-slate-300 outline-none focus:ring-1 focus:ring-brand-500">
                                {resolutions.map(res => <option key={res} value={res}>{res}</option>)}
                            </select>
                        </div>

                        <div className="space-y-2">
                            <div className="flex justify-between items-center">
                                <span className="text-[10px] text-slate-500 font-bold uppercase">Focus Mode</span>
                                <select
                                    value={focusMode}
                                    onChange={(e) => { setFocusMode(e.target.value); sendControl(`focus_mode=${e.target.value}`, `Focus Mode: ${e.target.value === '0' ? "AUTO" : "MANUAL"}`); console.log(focusMode) }}
                                    className="bg-transparent text-[10px] text-brand-400 font-bold outline-none cursor-pointer">
                                    <option value="0" className="bg-slate-900">AUTO</option>
                                    <option value="2" className="bg-slate-900" disabled={!manualFocus}>MANUAL</option>
                                </select>
                            </div>
                            <p className={`text-[9px] ${manualFocus ? 'text-emerald-400' : 'text-red-400'}`}>
                                {manualFocus ? 'Manual focus supported' : 'Fixed focus camera'}
                            </p>
                            {focusMode === '2' && (
                                <div className="space-y-1">
                                    <div className="flex justify-between items-center mb-1">
                                        <span className="text-[9px] text-slate-500">Distance</span>
                                        <span className="text-[9px] text-brand-400 font-mono">{manualFocusValue}</span>
                                    </div>
                                    <input type="range" min="0" max="1000"
                                        value={manualFocusValue}
                                        onChange={(e) => setManualFocusValue(e.target.value)}
                                        onMouseUp={(e) => sendControl(`focus_mode=2&focus_distance=${e.target.value}`, `Focus Distance: ${e.target.value}`)}
                                        onTouchEnd={(e) => sendControl(`focus_mode=2&focus_distance=${e.target.value}`, `Focus Distance: ${e.target.value}`)}
                                        className="w-full h-1.5 bg-slate-700 rounded-lg appearance-none cursor-pointer accent-brand-500" />
                                    <div className="flex justify-between text-[8px] text-slate-500 uppercase">
                                        <span>Infinity</span>
                                        <span>Macro</span>
                                    </div>
                                </div>
                            )}
                        </div>

                        <div className="space-y-2 pt-2 border-t border-slate-800/50">
                            <div className="flex justify-between items-center mb-1">
                                <span className="text-[10px] text-slate-500 font-bold uppercase">Exposure</span>
                                <span className="text-[9px] text-brand-400 font-mono">{exposure.value}</span>
                            </div>
                            <input type="range"
                                min={exposure.min} max={exposure.max}
                                value={exposure.value}
                                disabled={exposure.disabled}
                                onChange={(e) => setExposure(exposure => ({ ...exposure, value: e.target.value }))}
                                onMouseUp={(e) => sendControl(`exposure_index=${e.target.value}`, `Exposure: ${e.target.value}`)}
                                onTouchEnd={(e) => sendControl(`exposure_index=${e.target.value}`)}
                                className="w-full h-1.5 bg-slate-700 rounded-lg appearance-none cursor-pointer accent-brand-500 disabled:opacity-30 disabled:cursor-not-allowed" />
                            <div className="flex justify-between text-[8px] text-slate-500 uppercase">
                                <span>Darker</span>
                                <span>Brighter</span>
                            </div>
                        </div>
                    </div>
                )}

                <div className="space-y-3 pt-2 border-t border-slate-800">
                    <label className="text-xs font-semibold text-slate-500 uppercase tracking-wider">Tools</label>
                    <button id="virtualCamBtn" disabled className="w-full border border-slate-700 hover:border-emerald-500/50 hover:bg-emerald-500/10 text-slate-300 py-2 rounded-lg text-sm transition-all flex items-center justify-center gap-2 disabled:opacity-30">
                        Virtual Camera
                    </button>
                    <button id="popoutBtn" disabled className="w-full border border-slate-700 hover:border-brand-500/50 hover:bg-brand-500/10 text-slate-300 py-2 rounded-lg text-sm transition-all disabled:opacity-30">
                        Pop-out Feed
                    </button>
                </div>

                <div className="mt-auto p-4 bg-slate-900/80 rounded-xl border border-slate-800/50 shrink-0">
                    <div className="flex items-center justify-between mb-2">
                        <span className="text-xs text-slate-500">Status</span>
                        <span id="modeIndicator" className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-slate-700 text-slate-300 uppercase">{status.state}</span>
                    </div>
                    <div id="status" className={`text-sm font-medium italic ${status.state === 'error' ? 'text-red-400' : ''} ${status.state === 'active' ? 'text-emerald-400' : ''} ${status.state === 'idle' ? 'text-brand-500' : ''}`}>{status.message}</div>
                </div>
            </aside>

            {/* Main */}
            <main className="flex-1 relative flex flex-col bg-[#020617]">
                <div className="absolute top-0 left-0 w-full h-full overflow-hidden pointer-events-none">
                    <div className="absolute -top-24 -right-24 w-96 h-96 bg-brand-600/10 rounded-full blur-[120px]"></div>
                    <div className="absolute -bottom-24 -left-24 w-96 h-96 bg-emerald-600/5 rounded-full blur-[120px]"></div>
                </div>

                <div className="flex-1 flex items-center justify-center p-8">
                    <div id="placeholder" className={`${isConnected ? 'hidden' : 'block'} max-w-md text-center space-y-6`}>
                        <div className="w-20 h-20 bg-slate-800/50 rounded-3xl mx-auto flex items-center justify-center border border-slate-700">
                            <svg className="w-10 h-10 text-slate-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 4v1m6 11h2m-6 0h-2v4m0-11v3m0 0h.01M12 12h4.01M16 20h4M4 12h4m12 0h.01M5 8h2a1 1 0 001-1V5a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1zm12 0h2a1 1 0 001-1V5a1 1 0 00-1-1h-2a1 1 0 00-1 1v2a1 1 0 001 1zM5 20h2a1 1 0 001-1v-2a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1z" /></svg>
                        </div>
                        <div>
                            <h2 className="text-2xl font-bold text-white mb-2">Connect Your Stream</h2>
                            <p className="text-slate-400 text-sm leading-relaxed">Toggle your phone's webcam server and enter the URL in the sidebar.</p>
                        </div>
                    </div>

                    <div id="videoStreamDiv" className={`${isConnected ? 'block' : 'hidden'} relative w-full h-full flex items-center justify-center`}>
                        <img ref={videoRef} id="videoStream" className="max-w-full max-h-full rounded-2xl border border-slate-700/50 video-glow object-contain transition-all duration-500" alt="Stream" src={serverURL} />
                    </div>
                </div>

                <div id="toast" className={`absolute bottom-6 right-6 px-6 py-3 rounded-xl glass border-brand-500/30 text-sm font-medium opacity-0 transition-all duration-300 pointer-events-none ${toast.visible ? 'translate-y-0 opacity-100' : 'translate-y-20 opacity-0'} ${toast.isError ? 'border-red-500/30' : 'border-brand-500/30'}`}>{toast.message}</div>
            </main>
        </div>
    </>
    );
}
export default Home;