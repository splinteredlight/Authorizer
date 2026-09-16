// SPDX-License-Identifier: MIT
//
// Authorizer Bluetooth-to-USB keyboard bridge for Raspberry Pi Pico W / Pico 2 W.
//
// Derived from Adafruit's "Bluetooth Unified Keyboard Bridge" (John Park, 2025,
// MIT), reworked for Authorizer's Bluetooth auto-type:
//
//  * The Pico behaves like a PC: it is discoverable and connectable, and it
//    ACCEPTS an incoming Bluetooth Classic HID connection. Authorizer's
//    Bluetooth screen ("Start Device Scan" -> "Pair as Keyboard") finds the
//    Pico and the phone initiates the connection, exactly as it would with a
//    laptop. The phone never has to be made discoverable.
//  * Every 8-byte keyboard report from the phone is forwarded to the USB host
//    unchanged (modifiers + six usage codes). No ASCII round trip, so the
//    keyboard layout chosen in Authorizer is what the PC sees, and any key the
//    phone can send is passed through.
//  * A release-all report is sent whenever the Bluetooth link drops so a key
//    can never stay held on the host.
//  * Link keys are stored in the Pico's flash by BTstack, so the pairing
//    survives power cycles. Hold BOOTSEL briefly (while running) to forget
//    all pairings and start over.
//
// LED:  slow blink  = waiting for the phone to connect
//       solid       = phone connected, forwarding
//       short flicker on every report while connected
//       three fast blinks = link established
//
// Build: rp2040:rp2040:rpipicow:ipbtstack=ipv4btcble  (see ../build.sh)
// Serial: 115200 baud on the USB CDC port, nothing sensitive is printed.

extern "C" {
#include "btstack.h"
}
#include <BluetoothHCI.h>
#include "usb_kbd.h"

// === CONFIGURATION ===
#define BRIDGE_NAME     "Authorizer Bridge"
// Class of device: Major = Computer (0x01), Minor = Desktop (0x01 << 2).
// Authorizer lists anything it discovers; a computer class just looks right.
#define BRIDGE_COD      0x000104
#define DEBUG_REPORTS   false     // never enable outside the bench: prints usage codes

// --- Bluetooth side -----------------------------------------------------------
typedef enum { BOOTING, WAITING, CONNECTED } bridge_state_t;
static bridge_state_t state = BOOTING;

static BluetoothHCI hci;
static btstack_packet_callback_registration_t hci_cb;
static uint16_t control_cid = 0;
static uint16_t interrupt_cid = 0;
static bd_addr_t peer_addr;

// HID transport header (Bluetooth HID 1.1, section 7.4)
#define HID_HDR_TYPE_MASK     0xF0
#define HID_HDR_DATA          0xA0
#define HID_HDR_PARAM_INPUT   0x01

// --- LED ----------------------------------------------------------------------
static unsigned long ledTimer = 0;
static bool ledState = false;
static unsigned long ledOffUntil = 0;
static int celebrate = 0;
static unsigned long celebrateTimer = 0;

static void ledSet(bool on) {
  ledState = on;
  digitalWrite(LED_BUILTIN, on ? HIGH : LOW);
}

static void ledActivity() {
  if (state == CONNECTED && celebrate == 0) {
    digitalWrite(LED_BUILTIN, LOW);
    ledOffUntil = millis() + 40;
  }
}

static void ledCelebrate(int toggles) {
  celebrate = toggles;
  celebrateTimer = millis();
  ledSet(true);
}

static void ledService(unsigned long now) {
  if (celebrate > 0) {
    if (now - celebrateTimer >= 120) {
      celebrate--;
      ledSet(celebrate % 2 == 0);
      celebrateTimer = now;
    }
    return;
  }
  if (ledOffUntil) {
    if (now >= ledOffUntil) {
      ledOffUntil = 0;
      digitalWrite(LED_BUILTIN, HIGH);
    }
    return;
  }
  switch (state) {
    case BOOTING:
    case WAITING:
      if (now - ledTimer >= 700) {
        ledSet(!ledState);
        ledTimer = now;
      }
      break;
    case CONNECTED:
      if (!ledState) {
        ledSet(true);
      }
      break;
  }
}

