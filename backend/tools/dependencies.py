import os
import ast
import sys
import argparse
from importlib.metadata import version, PackageNotFoundError

ALWAYS_INCLUDE = set()

PACKAGE_MAPPING = {
    "dotenv": "python-dotenv",
    "google.generativeai": "google-generativeai",
    "google": "google-generativeai",
    "ebooklib": "EbookLib",
    "bs4": "beautifulsoup4",
    "PIL": "Pillow",
    "cv2": "opencv-python",
    "skimage": "scikit-image",
    "yaml": "PyYAML",
    "dateutil": "python-dateutil",
    "jwt": "python-jose",
    "uvicorn": "uvicorn[standard]",
    "ffmpeg": "ffmpeg-python",
    "pywhispercpp": "pywhispercpp",
}

IGNORE_DIRS = {
    'venv', '.venv', 'env', '.env', '.git', '__pycache__',
    '.idea', '.vscode', 'node_modules', 'build', 'dist',
    'site-packages', 'migrations', 'tests'
}

DEFAULT_WHITELIST = [
    "api", "core", "pipelines", "services", "utils", "tools",
    "cli.py", "main.py", "config.py"
]


def get_stdlib_modules():
    """Returns a set of standard library module names."""
    if sys.version_info >= (3, 10):
        return sys.stdlib_module_names
    else:
        return {
            "os", "sys", "re", "math", "random", "shutil", "subprocess", "json",
            "time", "datetime", "pathlib", "logging", "uuid", "io", "copy",
            "traceback", "functools", "collections", "abc", "argparse", "hashlib",
            "enum", "dataclasses", "threading", "multiprocessing", "tempfile",
            "base64", "warnings", "pickle", "struct", "inspect", "ast", "platform",
            "configparser", "contextlib", "csv", "email", "fnmatch", "glob",
            "gzip", "hmac", "html", "http", "importlib", "itertools", "operator",
            "queue", "signal", "socket", "sqlite3", "ssl", "stat", "string",
            "textwrap", "types", "unittest", "urllib", "xml", "zipfile", "zlib",
            "typing", "secrets", "concurrent", "asyncio", "weakref", "gc"
        }


STDLIB_MODULES = get_stdlib_modules()


def is_stdlib(module_name):
    if module_name in STDLIB_MODULES: return True
    if module_name.startswith('_'): return True
    return False


def get_imports_from_file(filepath):
    imports = set()
    try:
        with open(filepath, "r", encoding="utf-8") as f:
            root = ast.parse(f.read(), filename=filepath)

        for node in ast.walk(root):
            module_name = None
            if isinstance(node, ast.Import):
                for alias in node.names:
                    module_name = alias.name.split('.')[0]
                    imports.add(module_name)
            elif isinstance(node, ast.ImportFrom):
                if node.module:
                    module_name = node.module.split('.')[0]
                    if not node.level:
                        imports.add(module_name)
    except Exception:
        pass
    return imports


def get_project_root():
    current_dir = os.path.dirname(os.path.abspath(__file__))
    if os.path.basename(current_dir) == 'tools':
        return os.path.dirname(current_dir)
    return current_dir


def get_installed_version(package_name):
    """Try to find installed version for package."""
    candidates = [
        package_name,
        package_name.replace('_', '-'),
        package_name.lower()
    ]
    for candidate in candidates:
        try:
            clean_name = candidate.split('[')[0]
            return version(clean_name)
        except PackageNotFoundError:
            continue
    return None


def scan_project(root_dir, whitelist):
    all_imports = set()
    targets = [os.path.join(root_dir, t) for t in whitelist]

    for target in targets:
        if os.path.isfile(target):
            all_imports.update(get_imports_from_file(target))
        elif os.path.isdir(target):
            for dirpath, dirnames, filenames in os.walk(target):
                dirnames[:] = [d for d in dirnames if d not in IGNORE_DIRS]
                for filename in filenames:
                    if filename.endswith(".py"):
                        filepath = os.path.join(dirpath, filename)
                        all_imports.update(get_imports_from_file(filepath))
    return all_imports


def main():
    parser = argparse.ArgumentParser(description="Generate requirements.txt from source code.")
    parser.add_argument("--freeze", action="store_true", help="Add versions (==x.y.z)")
    parser.add_argument("--output", default="requirements.txt", help="Output filename")
    args = parser.parse_args()

    root_dir = get_project_root()
    print(f"🔍 Scanning project root: {root_dir}")

    # Detect local modules (to exclude them from requirements).
    # Only scan the ROOT directory for top-level packages/modules.
    local_modules = {
        os.path.splitext(f)[0]
        for f in os.listdir(root_dir)
        if os.path.isfile(os.path.join(root_dir, f)) and f.endswith('.py')
    }

    local_modules.update({
        d for d in os.listdir(root_dir)
        if os.path.isdir(os.path.join(root_dir, d)) and d not in IGNORE_DIRS
    })

    raw_imports = scan_project(root_dir, DEFAULT_WHITELIST)
    final_requirements = set()

    for req in ALWAYS_INCLUDE:
        final_requirements.add(req)

    # Found imports
    for module in raw_imports:
        if module in local_modules:
            continue
        if is_stdlib(module):
            continue
        if module[0].isupper() and module not in PACKAGE_MAPPING:
            continue

        pip_name = PACKAGE_MAPPING.get(module, module)
        final_requirements.add(pip_name)

    requirements_lines = set()
    print("-" * 30)

    for req in sorted(final_requirements):
        if args.freeze:
            ver = get_installed_version(req)
            if ver:
                requirements_lines.add(f"{req}=={ver}")
                print(f"➕ {req}=={ver}")
            else:
                requirements_lines.add(req)
                print(f"⚠️ {req} (Not installed, version unknown)")
        else:
            requirements_lines.add(req)
            print(f"➕ {req}")

    out_path = os.path.join(root_dir, args.output)
    with open(out_path, "w", encoding="utf-8") as f:
        if any('torch' in req for req in requirements_lines):
            f.write("--index-url https://download.pytorch.org/whl/cpu\n\n")
            pass

        for line in sorted(requirements_lines):
            f.write(f"{line}\n")

    print("-" * 30)
    print(f"✅ Saved to {args.output}")


if __name__ == "__main__":
    main()