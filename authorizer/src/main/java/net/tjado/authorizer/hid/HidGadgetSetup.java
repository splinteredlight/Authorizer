/*
 * Authorizer
 *
 * Copyright 2026 Authorizer contributors
 * Licensed under GNU General Public License 3.0.
 *
 * @license GPL-3.0 <https://opensource.org/licenses/GPL-3.0>
 *
 * The SELinux / configfs approach follows Arian04/android-hid-client
 * (me.arianb.usb_hid_client, GPL-3.0), which is the actively maintained
 * reference for HID gadgets on rooted stock kernels.
 */
package net.tjado.authorizer.hid;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.topjohnwu.superuser.Shell;

import net.tjado.authorizer.Utilities;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One-shot, root-only preparation of an existing configfs USB gadget so that
 * this app can write keyboard reports to /dev/hidgN directly, without a
 * custom kernel and without ever passing secrets through a root shell.
 *
 * What it does (all steps are idempotent):
 * <ol>
 * <li>If the gadget has no hid.* function, create one with the standard
 *     8-byte boot keyboard report descriptor, link it into the first config
 *     and re-bind the UDC. (Skipped when hid.* already exists, e.g. when it
 *     was created by hand or by another tool.)</li>
 * <li>Live-patch SELinux so this app's domain may open/read/write chr_file
 *     nodes of type "device" (magiskpolicy --live, or ksud on KernelSU).
 *     The patch lasts until reboot; see doc/HID_SETUP.md for a Magisk
 *     boot script.</li>
 * <li>chown/chmod/chcon the node to this app's UID and MLS categories.</li>
 * </ol>
 *
 * Root is used only here. Keystrokes are written by {@link net.tjado.authorizer.OutputUsbKeyboard}
 * as the app's own UID.
 */
public final class HidGadgetSetup
{
    private static final String TAG = "HidGadgetSetup";

    public static final String DEFAULT_DEVICE = "/dev/hidg0";
    private static final String CONFIGFS = "/config/usb_gadget";
    private static final String FUNCTION_NAME = "hid.usb0";

    /**
     * USB HID boot-protocol keyboard report descriptor, 63 bytes, no report
     * ID: 8-byte reports (modifiers, reserved, 6 key codes). Identical to
     * the descriptor used by the kernel's f_hid keyboard example and by the
     * manual configfs setup this app targets.
     */
    private static final String KEYBOARD_REPORT_DESC_B64 =
            "BQEJBqEBBQcZ4CnnFQAlAXUBlQiBApUBdQiBA5UFdQEFCBkBKQWRApUBdQORA5UGdQgVACVlBQcZACllgQDA";

    private static final String[] MAGISKPOLICY_CANDIDATES = {
            "magiskpolicy",
            "/debug_ramdisk/magiskpolicy",
            "/sbin/magiskpolicy",
            "/data/adb/magisk/magiskpolicy",
    };

    private static final ExecutorService itsExecutor =
            Executors.newSingleThreadExecutor();
    private static final Handler itsMainHandler =
            new Handler(Looper.getMainLooper());

    static {
        Shell.enableVerboseLogging = net.tjado.passwdsafe.BuildConfig.DEBUG;
        Shell.setDefaultBuilder(Shell.Builder.create()
                                             .setFlags(Shell.FLAG_MOUNT_MASTER)
                                             .setTimeout(20));
    }

    /** Outcome of a preparation run */
    public static final class Result
    {
        public final boolean success;
        public final String message;
        public final List<String> log;

        Result(boolean success, String message, List<String> log)
        {
            this.success = success;
            this.message = message;
            this.log = log;
        }
    }

    /** Callback for {@link #ensureReadyAsync} */
    public interface ReadyCallback
    {
        /** Called on the main thread when the device is writable */
        void onReady();

        /** Called on the main thread when it could not be made writable */
        void onFailed(@NonNull String message);
    }

    private HidGadgetSetup()
    {
    }

