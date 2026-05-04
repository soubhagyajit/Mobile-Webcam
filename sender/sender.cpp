#include <iostream>
#include <string>
#include <thread>
#include <atomic>
#include <mutex>
#include <opencv2/opencv.hpp>
#include </Project Files/Website Projects/Mobile-Webcam/softcam-1.8.1/softcam-1.8.1/src/softcam/softcam.h>

// --- Shared state between capture and send threads ---
std::mutex frameMutex;
cv::Mat latestFrame;         // Always holds the most recent frame
std::atomic<bool> newFrameAvailable(false);
std::atomic<bool> running(true);

// --- Thread 1: Capture — runs as fast as the stream allows ---
void captureThread(const std::string& streamUrl) {
    // CAP_ANY lets OpenCV pick the best backend for MJPEG over HTTP
    cv::VideoCapture cap(streamUrl, cv::CAP_ANY);

    if (!cap.isOpened()) {
        std::cerr << "[C++ Bridge] Error: Could not open stream at " << streamUrl << std::endl;
        running = false;
        return;
    }

    // Key: keep the internal queue as small as possible
    cap.set(cv::CAP_PROP_BUFFERSIZE, 1);

    cv::Mat frame;
    while (running) {
        bool success = cap.read(frame);
        if (!success || frame.empty()) {
            std::cerr << "[C++ Bridge] Stream ended or frame dropped." << std::endl;
            running = false;
            break;
        }

        // Overwrite latestFrame — old unprocessed frames are simply discarded
        // This is the core fix: we never queue up stale frames
        {
            std::lock_guard<std::mutex> lock(frameMutex);
            latestFrame = frame.clone();
            newFrameAvailable = true;
        }
    }

    cap.release();
}

// --- Thread 2: Send — runs at the virtual cam's FPS ---
void sendThread(scCamera cam, int width, int height, int fps) {
    const int frameTimeMs = 1000 / fps;
    cv::Mat frame;
    cv::Mat resizedFrame;

    while (running) {
        auto frameStart = std::chrono::steady_clock::now();

        bool hasFrame = false;
        {
            std::lock_guard<std::mutex> lock(frameMutex);
            if (newFrameAvailable) {
                frame = latestFrame.clone();
                newFrameAvailable = false;
                hasFrame = true;
            }
        }

        if (hasFrame) {
            if (frame.cols != width || frame.rows != height) {
                cv::resize(frame, resizedFrame, cv::Size(width, height), 0, 0, cv::INTER_NEAREST);
                scSendFrame(cam, resizedFrame.data);
            }
            else {
                scSendFrame(cam, frame.data);
            }
        }

        // Sleep for the remainder of the frame budget to avoid busy-spinning
        auto elapsed = std::chrono::steady_clock::now() - frameStart;
        auto sleepMs = frameTimeMs - std::chrono::duration_cast<std::chrono::milliseconds>(elapsed).count();
        if (sleepMs > 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(sleepMs));
        }
    }
}

int main(int argc, char* argv[]) {
    int width = (argc > 1) ? std::stoi(argv[1]) : 1280;
    int height = (argc > 2) ? std::stoi(argv[2]) : 720;
    int fps = (argc > 3) ? std::stoi(argv[3]) : 30;
    std::string streamUrl = "http://localhost:8080/video";

    scCamera cam = scCreateCamera(width, height, fps);
    if (!cam) {
        std::cerr << "[C++ Bridge] Failed to create softcam camera." << std::endl;
        return 1;
    }
    std::cout << "[C++ Bridge] Virtual Camera active at " << width << "x" << height << "@" << fps << "fps" << std::endl;

    // Launch capture and send on separate threads
    std::thread capture(captureThread, streamUrl);
    std::thread sender(sendThread, cam, width, height, fps);

    capture.join();
    running = false;
    sender.join();

    scDeleteCamera(cam);
    std::cout << "[C++ Bridge] Shutting down." << std::endl;
    return 0;
}