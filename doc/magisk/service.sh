#!/system/bin/sh
# Magisk module service.sh: re-apply Authorizer's USB HID access at boot.
# Runs as root late in boot. Adjust APP and DEV as needed.
APP=net.tjado.passwdsafe
DEV=/dev/hidg0
G=/config/usb_gadget/g1

# Wait for the package manager and the gadget to be up
until pm path "$APP" >/dev/null 2>&1; do sleep 2; done
until [ -f "$G/UDC" ]; do sleep 2; done

# 1. HID keyboard function (only if none exists)
if ! ls "$G/functions" 2>/dev/null | grep -q '^hid\.'; then
  F="$G/functions/hid.usb0"
  CFG=$(ls -d "$G"/configs/* | head -n 1)
  UDC=$(cat "$G/UDC"); [ -n "$UDC" ] || UDC=$(ls /sys/class/udc | head -n 1)
  mkdir -p "$F"
  echo 1 > "$F/protocol"; echo 1 > "$F/subclass"; echo 8 > "$F/report_length"
  echo 'BQEJBqEBBQcZ4CnnFQAlAXUBlQiBApUBdQiBA5UFdQEFCBkBKQWRApUBdQORA5UGdQgVACVlBQcZACllgQDA' | base64 -d > "$F/report_desc"
  echo '' > "$G/UDC"
  ln -s "$F" "$CFG/" 2>/dev/null
  echo "$UDC" > "$G/UDC"
fi

# 2. SELinux: allow the app's domain to use device chr_files
UID_=$(stat -c %u "/data/data/$APP")
CTX=$(stat -c %C "/data/data/$APP")           # u:object_r:app_data_file:s0:cN,cM
CATS=${CTX##*:s0}; CATS=${CATS#:}
magiskpolicy --live "allow untrusted_app device chr_file { getattr open read write ioctl }"

# 3. Node ownership
[ -c "$DEV" ] || sleep 3
chown "$UID_:$UID_" "$DEV"
chmod 600 "$DEV"
chcon "u:object_r:device:s0${CATS:+:$CATS}" "$DEV"
