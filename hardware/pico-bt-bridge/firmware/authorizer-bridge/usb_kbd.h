// SPDX-License-Identifier: MIT
// Raw USB keyboard report output, kept in its own translation unit because
// TinyUSB's hid.h and BTstack's btstack_hid.h both define hid_report_type_t.
#pragma once
#include <Keyboard.h>   // KeyReport

void usbKbdBegin();
// Send one 8-byte boot keyboard report (modifiers + 6 usage codes) unchanged.
// Waits briefly for the endpoint so a press/release pair is never half-sent.
bool usbKbdSendRaw(const KeyReport *r);
