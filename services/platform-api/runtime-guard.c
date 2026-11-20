#define _POSIX_C_SOURCE 200809L
#include <ctype.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* glibc's legacy resolver stores search strings, including their terminating
 * NULs, in defdname[256]. Joining them with spaces uses the same byte budget.
 * Pin a validated snapshot in LOCALDOMAIN before starting any JVM code.
 */
#define SEARCH_CAPACITY 256

static int normalize_search(const char *input, char output[SEARCH_CAPACITY]) {
    size_t length = 0;
    while (*input) {
        while (*input && isspace((unsigned char)*input)) input++;
        if (!*input) break;
        const char *start = input;
        while (*input && !isspace((unsigned char)*input)) input++;
        size_t token_length = (size_t)(input - start);
        size_t separator = length != 0;
        if (token_length >= SEARCH_CAPACITY - length - separator) return -1;
        if (separator) output[length++] = ' ';
        memcpy(output + length, start, token_length);
        length += token_length;
    }
    output[length] = '\0';
    return 0;
}

static int read_search(FILE *file, char output[SEARCH_CAPACITY], int *found) {
    char *line = NULL;
    size_t capacity = 0;
    int result = 0;
    *found = 0;
    while (getline(&line, &capacity, file) >= 0) {
        int domain = strncmp(line, "domain", 6) == 0;
        int search = strncmp(line, "search", 6) == 0;
        if ((!domain && !search) || (line[6] != ' ' && line[6] != '\t')) continue;
        char *value = line + 6;
        while (*value == ' ' || *value == '\t') value++;
        value[strcspn(value, "\r\n")] = '\0';
        if (!*value) continue;
        if (domain) value[strcspn(value, " \t")] = '\0';
        /* The last nonempty domain/search directive wins, as in glibc. */
        result = normalize_search(value, output);
        *found = 1;
    }
    if (!feof(file)) result = -2;
    free(line);
    return result;
}

static int configure_search(char output[SEARCH_CAPACITY]) {
    const char *configured = getenv("LOCALDOMAIN");
    if (configured) {
        if (strpbrk(configured, "\r\n")) return -1;
        return normalize_search(configured, output);
    }
    FILE *file = fopen("/etc/resolv.conf", "r");
    int found = 0;
    if (file) {
        int result = read_search(file, output, &found);
        fclose(file);
        if (result != 0) return result;
    } else if (errno != ENOENT) {
        return -2;
    }
    if (!found) {
        char hostname[256];
        if (gethostname(hostname, sizeof(hostname)) != 0) return -2;
        hostname[sizeof(hostname) - 1] = '\0';
        const char *dot = strchr(hostname, '.');
        return normalize_search(dot ? dot + 1 : ".", output);
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fputs("Usage: testhub-runtime-guard <program> [args...]\n", stderr);
        return 64;
    }
    char search[SEARCH_CAPACITY];
    int result = configure_search(search);
    if (result != 0) {
        fputs(result == -1
              ? "TestHub DNS configuration rejected: set LOCALDOMAIN to a single-line search list of at most 255 bytes after whitespace normalization.\n"
              : "TestHub cannot read DNS configuration; check /etc/resolv.conf permissions and LOCALDOMAIN.\n", stderr);
        return 78;
    }
    /* A root search suffix preserves the meaning of an explicitly empty list
     * and prevents fallback to an inherited hostname suffix on reload. */
    if (!search[0]) strcpy(search, ".");
    if (setenv("LOCALDOMAIN", search, 1) != 0 ||
        setenv("LC_ALL", "C.UTF-8", 1) != 0 || unsetenv("LANGUAGE") != 0 ||
        setenv("FONTCONFIG_FILE", "/etc/fonts/fonts.conf", 1) != 0 ||
        setenv("FONTCONFIG_PATH", "/etc/fonts", 1) != 0 ||
        setenv("HOME", "/home/nonroot", 1) != 0 ||
        setenv("XDG_CONFIG_HOME", "/home/nonroot/.config", 1) != 0) {
        fputs("TestHub could not initialize its runtime environment.\n", stderr);
        return 71;
    }
    execvp(argv[1], argv + 1);
    perror("TestHub could not start the configured program");
    return 71;
}
