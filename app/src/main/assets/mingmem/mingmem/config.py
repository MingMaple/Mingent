import json
import os
from pathlib import Path

from .settings import NL

CONFIG_ENV = "MINGMEM_CONFIG"
APP_DIR = "mingmem"


def config_path():
    """用户级配置文件位置。

    优先级：MINGMEM_CONFIG（测试/脚本用）> Windows %APPDATA% > XDG/家目录。
    """
    explicit = (os.environ.get(CONFIG_ENV) or "").strip()
    if explicit:
        return Path(explicit)
    if os.name == "nt":
        base = (os.environ.get("APPDATA") or "").strip()
        if base:
            return Path(base) / APP_DIR / "config.json"
        return Path.home() / "AppData" / "Roaming" / APP_DIR / "config.json"
    base = (os.environ.get("XDG_CONFIG_HOME") or "").strip()
    if base:
        return Path(base) / APP_DIR / "config.json"
    return Path.home() / ".config" / APP_DIR / "config.json"


def load_config():
    path = config_path()
    try:
        raw = path.read_text(encoding="utf-8")
    except OSError:
        return {}
    try:
        data = json.loads(raw)
    except ValueError:
        return {}
    return data if isinstance(data, dict) else {}


def save_config(data):
    path = config_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = Path(str(path) + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + NL,
                   encoding="utf-8", newline="")
    os.replace(str(tmp), str(path))
    return path


def resolve_data_root(explicit=None):
    """返回 (数据根 or None, 来源说明)。

    顺序：--data-root > MINGMEM_DATA > MEM_DATA（兼容旧名）> 配置文件。
    **不设隐式默认**：都没有就返回 None，由调用方报错退出（D-02）。
    """
    if explicit:
        return Path(explicit), "--data-root"
    for key in ("MINGMEM_DATA", "MEM_DATA"):
        val = (os.environ.get(key) or "").strip()
        if val:
            return Path(val), "环境变量 " + key
    root = str(load_config().get("data_root") or "").strip()
    if root:
        return Path(root), "配置文件 " + str(config_path())
    return None, ""


def configure_message():
    return ("未配置数据根：记忆无处安放，拒绝继续。" + NL +
            "  修复：mingmem init --data-root <目录>" + NL +
            "  临时：--data-root <目录> 或 环境变量 MINGMEM_DATA" + NL +
            "  配置文件位置：" + str(config_path()))
