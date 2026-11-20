#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int equals(const char *key, const char *expected) {
    const char *value = getenv(key);
    return value && strcmp(value, expected) == 0;
}

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    if (getpid() != 1) return 1;
    if (!equals("LOCALDOMAIN", argv[1])) {
        fprintf(stderr, "LOCALDOMAIN snapshot mismatch (expected %zu bytes, observed %zu bytes)\n",
                strlen(argv[1]), getenv("LOCALDOMAIN") ? strlen(getenv("LOCALDOMAIN")) : 0);
        return 1;
    }
    if (!equals("LC_ALL", "C.UTF-8") || getenv("LANGUAGE") ||
        !equals("HOME", "/home/nonroot") || !equals("FONTCONFIG_FILE", "/etc/fonts/fonts.conf") ||
        !equals("FONTCONFIG_PATH", "/etc/fonts") || !equals("XDG_CONFIG_HOME", "/home/nonroot/.config")) return 1;
    printf("validated search snapshot and runtime environment passed (%zu bytes)\n", strlen(argv[1]));
    return 0;
}
