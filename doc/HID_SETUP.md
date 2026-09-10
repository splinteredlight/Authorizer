# USB HID auto-type on a rooted stock kernel

Authorizer types credentials into a USB host by acting as a USB keyboard.
Since 0.6.0 it no longer needs a custom kernel (pelya/android-keyboard-gadget)
and never passes secrets through a root shell. It writes 8-byte boot-keyboard
reports straight to the gadget character device (`/dev/hidg0` by default) as its
own UID. Root is used once, to prepare that node.

Tested target: Pixel 9 Pro XL ("komodo"), Android 17, Magisk, SELinux enforcing,
stock kernel with `CONFIG_USB_CONFIGFS_F_HID=y`.

No root? The Bluetooth auto-type path needs none, and
`hardware/pico-bt-bridge/` turns it into a USB keyboard on any PC with a
Raspberry Pi Pico W. See that directory's README.

## What "Prepare USB HID device" does

Settings → Auto-Type → *Prepare USB HID device now* (or automatically on first
auto-type when *Prepare USB HID device automatically* is on) runs, as root:

1. **Gadget function.** Look through `/config/usb_gadget/<g>/functions/hid.*`
   for a boot keyboard with plain 8-byte reports (`protocol 1`,
   `report_length 8`), e.g. one you created by hand. If there is none, create
   `hid.authorizer` with protocol 1, subclass 1, `report_length 8` and the
   standard 63-byte boot keyboard descriptor, symlink it into the first config
   (`configs/b.1`) and re-bind the UDC. Re-binding drops the USB link for a
   second (adb reconnects). The matching `/dev/hidgN` is resolved from the
   function's `dev` attribute (major:minor) and stored in the *USB HID device*
   preference, so the default `/dev/hidg0` is only a starting point.

   Other tools' functions are never touched. On the test device
   android-hid-client owned `hid.keyboard` (`/dev/hidg0`, a report-ID keyboard
   with `report_length 4`) and `hid.touchpad`; Authorizer added
   `hid.authorizer` as `/dev/hidg2` next to them and both apps keep working.
2. **SELinux.** The app reads its own context from `/proc/self/attr/current`
   (e.g. `u:r:untrusted_app:s0:c159,c256,c512,c768`) and applies
   `magiskpolicy --live "allow untrusted_app device chr_file { getattr open read write ioctl }"`
   (or `ksud sepolicy patch ...` on KernelSU). This lasts until reboot.
3. **Node ownership.** `chown <uid>:<uid>`, `chmod 600`, and
   `chcon u:object_r:device:s0:<app categories>` on the device node.

After that the app opens the node itself; no further root calls.

Root prompt: the first run asks Magisk for root. Answer the prompt within its
timeout; if it is rejected or times out, tap *Prepare* again after granting
Authorizer in the Magisk app (Superuser tab).

## Manual test (adb + Termux with root)

```sh
# 1. gadget state
adb shell su -c 'ls -l /config/usb_gadget/g1/functions/ /config/usb_gadget/g1/configs/b.1/; cat /config/usb_gadget/g1/UDC'
adb shell su -c 'ls -lZ /dev/hidg*'

# 2. host side: plug the phone into a computer, focus a text editor, then send
#    'a' as one 8-byte press report and one release report. Android's printf
#    has no \x escapes and nested quoting mangles octal, so build the bytes on
#    the PC and write them with dd bs=8 (one write() per report):
B64=$(python3 -c 'import base64;print(base64.b64encode(bytes([0,0,4,0,0,0,0,0]+[0]*8)).decode())')
adb shell su -c "echo $B64 | base64 -d > /data/local/tmp/hid.bin; dd bs=8 if=/data/local/tmp/hid.bin of=/dev/hidgN; rm /data/local/tmp/hid.bin"
# an 'a' must appear on the host (use the hidgN shown in Settings)

# 3. app side: install, open a record, tap "Auto-Type USB → Username".
#    First run prompts Magisk for root once, then types.

# 4. verify the node is now owned by the app and the policy is live
adb shell su -c 'ls -lZ /dev/hidg*'           # Authorizer's node: owned by u0_aNNN, label with the app's categories
adb shell su -c 'cat /config/usb_gadget/g1/functions/hid.authorizer/dev'   # major:minor -> /dev/hidg<minor>
adb shell 'dmesg | grep -i avc | grep hidg'        # must be empty after setup
adb logcat -s HidGadgetSetup OutputUsbKeyboard      # debug builds only
```

Unexpected `avc: denied` lines in `dmesg` name the exact permission that is
missing; add it to the rule in `HidGadgetSetup.prepare()`.

## Surviving a reboot

The live policy patch and node ownership reset at boot. A minimal Magisk
module can re-apply them: copy `doc/magisk/service.sh` into a module directory
as `service.sh`, add a `module.prop`, and adjust `APP=net.tjado.passwdsafe`.
The gadget function itself must also be recreated at boot if you created it
by hand; the script does that too.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| "USB HID device /dev/hidgN not found" | No boot-keyboard function in the gadget, or gadget not bound | Run *Prepare*, check `cat /config/usb_gadget/g1/UDC` is non-empty |
| "exists, no access" after Prepare | Policy patch failed, or a second `avc` permission is needed | `dmesg | grep avc`; ensure `magiskpolicy` is on the root shell PATH |
| "Root access denied" | Magisk prompt rejected or timed out | Grant Authorizer in Magisk → Superuser, tap *Prepare* again |
| Host types garbage / nothing, node is hidg0 | hidg0 belongs to another tool's report-ID keyboard | Let *Prepare* pick/create the 8-byte function; check the path shown in Settings |
| Host sees nothing, no errors | Wrong function linked into the active config, or `report_length` ≠ 8 | `ls -l configs/b.1/`, `cat functions/hid.authorizer/report_length` |
| Characters wrong on the host | Keyboard layout | Settings → Auto-Type → language, or long-press an auto-type button |
| Prepare hangs | Magisk prompt waiting | Grant root in the Magisk dialog; 20 s timeout applies |
