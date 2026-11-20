#include <expat.h>
#include <stdio.h>
#include <string.h>

static int conversions;
static int characters;

static int convert(void *data, const char *bytes) {
    (void)data;
    conversions++;
    return (unsigned char)bytes[0] == 0x81 && (unsigned char)bytes[1] == 0x82 ? 'I' : -1;
}

static int XMLCALL encoding(void *data, const XML_Char *name, XML_Encoding *info) {
    (void)data;
    (void)name;
    for (int i = 0; i < 256; i++) info->map[i] = i < 128 ? i : -1;
    info->map[0x81] = -2;
    info->convert = convert;
    info->data = NULL;
    info->release = NULL;
    return XML_STATUS_OK;
}

static void XMLCALL text(void *data, const XML_Char *value, int length) {
    (void)data;
    if (length == 1 && value[0] == 'I') characters++;
}

int main(void) {
    const XML_Char name[] = {'x', '-', 't', 'e', 's', 't', 0};
    XML_Parser parser = XML_ParserCreate(name);
    if (!parser) return 1;
    XML_SetUnknownEncodingHandler(parser, encoding, NULL);
    XML_SetCharacterDataHandler(parser, text);
    /* A legitimate multibyte character split across two input chunks. */
    int ok = XML_Parse(parser, "<r>\x81", 4, XML_FALSE) == XML_STATUS_OK;
    ok = ok && XML_Parse(parser, "\x82</r>", 5, XML_TRUE) == XML_STATUS_OK;
    ok = ok && conversions > 0 && characters == 1;
    printf("custom encoding and input chunk compatibility: %s\n", ok ? "passed" : "failed");
    XML_ParserFree(parser);
    return ok ? 0 : 1;
}
