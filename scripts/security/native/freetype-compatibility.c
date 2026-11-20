#include <dlfcn.h>
#include <stdio.h>
#include <ft2build.h>
#include FT_FREETYPE_H

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    void *handle = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        fprintf(stderr, "%s\n", dlerror());
        return 1;
    }
#define LOAD(name) __typeof__(&name) call_##name = (__typeof__(&name))dlsym(handle, #name)
    LOAD(FT_Init_FreeType);
    LOAD(FT_Library_Version);
    LOAD(FT_New_Face);
    LOAD(FT_Set_Pixel_Sizes);
    LOAD(FT_Load_Char);
    LOAD(FT_Done_Face);
    LOAD(FT_Done_FreeType);
#undef LOAD
    if (!call_FT_Init_FreeType || !call_FT_Library_Version || !call_FT_New_Face ||
        !call_FT_Set_Pixel_Sizes || !call_FT_Load_Char || !call_FT_Done_Face || !call_FT_Done_FreeType) return 1;
    FT_Library library = NULL;
    FT_Face face = NULL;
    if (call_FT_Init_FreeType(&library)) return 1;
    int major, minor, patch;
    call_FT_Library_Version(library, &major, &minor, &patch);
    printf("FreeType %d.%d.%d via %s\n", major, minor, patch, argv[1]);
    int ok = call_FT_New_Face(library, "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 0, &face) == 0;
    if (ok) {
        ok = call_FT_Set_Pixel_Sizes(face, 0, 16) == 0 && call_FT_Load_Char(face, 'A', FT_LOAD_RENDER) == 0;
        ok = ok && face->glyph->bitmap.width > 0 && face->glyph->bitmap.rows > 0;
        call_FT_Done_Face(face);
    }
    call_FT_Done_FreeType(library);
    dlclose(handle);
    printf("font loading and glyph rasterization: %s\n", ok ? "passed" : "failed");
    return ok ? 0 : 1;
}
