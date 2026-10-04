cmake_minimum_required(VERSION 3.22.1)
project(hotcodepush_bspatch C)

# FreeBSD's bspatch behind one JNI function, and bzip2 1.0.8's decompression: blocksort.c and compress.c stay out, and with
# every symbol but the JNI function hidden the linker drops bzlib.c's compression functions that would call them.
add_library(hotcodepush_bspatch SHARED
    bspatch/bspatch.c
    bspatch_jni.c
    bzip2/bzlib.c
    bzip2/crctable.c
    bzip2/decompress.c
    bzip2/huffman.c
    bzip2/randtable.c)
set_target_properties(hotcodepush_bspatch PROPERTIES C_STANDARD 23 C_STANDARD_REQUIRED ON)
target_include_directories(hotcodepush_bspatch PRIVATE bspatch/include bzip2)
target_compile_options(hotcodepush_bspatch PRIVATE -fdata-sections -ffunction-sections -fvisibility=hidden)
target_link_options(hotcodepush_bspatch PRIVATE -Wl,--gc-sections)
