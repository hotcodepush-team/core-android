#ifndef HOTCODEPUSH_BSPATCH_H
#define HOTCODEPUSH_BSPATCH_H

#include <sys/types.h>

#define HOTCODEPUSH_BSPATCH_OK 0
#define HOTCODEPUSH_BSPATCH_CORRUPT_PATCH 1
#define HOTCODEPUSH_BSPATCH_IO_ERROR 2
#define HOTCODEPUSH_BSPATCH_OUT_OF_MEMORY 3

/*
 * Applies the BSDIFF40 patch at patch_path to the file at old_path and writes
 * the result to new_path, refusing a patch whose new file exceeds max_new_size.
 * A control triple that reaches outside the old file adds nothing there, so the
 * caller checks the result's hash. Returns one of the statuses above; on any
 * failure new_path is removed.
 */
int hotcodepush_bspatch(const char *old_path, const char *new_path,
    const char *patch_path, off_t max_new_size);

#endif
