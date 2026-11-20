#include <expat.h>
#include <stdio.h>
#include <string.h>

/* Compatibility checks for the Expat libraries in the final API image. */
static size_t emitted;
static void XMLCALL chars(void *unused, const XML_Char *s, int n) { (void)unused; (void)s; emitted += n; }
static void XMLCALL pi(void *unused, const XML_Char *target, const XML_Char *data) { (void)unused; (void)target; (void)data; emitted++; }
static int XMLCALL external(XML_Parser parent, const XML_Char *context, const XML_Char *base, const XML_Char *system, const XML_Char *pub) {
  (void)base; (void)system; (void)pub;
  XML_Parser sub = XML_ExternalEntityParserCreate(parent, context, NULL);
  if (!sub) return 0;
  const char *doc = "<?target data?>";
  int ok = XML_Parse(sub, doc, strlen(doc), XML_TRUE) == XML_STATUS_OK;
  XML_ParserFree(sub);
  return ok;
}
int main(int argc, char **argv) {
  if (argc != 2) return 2;
  printf("version=%s case=%s\n", XML_ExpatVersion(), argv[1]); fflush(stdout);
  XML_Parser p = XML_ParserCreate(NULL);
  if (!p) return 2;
  int ok = 0;
  if (!strcmp(argv[1], "reset")) {
    const char *doc = "<root attr='ok'/>";
    ok = XML_Parse(p, doc, strlen(doc), XML_TRUE) == XML_STATUS_OK;
    ok = ok && XML_ParserReset(p, NULL);
    ok = ok && XML_Parse(p, "<r/>", 4, XML_TRUE) == XML_STATUS_OK;
  } else if (!strcmp(argv[1], "subparser")) {
    XML_SetProcessingInstructionHandler(p, pi);
    XML_SetExternalEntityRefHandler(p, external);
    const char *doc = "<!DOCTYPE root [<!ENTITY ext SYSTEM 'memory-only'>]><root>&ext;</root>";
    ok = XML_Parse(p, doc, strlen(doc), XML_TRUE) == XML_STATUS_OK && emitted == 1;
  } else if (!strcmp(argv[1], "basic")) {
    XML_SetCharacterDataHandler(p, chars);
    const char *doc = "<root attr='ok'>hello &amp; world</root>";
    ok = XML_Parse(p, doc, strlen(doc), XML_TRUE) == XML_STATUS_OK && emitted == 13;
  }
  printf("pass=%d error=%d emitted=%zu\n", ok, XML_GetErrorCode(p), emitted);
  XML_ParserFree(p);
  return ok ? 0 : 1;
}
