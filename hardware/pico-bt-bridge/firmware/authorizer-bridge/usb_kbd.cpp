// SPDX-License-Identifier: MIT
#include "usb_kbd.h"
#include <USB.h>
#include "tusb.h"
#include "class/hid/hid_device.h"

// Keyboard_ keeps sendReport() protected and silently drops a report when the
// endpoint is busy. The phone sends press and release back to back, so wait
// (briefly) for the endpoint instead of losing the release.
class BridgeKeyboard : public Keyboard_ {
public:
  bool sendRaw(const KeyReport *r) {
    if (!_running) {
      return false;
    }
    CoreMutex m(&USB.mutex);
    for (int i = 0; i < 40 && !USB.HIDReady(); i++) {   // up to ~20 ms
      tud_task();
      delayMicroseconds(500);
    }
    bool ok = false;
    if (USB.HIDReady()) {
      ok = tud_hid_keyboard_report(USB.findHIDReportID(_id), r->modifiers, (uint8_t *)r->keys);
    }
    tud_task();
    return ok;
  }
};

static BridgeKeyboard kbd;

void usbKbdBegin() {
  kbd.begin();
}

bool usbKbdSendRaw(const KeyReport *r) {
  return kbd.sendRaw(r);
}
