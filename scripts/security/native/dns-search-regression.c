#define _DEFAULT_SOURCE
#include <assert.h>
#include <resolv.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Exercise libc directly, independently of the runtime configuration guard. */
int main(int argc, char **argv) {
    assert(argc == 2);
    char domains[512];
    size_t expected_entries;
    if (strcmp(argv[1], "oversize-first") == 0) {
        memset(domains, 'a', 256);
        domains[256] = '\0';
        expected_entries = 0;
    } else if (strcmp(argv[1], "truncated-tail") == 0) {
        memcpy(domains, "ok.test ", 8);
        memset(domains + 8, 'b', 250);
        domains[258] = '\0';
        expected_entries = 1;
    } else if (strcmp(argv[1], "exact-first-boundary") == 0) {
        memset(domains, 'a', 255);
        domains[255] = '\0';
        expected_entries = 1;
    } else if (strcmp(argv[1], "max-entries") == 0) {
        strcpy(domains, "a.test b.test c.test d.test e.test f.test g.test");
        expected_entries = MAXDNSRCH;
    } else {
        assert(strcmp(argv[1], "ordinary") == 0);
        strcpy(domains, "team.test svc.test local.test");
        expected_entries = 3;
    }
    assert(setenv("LOCALDOMAIN", domains, 1) == 0);
    struct __res_state state = {0};
    assert(res_ninit(&state) == 0);
    size_t entries = 0;
    while (entries < MAXDNSRCH && state.dnsrch[entries] != NULL) {
        ++entries;
    }
    assert(entries == expected_entries);
    assert(memchr(state.defdname, '\0', sizeof(state.defdname)) != NULL);
    if (expected_entries == 0) {
        assert(state.defdname[0] == '\0');
    }
    if (strcmp(argv[1], "truncated-tail") == 0) {
        assert(strcmp(state.dnsrch[0], "ok.test") == 0);
    }
    if (strcmp(argv[1], "exact-first-boundary") == 0) {
        assert(strlen(state.dnsrch[0]) == 255);
    }
    res_nclose(&state);
    printf("libc resolver %s: %zu legacy search entries, initialization passed\n",
           argv[1], entries);
    return 0;
}
