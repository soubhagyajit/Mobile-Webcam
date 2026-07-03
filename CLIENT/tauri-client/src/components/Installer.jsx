import { getCurrentWebview } from "@tauri-apps/api/webview";
import { getCurrentWindow, Window } from "@tauri-apps/api/window";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { useEffect, useState } from "react";

function Installer() {
  const [progressMsg, setProgressMsg] = useState("Starting...");
  const [progressPct, setProgressPct] = useState(0);

  const [closeTimeout, setCloseTimeout] = useState(1500);

  const handleClose = async () => {
    await getCurrentWindow().close();
  };
  const addLog = (message, isError = false) => {
    const entry = document.createElement("div");
    const time = new Date().toLocaleTimeString([], {
      hour12: false,
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
    entry.className = isError ? "text-red-400" : "";
    entry.className.replace("hidden", " ");
    entry.innerHTML = `<span class="text-slate-600">[${time}]</span> ${message}`;
    log.appendChild(entry);
    log.scrollTop = log.scrollHeight;
  };

  const updateProgress = (message, percent) => {
    addLog(message);
    setProgressMsg(message);
    setProgressPct(percent);
  };

  const startAutoInstall = async () => {
    await invoke("init_installer");
  };

  useEffect(() => {
    setTimeout(() => {
      startAutoInstall();
    }, 1000);
  }, []);

  useEffect(() => {
    const unlisten = listen("installer-progress", (e) => {
      updateProgress(e.payload.message, e.payload.percent);
      console.log(e.payload.message);
      console.log(e.payload.percent);
    });
    return () => {
      unlisten.then((f) => f());
    };
  }, []);

  useEffect(() => {
    const unlisten = listen("close-installer", (e) => {
      console.log(e.payload);
      if (e.payload) {
        setTimeout(() => { handleClose()},closeTimeout)
      }
    });
    return () => {
      unlisten.then((f) => f());
    };
  }, []);

  return (
    <>
      <div className="fixed inset-0 bg-slate-950 text-slate-200 antialiased flex items-center justify-center overflow-hidden z-50">
        <div className="absolute inset-0 overflow-hidden pointer-events-none">
          <div className="absolute -top-24 -left-24 w-96 h-96 bg-brand-600/10 rounded-full blur-[100px]"></div>
          <div className="absolute -bottom-24 -right-24 w-96 h-96 bg-emerald-600/10 rounded-full blur-[100px]"></div>
        </div>

        <div className="container relative z-10 w-112.5 p-8 glass-panel rounded-4xl">
          <div id="headerArea" className="text-center mb-8">
            <h1 className="text-2xl font-bold text-white mb-2 tracking-tight">
              Setting Up Components
            </h1>
            <p className="text-slate-400 text-sm">
              Preparing your virtual camera drivers...
            </p>
          </div>

          {/* <div className="flex flex-col items-center justify-center mb-8 h-24">
                        <div id="spinner" className="custom-loader"></div>

                        <div id="successIcon" className="hidden scale-0 transition-transform duration-500">
                            <div className="w-16 h-16 bg-emerald-500/20 rounded-full flex items-center justify-center border border-emerald-500/50">
                                <svg className="w-8 h-8 text-emerald-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="3" d="M5 13l4 4L19 7"></path></svg>
                            </div>
                        </div>

                        <div id="errorIcon" className="hidden scale-0 transition-transform duration-500">
                            <div className="w-16 h-16 bg-red-500/20 rounded-full flex items-center justify-center border border-red-500/50">
                                <svg className="w-8 h-8 text-red-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="3" d="M6 18L18 6M6 6l12 12"></path></svg>
                            </div>
                        </div>
                    </div> */}

          <div
            id="logWrapper"
            className="mb-6 overflow-hidden transition-all duration-500"
          >
            <div
              id="log"
              className="bg-black/40 border border-slate-800 rounded-xl p-4 h-64 overflow-y-auto font-mono text-[10px] text-slate-500 space-y-1"
            ></div>
          </div>

          <div className="space-y-4">
            <div className="flex items-center">
              <div className="w-full bg-slate-800 rounded-full h-2.5 overflow-hidden">
                <div
                  id="progressBar"
                  className={`bg-linear-to-r from-brand-500 to-emerald-500 h-full transition-all duration-500 ease-out`}
                  style={{ width: `${progressPct}%` }}
                ></div>
              </div>
              <div className="font-mono text-sm mx-2 text-slate-500">
                {progressPct}%
              </div>
            </div>

            <div
              id="status"
              className="text-center text-sm font-medium text-slate-300"
            >
              {progressMsg}
            </div>
          </div>
          <button
            onClick={handleClose}
            id="closeButton"
            className="w-full mt-6 py-3 bg-slate-800 hover:bg-slate-700 text-white font-semibold rounded-xl transition-all active:scale-95"
          >
            Close Installer
          </button>
        </div>
      </div>
    </>
  );
}

export default Installer;
