"""Real local text transformation example. Run explicitly with SDK's module directory on PYTHONPATH."""
import argparse
from pocket_plugin_server import LocalToolServer, Tool


def normalize_text(arguments):
    if set(arguments) != {"text"} or not isinstance(arguments["text"], str) or len(arguments["text"]) > 4000:
        raise ValueError("Expected only text, at most 4000 Unicode characters")
    return {"text": " ".join(arguments["text"].split())}


def word_count(arguments):
    normalized = normalize_text(arguments)["text"]
    return {"count": len(normalized.split())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    options = parser.parse_args()
    with LocalToolServer(("127.0.0.1", options.port), [Tool("normalize_text", normalize_text), Tool("word_count", word_count)]) as server:
        print(f"Local tools listening at http://127.0.0.1:{server.server_port}/tools", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
