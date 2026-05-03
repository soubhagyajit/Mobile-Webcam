#include <iostream>
#include <vector>
#include <windows.h>
#include <fcntl.h>
#include <io.h>

// Typedefs for the DLL functions
typedef void* (*CreateCam)(int, int, float);
typedef void (*SendFrame)(void*, void*);
typedef void (*DeleteCam)(void*); // Good practice to include if available

int main(int argc, char* argv[]) {
    // 1. Validate Arguments
    if (argc < 4) {
        std::cerr << "Usage: bridge.exe <width> <height> <fps>" << std::endl;
        return 1;
    }

    // 2. Set Binary Mode
    // This is critical. Without this, Windows converts 0x0A to 0x0D 0x0A, 
    // corrupting your raw video bytes.
    if (_setmode(_fileno(stdin), _O_BINARY) == -1) {
        std::cerr << "Error: Could not set stdin to binary mode" << std::endl;
        return 1;
    }

    int w = atoi(argv[1]);
    int h = atoi(argv[2]);
    float fps = (float)atof(argv[3]);

    // 3. Load DLL
    // Ensure softcam.dll is in the same directory as bridge.exe
    HMODULE dll = LoadLibraryA("softcam.dll");
    if (!dll) {
        DWORD err = GetLastError();
        std::cerr << "Error: Could not find softcam.dll (System Error: " << err << ")" << std::endl;
        return 1;
    }

    // 4. Load Functions
    auto scCreate = (CreateCam)GetProcAddress(dll, "scCreateCamera");
    auto scSend = (SendFrame)GetProcAddress(dll, "scSendFrame");
    auto scDelete = (DeleteCam)GetProcAddress(dll, "scDeleteCamera"); // Usually exists in softcam

    if (!scCreate || !scSend) {
        std::cerr << "Error: Could not find functions in softcam.dll" << std::endl;
        FreeLibrary(dll);
        return 1;
    }

    // 5. Create Camera Instance
    void* cam = scCreate(w, h, fps);
    if (!cam) {
        std::cerr << "Error: scCreateCamera returned NULL. " 
                  << "Check if the virtual camera driver is installed correctly." << std::endl;
        FreeLibrary(dll);
        return 1;
    }

    // 6. Processing Loop
    const size_t frameSize = (size_t)w * h * 3;
    std::vector<char> buffer(frameSize);

    // Use gcount to ensure we only send data when a full frame is read
    while (std::cin) {
        std::cin.read(buffer.data(), frameSize);
        std::streamsize bytesRead = std::cin.gcount();

        if (bytesRead == (std::streamsize)frameSize) {
            // Check pointer one last time before sending to prevent Access Violation
            if (cam) {
                scSend(cam, buffer.data());
            }
        } else if (bytesRead > 0) {
            // We got a partial frame, likely the pipe is closing or data is lagging
            continue; 
        }
    }

    // 7. Cleanup
    if (scDelete && cam) {
        scDelete(cam);
    }
    FreeLibrary(dll);
    
    return 0;
}