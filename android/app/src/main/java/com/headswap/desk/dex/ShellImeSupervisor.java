package com.headswap.desk.dex;

/** Keep the already-authorized display helper available if its VM dies while the app remains
 * active. A waiting shell uses no polling, disk files or network. Restart is only possible after
 * the worker authenticated the exact app process; the next worker must match that PID/start time.
 * All process arguments are positional and never interpreted as shell source. */
final class ShellImeSupervisor {
    private ShellImeSupervisor() {}

    static final String LOOP =
        "set -f\n" +
        "runner=$1; shift; quick=0\n" +
        "while :; do\n" +
        "  began=$SECONDS\n" +
        "  result=$(\"$runner\" \"$@\")\n" +
        "  case \"$result\" in *CLOSED) exit 0;; PIN:*:*) ;; *) exit 0;; esac\n" +
        "  pair=${result#PIN:}; pid=${pair%%:*}; ticks=${pair#*:}\n" +
        "  case $pid in ''|*[!0-9]*) exit 0;; esac\n" +
        "  case $ticks in ''|*[!0-9]*) exit 0;; esac\n" +
        "  if [ \"$((SECONDS - began))\" -ge 60 ]; then quick=0; fi\n" +
        "  quick=$((quick + 1)); [ \"$quick\" -le 3 ] || exit 0\n" +
        "  set -- \"$1\" \"$2\" \"$3\" \"$4\" \"$5\" \"$pid\" \"$ticks\"\n" +
        "  sleep 1\n" +
        "done";

    static String[] command(int uid, String token, boolean panel) {
        if (uid < 10000 || uid % 100000 < 10000 || !PhoneActivationPolicy.validToken(token))
            throw new IllegalArgumentException("Invalid helper activation");
        return new String[]{"/system/bin/setsid", "/system/bin/nohup", "/system/bin/sh", "-c", LOOP,
            "headswap-display-lifetime", "/system/bin/app_process", "/", ShellPanelHelper.class.getName(),
            Integer.toString(uid), token, panel ? "ime-and-panel" : "ime"};
    }
}