// --- helpers -------------------------------------------------------------------
static void releaseAllKeys() {
  KeyReport empty = {0, 0, {0, 0, 0, 0, 0, 0}};
  usbKbdSendRaw(&empty);
}

static void dropLink() {
  if (interrupt_cid) l2cap_disconnect(interrupt_cid);
  if (control_cid) l2cap_disconnect(control_cid);
  interrupt_cid = 0;
  control_cid = 0;
  releaseAllKeys();
  state = WAITING;
}

static void handleInputReport(const uint8_t *packet, uint16_t size) {
  // packet[0] is the HID transport header: DATA | INPUT
  if (size < 9 || (packet[0] & HID_HDR_TYPE_MASK) != HID_HDR_DATA ||
      (packet[0] & 0x03) != HID_HDR_PARAM_INPUT) {
    return;
  }
  const uint8_t *p;
  if (size >= 10) {
    p = packet + 2;        // header, report ID, then 8-byte boot report
  } else {
    p = packet + 1;        // header, then 8-byte boot report (no report ID)
  }
  KeyReport r;
  r.modifiers = p[0];
  r.reserved = 0;
  memcpy(r.keys, p + 2, 6);
#if DEBUG_REPORTS
  Serial.printf("report mod=%02x keys=%02x %02x %02x %02x %02x %02x\n",
                r.modifiers, r.keys[0], r.keys[1], r.keys[2], r.keys[3], r.keys[4], r.keys[5]);
#endif
  usbKbdSendRaw(&r);
  ledActivity();
}

static void packetHandler(uint8_t packet_type, uint16_t channel, uint8_t *packet, uint16_t size) {
  if (packet_type == L2CAP_DATA_PACKET) {
    if (channel == interrupt_cid) {
      handleInputReport(packet, size);
    }
    // Control channel traffic (handshakes, SET_PROTOCOL) needs no reply here.
    return;
  }
  if (packet_type != HCI_EVENT_PACKET) {
    return;
  }

  switch (hci_event_packet_get_type(packet)) {
    case BTSTACK_EVENT_STATE:
      if (btstack_event_state_get_state(packet) == HCI_STATE_WORKING) {
        bd_addr_t local;
        gap_local_bd_addr(local);
        Serial.printf("Bluetooth up, address %s, name \"%s\"\n", bd_addr_to_str(local), BRIDGE_NAME);
        Serial.println("Discoverable. In Authorizer: Bluetooth -> Start Device Scan -> Pair as Keyboard");
        state = WAITING;
      }
      break;

    case HCI_EVENT_PIN_CODE_REQUEST: {
      // Legacy (pre-SSP) pairing fallback; modern phones use SSP.
      bd_addr_t addr;
      hci_event_pin_code_request_get_bd_addr(packet, addr);
      Serial.println("Legacy PIN request, answering 0000");
      gap_pin_code_response(addr, "0000");
      break;
    }

    case HCI_EVENT_USER_CONFIRMATION_REQUEST:
      // gap_ssp_set_auto_accept(1) answers this; log it so pairing is visible.
      Serial.printf("SSP confirmation, passkey %06lu (auto-accepted)\n",
                    (unsigned long)hci_event_user_confirmation_request_get_numeric_value(packet));
      break;

    case L2CAP_EVENT_INCOMING_CONNECTION: {
      uint16_t psm = l2cap_event_incoming_connection_get_psm(packet);
      uint16_t cid = l2cap_event_incoming_connection_get_local_cid(packet);
      bd_addr_t addr;
      l2cap_event_incoming_connection_get_address(packet, addr);
      if (psm == BLUETOOTH_PSM_HID_CONTROL || psm == BLUETOOTH_PSM_HID_INTERRUPT) {
        Serial.printf("Incoming HID %s channel from %s\n",
                      psm == BLUETOOTH_PSM_HID_CONTROL ? "control" : "interrupt", bd_addr_to_str(addr));
        memcpy(peer_addr, addr, sizeof(bd_addr_t));
        l2cap_accept_connection(cid);
      } else {
        l2cap_decline_connection(cid);
      }
      break;
    }

    case L2CAP_EVENT_CHANNEL_OPENED: {
      uint8_t status = l2cap_event_channel_opened_get_status(packet);
      uint16_t psm = l2cap_event_channel_opened_get_psm(packet);
      uint16_t cid = l2cap_event_channel_opened_get_local_cid(packet);
      if (status) {
        Serial.printf("HID channel (psm 0x%04x) failed, status 0x%02x\n", psm, status);
        break;
      }
      if (psm == BLUETOOTH_PSM_HID_CONTROL) {
        control_cid = cid;
      } else if (psm == BLUETOOTH_PSM_HID_INTERRUPT) {
        interrupt_cid = cid;
      }
      if (control_cid && interrupt_cid) {
        Serial.println("*** Phone connected, forwarding keystrokes to USB ***");
        state = CONNECTED;
        ledCelebrate(6);
      }
      break;
    }

    case L2CAP_EVENT_CHANNEL_CLOSED: {
      uint16_t cid = l2cap_event_channel_closed_get_local_cid(packet);
      if (cid == control_cid) control_cid = 0;
      if (cid == interrupt_cid) interrupt_cid = 0;
      if (!control_cid && !interrupt_cid && state == CONNECTED) {
        Serial.println("Phone disconnected");
        releaseAllKeys();
        state = WAITING;
      }
      break;
    }

    default:
      break;
  }
}

