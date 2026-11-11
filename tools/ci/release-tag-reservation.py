"""Choose the next unoccupied release tag without rewriting existing signed history.

The public latest release is the baseline for constructing delta patches.
Git tag refs, including orphaned refs left by interrupted publishes, reserve
version numbers but do not make a usable delta baseline.
"""

def select_version(floor, tag_for_code, occupied, max_candidates=64):
    if type(floor) is not int or not 999 <= floor < 2_100_000_000:
        raise ValueError("Invalid verified release floor")
    if type(max_candidates) is not int or not 1 <= max_candidates <= 256:
        raise ValueError("Invalid bounded tag search")
    if not callable(tag_for_code) or not callable(occupied):
        raise TypeError("Tag formatter and lookup are required")
    for offset in range(1, max_candidates + 1):
        code = floor + offset
        if code > 2_100_000_000:
            raise ValueError("Android version codes exhausted")
        tag = tag_for_code(code)
        if not occupied(tag):
            return code
    raise ValueError("All bounded candidates reserved; manual release inspection required")
