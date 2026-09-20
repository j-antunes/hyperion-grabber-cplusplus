#pragma once

// Windows display power monitor, shared by the C++ PC grabber and the
// desktop JVM app's JNI helper. This is the Windows counterpart of Android's
// SCREEN_OFF/SCREEN_ON receiver and the X11 DPMS check: while the console
// display is off the grabbers pause capture and drop the TCP connection so
// Hyperion releases the priority, then reconnect when it comes back.
//
// Windows only delivers GUID_CONSOLE_DISPLAY_STATE through WM_POWERBROADCAST
// to a window, so this spins up a hidden top-level window on its own thread
// and mirrors the state into an atomic flag that capture threads poll.

#ifdef _WIN32

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif

#include <windows.h>
#include <atomic>
#include <cstring>
#include <thread>

namespace hyperion {
namespace win {

class DisplayPowerMonitor {
public:
    DisplayPowerMonitor() {
        m_thread = std::thread(&DisplayPowerMonitor::run, this);
        // Wait until the window exists (or creation failed) so the
        // destructor can always reach it.
        while (!m_started.load()) std::this_thread::yield();
    }

    DisplayPowerMonitor(const DisplayPowerMonitor&) = delete;
    DisplayPowerMonitor& operator=(const DisplayPowerMonitor&) = delete;

    ~DisplayPowerMonitor() {
        HWND h = m_hwnd.load();
        if (h) PostMessageW(h, WM_CLOSE, 0, 0);
        if (m_thread.joinable()) m_thread.join();
    }

    // True until Windows tells us otherwise (0 = off; 1 = on; 2 = dimmed,
    // treated as on because the screen is still readable).
    bool isDisplayOn() const { return m_on.load(); }

private:
    // GUID_CONSOLE_DISPLAY_STATE {6FE69556-704A-47A0-8F24-C28D936FDA47}.
    // Defined locally so we don't need INITGUID/uuid.lib in every TU.
    static const GUID& consoleDisplayState() {
        static const GUID g = { 0x6fe69556, 0x704a, 0x47a0,
                                { 0x8f, 0x24, 0xc2, 0x8d, 0x93, 0x6f, 0xda, 0x47 } };
        return g;
    }

    void run() {
        HINSTANCE inst = GetModuleHandleW(nullptr);
        const wchar_t* cls = L"HyperionGrabberDisplayPower";

        WNDCLASSEXW wc{};
        wc.cbSize        = sizeof(wc);
        wc.lpfnWndProc   = &DisplayPowerMonitor::wndProc;
        wc.hInstance     = inst;
        wc.lpszClassName = cls;
        if (!RegisterClassExW(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
            m_started = true;
            return;  // no monitor: display is reported as always on
        }

        // A hidden (never shown) top-level window, not HWND_MESSAGE: it must
        // be a real window to receive power broadcasts reliably.
        HWND hwnd = CreateWindowExW(0, cls, L"", WS_OVERLAPPED, 0, 0, 0, 0,
                                    nullptr, nullptr, inst, this);
        if (!hwnd) { m_started = true; return; }
        m_hwnd = hwnd;

        HPOWERNOTIFY notify = RegisterPowerSettingNotification(
            hwnd, &consoleDisplayState(), DEVICE_NOTIFY_WINDOW_HANDLE);
        m_started = true;

        MSG msg;
        while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
        if (notify) UnregisterPowerSettingNotification(notify);
        m_hwnd = nullptr;
    }

    static LRESULT CALLBACK wndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
        if (msg == WM_CREATE) {
            auto* cs = reinterpret_cast<CREATESTRUCTW*>(lParam);
            SetWindowLongPtrW(hwnd, GWLP_USERDATA,
                              reinterpret_cast<LONG_PTR>(cs->lpCreateParams));
            return 0;
        }
        auto* self = reinterpret_cast<DisplayPowerMonitor*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));

        if (msg == WM_POWERBROADCAST && wParam == PBT_POWERSETTINGCHANGE && self) {
            auto* s = reinterpret_cast<POWERBROADCAST_SETTING*>(lParam);
            if (s && IsEqualGUID(s->PowerSetting, consoleDisplayState()) &&
                s->DataLength >= sizeof(DWORD)) {
                DWORD state = 0;
                memcpy(&state, s->Data, sizeof(state));
                self->m_on = (state != 0);
            }
            return TRUE;
        }
        if (msg == WM_CLOSE)   { DestroyWindow(hwnd); return 0; }
        if (msg == WM_DESTROY) { PostQuitMessage(0);  return 0; }
        return DefWindowProcW(hwnd, msg, wParam, lParam);
    }

    std::thread        m_thread;
    std::atomic<HWND>  m_hwnd{nullptr};
    std::atomic<bool>  m_started{false};
    std::atomic<bool>  m_on{true};
};

} // namespace win
} // namespace hyperion

#endif // _WIN32