static void bluetoothStart() {
  l2cap_init();
  sm_init();

  gap_set_local_name(BRIDGE_NAME);
  gap_set_class_of_device(BRIDGE_COD);
  gap_set_default_link_policy_settings(LM_LINK_POLICY_ENABLE_SNIFF_MODE | LM_LINK_POLICY_ENABLE_ROLE_SWITCH);
  gap_set_bondable_mode(1);
  gap_ssp_set_enable(1);
  gap_ssp_set_io_capability(SSP_IO_CAPABILITY_NO_INPUT_NO_OUTPUT);   // "Just Works"
  gap_ssp_set_auto_accept(1);
  gap_discoverable_control(1);
  gap_connectable_control(1);

  // Accept the two HID channels the phone will open. LEVEL_2 makes BTstack
  // require an authenticated, encrypted link, which triggers pairing on first use.
  l2cap_register_service(&packetHandler, BLUETOOTH_PSM_HID_CONTROL, 0xffff, LEVEL_2);
  l2cap_register_service(&packetHandler, BLUETOOTH_PSM_HID_INTERRUPT, 0xffff, LEVEL_2);

  hci.install();
  hci_cb.callback = &packetHandler;
  hci_add_event_handler(&hci_cb);
  hci.begin();
}

// --- Arduino -------------------------------------------------------------------
void setup() {
  Serial.begin(115200);
  pinMode(LED_BUILTIN, OUTPUT);
  ledSet(true);

  usbKbdBegin();
  delay(1500);   // let the USB host enumerate and Serial open (optional)

  Serial.println("=== Authorizer Bluetooth -> USB keyboard bridge ===");
  bluetoothStart();
}

static void handleBootsel() {
  if (!BOOTSEL) {
    return;
  }
  while (BOOTSEL) {
    delay(1);
  }
  Serial.println("BOOTSEL: dropping link and forgetting all pairings");
  dropLink();
  gap_delete_all_link_keys();
  ledCelebrate(10);
}

void loop() {
  unsigned long now = millis();
  ledService(now);
  handleBootsel();
  delay(5);
}
