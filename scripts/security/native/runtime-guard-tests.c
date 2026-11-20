#define main runtime_guard_main
#include "/src/runtime-guard.c"
#undef main
#include <assert.h>

int main(void) {
    char output[SEARCH_CAPACITY];
    assert(normalize_search("  team.svc.cluster.local\t svc.cluster.local  cluster.local ", output) == 0);
    assert(strcmp(output, "team.svc.cluster.local svc.cluster.local cluster.local") == 0);
    assert(normalize_search("", output) == 0 && output[0] == '\0');
    char boundary[257];
    memset(boundary, 'a', 255);
    boundary[255] = '\0';
    assert(normalize_search(boundary, output) == 0 && strlen(output) == 255);
    boundary[255] = 'a';
    boundary[256] = '\0';
    assert(normalize_search(boundary, output) == -1);
    assert(setenv("LOCALDOMAIN", "team.test\nother.test", 1) == 0);
    assert(configure_search(output) == -1);
    assert(setenv("LOCALDOMAIN", "  team.test  svc.test ", 1) == 0);
    assert(configure_search(output) == 0 && strcmp(output, "team.test svc.test") == 0);
    const char config[] = "# normal configuration\nnameserver 127.0.0.11\nsearch first.test\ndomain second.test ignored.test\nsearch team.test svc.test\n";
    FILE *file = tmpfile();
    assert(file != NULL);
    assert(fputs(config, file) >= 0);
    rewind(file);
    int found;
    assert(read_search(file, output, &found) == 0 && found);
    assert(strcmp(output, "team.test svc.test") == 0);
    fclose(file);
    puts("runtime guard parser: 7 checks passed");
    return 0;
}
