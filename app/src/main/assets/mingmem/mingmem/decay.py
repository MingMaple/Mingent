import math
from datetime import datetime

from .settings import (ALPHA, BETA, CONSOLIDATE_KINDS, DECAY_BASE, DEFAULT_VARS, GAMMA,
                      NODE_KINDS)
from .text import clamp01, parse_ts


def age_days(ts):
    dt = parse_ts(ts)
    if dt is None:
        return 0.0
    now = datetime.now().astimezone()
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=now.tzinfo)
    return max(0.0, (now - dt).total_seconds() / 86400.0)


def node_kind_for(ev):
    k = ev.get("kind")
    if k in ("access", "sweep", "archive", "link"):
        return None
    if k == "consolidate":
        nk = ev.get("node_kind") or "semantic"
        return nk if nk in CONSOLIDATE_KINDS else "semantic"
    if k == "correction":
        return "correction"
    return k if k in NODE_KINDS else "note"


def variables_for(ev, kind):
    base = dict(DEFAULT_VARS.get(kind, DEFAULT_VARS["note"]))
    for k in ("S", "D", "E", "C", "I"):
        v = ev.get(k)
        if isinstance(v, (int, float)):
            base[k] = clamp01(v)
    return base


def decayed(node, days):
    e = clamp01(node.get("E") or 0.0)
    c = clamp01(node.get("C") or 0.0)
    i = clamp01(node.get("I") or 0.0)
    lam_e = DECAY_BASE["E"]
    lam_s = max(DECAY_BASE["S"], lam_e)
    lam_d = DECAY_BASE["D"] * (1 - ALPHA * e) * (1 - BETA * c) * (1 + GAMMA * i)
    lam_d = max(lam_d, lam_s)
    d0 = clamp01(node.get("D") or 0.0)
    s0 = clamp01(node.get("S") or 0.0)
    return (d0 * math.exp(-lam_d * days),
            s0 * math.exp(-lam_s * days),
            e * math.exp(-lam_e * days))
