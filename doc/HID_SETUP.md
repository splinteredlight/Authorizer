# USB HID auto-type on a rooted stock kernel

Authorizer types credentials into a USB host by acting as a USB keyboard.
Since 0.6.0 it no longer needs a custom kernel (pelya/android-keyboard-gadget)
and never passes secrets through a root shell. It writes 8-byte boot-keyboard
reports straight to the gadget character device (`/dev/hidg0` by default) as its
own UID. Root is used once, to prepare that node.

Tested target: Pixel 9 Pro XL ("komodo"), Android 17, Magisk, SELinux enforcing,
stock kernel with `CONFIG_USB_CONFIGFS_F_HID=y`.

## What "Prepare USB HID device" does

Settings → Auto-Type → *Prepare USB HID device now* (or automatically on first
auto-type when *Prepare USB HID device automatically* is on) runs, as root:

1. **Gadget function.** If `/config/usb_gadget/<g>/functions/` has no `hid.*`
   entry, create `hid.usb0` with protocol 1, subclass 1, `report_length 8` and
   the standard 63-byte boot keyboard descriptor, symlink it into the first
   config (`configs/b.1`) and re-bind the UDC. Re-binding drops the USB link
   for a second (adb reconnects). If a `hid.*` function already exists, for
   example the one you created by hand, this step is skipped entirely.
2. **SELinux.** The app reads its own context from `/proc/self/attr/current`
   (e.g. `u:r:untrusted_app:s0:c159,c256,c512,c768`) and applies
   `magiskpolicy --live "allow untrusted_app device chr_file { getattr open read write ioctl }"`
   (or `ksud sepolicy patch ...` on KernelSU). This lasts until reboot.
3. **Node ownership.** `chown <uid>:<uid>`, `chmod 600`, and
   `chcon u:object_r:device:s0:<app categories>` on the device node.

After that the app opens the node itself; no further root calls.

## Manual test (adb + Termux with root)

```sh
# 1. gadget state
adb shell su -c 'ls -l /config/usb_gadget/g1/functions/ /config/usb_gadget/g1/configs/b.1/; cat /config/usb_gadget/g1/UDC'
adb shell su -c 'ls -lZ /dev/hidg*'

# 2. host side: plug the phone into a computer, open a text editor, then
adb shell su -c "printf '\x00\x00\x04\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00' > /dev/hidg0"
# an 'a' must appear on the host

# 3. app side: install, open a record, tap "Auto-Type USB → Username".
#    First run prompts Magisk for root once, then types.

# 4. verify the node is now owned by the app and the policy is live
adb shell su -c 'ls -lZ /dev/hidg0'
adb shell su -c 'magiskpolicy --live --print-rules 2>/dev/null | grep "device chr_file" || true'
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
| "USB HID device /dev/hidg0 not found" | No `hid.*` function in the gadget, or gadget not bound | Run *Prepare*, check `cat /config/usb_gadget/g1/UDC` is non-empty |
| "exists, no access" after Prepare | Policy patch failed, or a second `avc` permission is needed | `dmesg | grep avc`; ensure `magiskpolicy` is on the root shell PATH |
| Host sees nothing, no errors | Wrong function linked into the active config, or `report_length` ≠ 8 | `ls -l configs/b.1/`, `cat functions/hid.usb0/report_length` |
| Characters wrong on the host | Keyboard layout | Settings → Auto-Type → language, or long-press an auto-type button |
| Prepare hangs | Magisk prompt waiting | Grant root in the Magisk dialog; 20 s timeout applies |
