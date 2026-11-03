/* Host-test CLI protocol peer entrypoint only. Real ELF/stat observation, never a
 * claim that delegated content/version/the execution chain has been verified. */
#include <stdlib.h>
#include <unistd.h>
#ifndef FIXTURE_PYTHON
#error "FIXTURE_PYTHON is required"
#endif
#ifndef FIXTURE_SCRIPT
#error "FIXTURE_SCRIPT is required"
#endif
int main(int argc, char **argv) {
    char **peer = calloc((size_t)argc + 2, sizeof(*peer));
    if (!peer) return 125;
    peer[0] = FIXTURE_PYTHON;
    peer[1] = FIXTURE_SCRIPT;
    for (int i = 1; i < argc; i++) peer[i + 1] = argv[i];
    execv(peer[0], peer);
    free(peer);
    return 127;
}