    /**
     * Read this process' SELinux context, e.g. "u:r:untrusted_app:s0:c1,c2".
     */
    @Nullable
    static String readOwnSelinuxContext()
    {
        try (BufferedReader r = new BufferedReader(
                new FileReader("/proc/self/attr/current"))) {
            String line = r.readLine();
            return (line == null) ? null : line.trim().replace("\0", "");
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Make sure the device is writable. Returns immediately via
     * {@code onReady} if it already is; otherwise runs the root setup on a
     * background thread and reports through the callback on the main thread.
     */
    public static void ensureReadyAsync(@NonNull String devicePath,
                                        @NonNull ReadyCallback cb)
    {
        if (HidStatus.probe(devicePath).isReady()) {
            cb.onReady();
            return;
        }
        itsExecutor.execute(() -> {
            Result res = prepare(devicePath);
            itsMainHandler.post(() -> {
                if (res.success) {
                    cb.onReady();
                } else {
                    cb.onFailed(res.message);
                }
            });
        });
    }

    /** Run the setup on a background thread with a completion callback */
    public static void prepareAsync(@NonNull String devicePath,
                                    @NonNull java.util.function.Consumer<Result> cb)
    {
        itsExecutor.execute(() -> {
            Result res = prepare(devicePath);
            itsMainHandler.post(() -> cb.accept(res));
        });
    }

    /**
     * Blocking root setup. Must not be called on the main thread.
     */
    @WorkerThread
    @NonNull
    public static Result prepare(@NonNull String devicePath)
    {
        List<String> log = new ArrayList<>();
        try {
            Shell shell = Shell.getShell();
            if (!shell.isRoot()) {
                return new Result(false, "Root access denied", log);
            }

            String ctx = readOwnSelinuxContext();
            if (ctx == null) {
                return new Result(false,
                                  "Cannot read own SELinux context", log);
            }
            // u:r:<domain>:s0[:categories]
            String[] parts = ctx.split(":", 4);
            if (parts.length < 3) {
                return new Result(false, "Unexpected SELinux context " + ctx, log);
            }
            String domain = parts[2];
            String categories = "";
            if (parts.length == 4) {
                int idx = parts[3].indexOf(':');
                if (idx >= 0) {
                    categories = parts[3].substring(idx + 1);
                }
            }
            log.add("domain=" + domain + " categories=" + categories);

            // 1. Gadget function
            String gadget = findGadgetDir(log);
            if (gadget == null) {
                return new Result(false,
                                  "No configfs USB gadget under " + CONFIGFS +
                                  " (is CONFIG_USB_CONFIGFS_F_HID enabled?)",
                                  log);
            }
            if (!hasHidFunction(gadget, log)) {
                Result r = createHidFunction(gadget, log);
                if (!r.success) {
                    return r;
                }
            }
            if (!Shell.cmd("[ -c '" + devicePath + "' ]").exec().isSuccess()) {
                return new Result(false, devicePath +
                                         " still missing after gadget setup",
                                  log);
            }

            // 2. SELinux
            String sepolicyRule = "allow " + domain +
                                  " device chr_file { getattr open read write ioctl }";
            Result sr = patchSepolicy(sepolicyRule, log);
            if (!sr.success) {
                return sr;
            }

            // 3. Ownership and label of the node
            int uid = Process.myUid();
            String label = "u:object_r:device:s0" +
                           (categories.isEmpty() ? "" : ":" + categories);
            Shell.Result cr = Shell.cmd(
                    "chown " + uid + ":" + uid + " '" + devicePath + "'",
                    "chmod 600 '" + devicePath + "'",
                    "chcon '" + label + "' '" + devicePath + "'").exec();
            logResult("chown/chmod/chcon", cr, log);
            if (!cr.isSuccess()) {
                return new Result(false, "Could not change ownership of " +
                                         devicePath, log);
            }

            HidStatus st = HidStatus.probe(devicePath);
            if (!st.isReady()) {
                return new Result(false, devicePath +
                                         " is still not writable after setup (check dmesg for avc denials)",
                                  log);
            }
            return new Result(true, "USB HID device ready: " + devicePath, log);
        } catch (Exception e) {
            Utilities.dbginfo(TAG, "prepare failed: " + e);
            log.add("exception: " + e);
            return new Result(false, "Setup failed: " + e.getMessage(), log);
        }
    }

    /** Find the gadget directory: prefer the one that has a bound UDC */
    @Nullable
    private static String findGadgetDir(List<String> log)
    {
        Shell.Result r = Shell.cmd(
                "for g in " + CONFIGFS + "/*; do " +
                "[ -f \"$g/UDC\" ] && [ -n \"$(cat $g/UDC 2>/dev/null)\" ] && echo \"$g\"; done").exec();
        List<String> bound = r.getOut();
        if (!bound.isEmpty() && !bound.get(0).trim().isEmpty()) {
            log.add("gadget (bound): " + bound.get(0));
            return bound.get(0).trim();
        }
        r = Shell.cmd("ls -d " + CONFIGFS + "/*/ 2>/dev/null").exec();
        for (String line : r.getOut()) {
            String g = line.trim();
            if (g.endsWith("/")) {
                g = g.substring(0, g.length() - 1);
            }
            if (!g.isEmpty()) {
                log.add("gadget (unbound): " + g);
                return g;
            }
        }
        return null;
    }

    private static boolean hasHidFunction(String gadget, List<String> log)
    {
        Shell.Result r = Shell.cmd("ls '" + gadget + "/functions'").exec();
        for (String f : r.getOut()) {
            if (f.trim().startsWith("hid.")) {
                log.add("existing function: " + f.trim());
                return true;
            }
        }
        return false;
    }

    /**
     * Create hid.usb0 (boot keyboard) and link it into the first config.
     * The UDC is unbound while the link is added, which briefly resets the
     * USB connection to the host (adb reconnects).
     */
    private static Result createHidFunction(String gadget, List<String> log)
    {
        String fn = gadget + "/functions/" + FUNCTION_NAME;
        Shell.Result r = Shell.cmd(
                "set -e",
                "G='" + gadget + "'",
                "F='" + fn + "'",
                "CFG=$(ls -d \"$G\"/configs/* | head -n 1)",
                "[ -n \"$CFG\" ]",
                "UDC=$(cat \"$G/UDC\" 2>/dev/null)",
                "[ -n \"$UDC\" ] || UDC=$(ls /sys/class/udc | head -n 1)",
                "echo \"cfg=$CFG udc=$UDC\"",
                "mkdir -p \"$F\"",
                "echo 1 > \"$F/protocol\"",
                "echo 1 > \"$F/subclass\"",
                "echo 8 > \"$F/report_length\"",
                "echo '" + KEYBOARD_REPORT_DESC_B64 + "' | base64 -d > \"$F/report_desc\"",
                "echo '' > \"$G/UDC\" || true",
                "[ -e \"$CFG/" + FUNCTION_NAME + "\" ] || ln -s \"$F\" \"$CFG/\"",
                "echo \"$UDC\" > \"$G/UDC\"").exec();
        logResult("create hid function", r, log);
        if (!r.isSuccess()) {
            return new Result(false, "Could not create HID function in " +
                                     gadget, log);
        }
        return new Result(true, "created " + fn, log);
    }

    /** Apply the live SELinux rule with whatever root solution is present */
    private static Result patchSepolicy(String rule, List<String> log)
    {
        for (String bin : MAGISKPOLICY_CANDIDATES) {
            Shell.Result probe = Shell.cmd("command -v " + bin +
                                           " >/dev/null 2>&1 || [ -x " + bin + " ]").exec();
            if (probe.isSuccess()) {
                Shell.Result r = Shell.cmd(bin + " --live '" + rule + "'").exec();
                logResult("magiskpolicy", r, log);
                return r.isSuccess() ?
                       new Result(true, "policy patched (magisk)", log) :
                       new Result(false, "magiskpolicy failed: " +
                                         String.join(" ", r.getErr()), log);
            }
        }
        if (Shell.cmd("command -v ksud >/dev/null 2>&1").exec().isSuccess()) {
            Shell.Result r = Shell.cmd("ksud sepolicy patch '" + rule + "'").exec();
            logResult("ksud", r, log);
            return r.isSuccess() ?
                   new Result(true, "policy patched (kernelsu)", log) :
                   new Result(false, "ksud sepolicy patch failed: " +
                                     String.join(" ", r.getErr()), log);
        }
        return new Result(false,
                          "No magiskpolicy or ksud found; cannot patch SELinux policy",
                          log);
    }

    private static void logResult(String what, Shell.Result r, List<String> log)
    {
        log.add(what + ": exit " + r.getCode());
        for (String s : r.getOut()) {
            log.add("  " + s);
        }
        for (String s : r.getErr()) {
            log.add("  ! " + s);
        }
        Utilities.dbginfo(TAG, what + " -> " + r.getCode());
    }
}
