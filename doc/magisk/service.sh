#!/system/bin/sh
# Magisk module service.sh: re-apply Authorizer's USB HID access at boot.
# Runs as root late in boot. Adjust APP and DEV as needed.
APP=net.tjado.passwdsafe
DEV=/dev/hidg0          # overridden below from the function's dev attribute
G=/config/usb_gadget/g1

LOG=/data/adb/modules/authorizer-hid/last-run.log
exec >"$LOG" 2>&1
echo "start $(date)"

# Wait for the gadget, and for the user's credential-encrypted storage to be
# unlocked: /data/data/<app> is unreadable before the first unlock, and we
# need its uid and SELinux categories.
until [ -f "$G/UDC" ]; do sleep 2; done
until stat -c %u "/data/data/$APP" >/dev/null 2>&1; do sleep 5; done
echo "app data visible $(date)"

# 1. Boot keyboard function (protocol 1, report_length 8); create if none
HAVE=""
for f in "$G"/functions/hid.*; do
  [ -d "$f" ] || continue
  [ "$(cat $f/protocol)" = 1 ] && [ "$(cat $f/report_length)" = 8 ] && HAVE="$f" && break
done
if [ -z "$HAVE" ]; then
  F="$G/functions/hid.authorizer"
  CFG=$(ls -d "$G"/configs/* | head -n 1)
  UDC=$(cat "$G/UDC"); [ -n "$UDC" ] || UDC=$(ls /sys/class/udc | head -n 1)
  mkdir -p "$F"
  echo 1 > "$F/protocol"; echo 1 > "$F/subclass"; echo 8 > "$F/report_length"
  echo 'BQEJBqEBBQcZ4CnnFQAlAXUBlQiBApUBdQiBA5UFdQEFCBkBKQWRApUBdQORA5UGdQgVACVlBQcZACllgQDA' | base64 -d > "$F/report_desc"
  echo '' > "$G/UDC"
  ln -s "$F" "$CFG/" 2>/dev/null
  echo "$UDC" > "$G/UDC"
  HAVE="$F"
fi
# resolve /dev/hidgN from the function's major:minor
MINOR=$(cat "$HAVE/dev" | cut -d: -f2)
[ -n "$MINOR" ] && DEV="/dev/hidg$MINOR"

# 2. SELinux: allow the app's domain to use device chr_files
UID_=$(stat -c %u "/data/data/$APP")
CTX=$(stat -c %C "/data/data/$APP")           # u:object_r:app_data_file:s0:cN,cM
CATS=${CTX##*:s0}; CATS=${CATS#:}
magiskpolicy --live "allow untrusted_app device chr_file { getattr open read write ioctl }"

# 3. Node ownership (the node appears a moment after the UDC re-binds)
for i in 1 2 3 4 5 6 7 8 9 10; do [ -c "$DEV" ] && break; sleep 1; done
[ -c "$DEV" ] || { echo "no $DEV after gadget setup"; exit 1; }
chown "$UID_:$UID_" "$DEV"
chmod 600 "$DEV"
chcon "u:object_r:device:s0${CATS:+:$CATS}" "$DEV"
ls -lZ "$DEV"
echo "done $(date)"
