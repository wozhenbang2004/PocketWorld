"""Original controlled Companion fixture; synthetic CLI derives binary output from actual input.
No real account, filesystem isolation or third-party business-success assertion.
"""
import input_files_fixture_server as original

original.INPUT_FIXTURE = original.INPUT_FIXTURE.replace(
    "        Path('input-summary.json').write_text(json.dumps(summary))",
    "        Path('input-summary.json').write_text(json.dumps(summary))\n"
    "        Path('channel-result.bin').write_bytes(b'\\x00\\xff'+(bytes.fromhex(summary[0]['sha256'])[:4] if summary else b''))",
)

if __name__ == "__main__":
    original.main()
