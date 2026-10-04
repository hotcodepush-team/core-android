/*-
 * SPDX-License-Identifier: BSD-2-Clause
 *
 * Copyright 2003-2005 Colin Percival
 * All rights reserved
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted providing that the following conditions 
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING
 * IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

/*
 * HotCodePush: FreeBSD's usr.bin/bsdiff/bspatch/bspatch.c as of freebsd-src
 * commit 725a9f47324d42037db93c27ceb40d4956872f3e, the file's latest change on
 * main (a5b1b2d2c5e585364809fdf8f165d6dc742faadb, 2026-10-04), changed only for
 * a library:
 * - main() is hotcodepush_bspatch(), which takes the three paths in bspatch's
 *   order and the most bytes the new file may hold, and returns a status in
 *   place of exiting; usage() is gone.
 * - Every err() and errx() sets that status and jumps to one cleanup, which
 *   closes, frees and unmaps what is open and deletes a partial new file; the
 *   atexit() cleanup and its globals are gone.
 * - add_off_t() is gone: each sum it checked is a ckd_add() whose overflow is
 *   a corrupt patch, as it was there.
 * - Capsicum is gone, and the new file is opened by its path rather than
 *   through its directory.
 * - <sys/types.h> is included for u_char, which Darwin declares there.
 * - The diff and extra lengths are cast to BZ2_bzRead()'s int, which the
 *   sanity check above each read guarantees, so no compiler warns.
 * - A header whose new size exceeds the caller's bound is a corrupt patch.
 * - The old file's bound is `oldpos + i >= 0 && oldpos + i < oldsize` again:
 *   FreeBSD's overflow checks (20bd59416dca, 2019) dropped the lower half, so a
 *   control triple that seeks before the old file read outside it.
 * - OFF_MAX is defined from off_t's width where the C library lacks it, as
 *   Android's does.
 * - offtin() decodes each 8-byte field in 64 bits, and a value outside off_t's
 *   range is a corrupt patch: off_t is 32 bits on Android's 32-bit ABIs, whose
 *   64-bit file offsets need API 24.
 */

#include <sys/types.h>

#include <bzlib.h>
#include <fcntl.h>
#include <limits.h>
#include <stdckdint.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/mman.h>

#include "HotCodePushBspatch.h"

#ifndef O_BINARY
#define O_BINARY 0
#endif
#ifndef OFF_MAX
#define OFF_MAX ((off_t)((UINT64_C(1) << (sizeof(off_t) * CHAR_BIT - 1)) - 1))
#endif
#define HEADER_SIZE 32

#define FAIL(s) do { status = (s); goto cleanup; } while (0)

static int offtin(u_char *buf, off_t *off)
{
	int64_t y;

	y = buf[7] & 0x7F;
	y = y * 256; y += buf[6];
	y = y * 256; y += buf[5];
	y = y * 256; y += buf[4];
	y = y * 256; y += buf[3];
	y = y * 256; y += buf[2];
	y = y * 256; y += buf[1];
	y = y * 256; y += buf[0];

	if (buf[7] & 0x80)
		y = -y;

	if (y > OFF_MAX || y < -OFF_MAX - 1)
		return (-1);
	*off = (off_t)y;
	return (0);
}

int hotcodepush_bspatch(const char *old_path, const char *new_path,
    const char *patch_path, off_t max_new_size)
{
	FILE *f = NULL, *cpf = NULL, *dpf = NULL, *epf = NULL;
	BZFILE *cpfbz2 = NULL, *dpfbz2 = NULL, *epfbz2 = NULL;
	const char *newfile = NULL;
	int cbz2err, dbz2err, ebz2err;
	int newfd = -1, oldfd = -1;
	int status = HOTCODEPUSH_BSPATCH_OK;
	off_t oldsize = 0, newsize;
	off_t bzctrllen, bzdatalen;
	u_char header[HEADER_SIZE], buf[8];
	u_char *old = MAP_FAILED, *new = NULL;
	off_t oldpos, newpos;
	off_t ctrl[3];
	off_t i, lenread, offset, sum;

	/* Open patch file */
	if ((f = fopen(patch_path, "rb")) == NULL)
		FAIL(HOTCODEPUSH_BSPATCH_IO_ERROR);
	/* Open patch file for control block */
	if ((cpf = fopen(patch_path, "rb")) == NULL)
		FAIL(HOTCODEPUSH_BSPATCH_IO_ERROR);
	/* open patch file for diff block */
	if ((dpf = fopen(patch_path, "rb")) == NULL)
		FAIL(HOTCODEPUSH_BSPATCH_IO_ERROR);
	/* open patch file for extra block */
	if ((epf = fopen(patch_path, "rb")) == NULL)
		FAIL(HOTCODEPUSH_BSPATCH_IO_ERROR);
	/* open oldfile */
	if ((oldfd = open(old_path, O_RDONLY | O_BINARY, 0)) < 0)
		FAIL(HOTCODEPUSH_BSPATCH_IO_ERROR);
	/* open newfile */
	if ((newfd = open(new_path,
	    O_CREAT | O_TRUNC | O_WRONLY | O_BINARY, 0666)) < 0)
		FAIL(HOTCODEPUSH_BSPATCH_IO_ERROR);
	newfile = new_path;

	/*
	File format:
		0	8	"BSDIFF40"
		8	8	X
		16	8	Y
		24	8	sizeof(newfile)
		32	X	bzip2(control block)
		32+X	Y	bzip2(diff block)
		32+X+Y	???	bzip2(extra block)
	with control block a set of triples (x,y,z) meaning "add x bytes
	from oldfile to x bytes from the diff block; copy y bytes from the
	extra block; seek forwards in oldfile by z bytes".
	*/

	/* Read header */
	if (fread(header, 1, HEADER_SIZE, f) < HEADER_SIZE) {
		if (feof(f))
			FAIL(HOTCODEPUSH_BSPATCH_CORRUPT_PATCH);
		FAIL(HOTCODEPUSH_BSPATCH_IO_ERROR);
	}

	/* Check for appropriate magic */
	if (memcmp(header, "BSDIFF40", 8) != 0)
		FAIL(HOTCODEPUSH_BSPATCH_CORRUPT_PATCH);

	/* Read lengths from header */
	if (offtin(header + 8, &bzctrllen) ||
	    offtin(header + 16, &bzdatalen) ||
	    offtin(header + 24, &newsize))
		FAIL(HOTCODEPUSH_BSPATCH_CORRUPT_PATCH);
	if (bzctrllen < 0 || bzctrllen > OFF_MAX - HEADER_SIZE ||
	    bzdatalen < 0 || bzctrllen + HEADER_SIZE > OFF_MAX - bzdatalen ||
	    newsize < 0 || newsize > SSIZE_MAX || newsize > max_new_size)
		FAIL(HOTCODEPUSH_BSPATCH_CORRUPT_PATCH);

	/* Close patch file and re-open it via libbzip2 at the right places */
	if (fclose(f)) {
		f = NULL;
		FAIL(HOTCODEPUSH_BSPATCH_IO_ERROR);
	}
	f = NULL;
	offset = HEADER_SIZE;
	if (fseeko(cpf, offset, SEEK_SET))
		FAIL(HOTCODEPUSH_BSPATCH_IO_ERROR);
	if ((cpfbz2 = BZ2_bzReadOpen(&cbz2err, cpf, 0, 0, NULL, 0)) == NULL)
		FAIL(HOTCODEPUSH_BSPATCH_IO_ERROR);
	if (ckd_add(&offset, offset, bzctrllen))
		FAIL(HOTCODEPUSH_BSPATCH_CORRUPT_PATCH);
	if (fseeko(dpf, offset, SEEK_SET))
		FAIL(HOTCODEPUSH_BSPATCH_IO_ERROR);
	if ((dpfbz2 = BZ2_bzReadOpen(&dbz2err, dpf, 0, 0, NULL, 0)) == NULL)
		FAIL(HOTCODEPUSH_BSPATCH_IO_ERROR);
	if (ckd_add(&offset, offset, bzdatalen))
		FAIL(HOTCODEPUSH_BSPATCH_CORRUPT_PATCH);
	if (fseeko(epf, offset, SEEK_SET))
		FAIL(HOTCODEPUSH_BSPATCH_IO_ERROR);
	if ((epfbz2 = BZ2_bzReadOpen(&ebz2err, epf, 0, 0, NULL, 0)) == NULL)
		FAIL(HOTCODEPUSH_BSPATCH_IO_ERROR);

	if ((oldsize = lseek(oldfd, 0, SEEK_END)) == -1 ||
	    oldsize > SSIZE_MAX)
		FAIL(HOTCODEPUSH_BSPATCH_IO_ERROR);

	old = mmap(NULL, oldsize+1, PROT_READ, MAP_SHARED, oldfd, 0);
	if (old == MAP_FAILED)
		FAIL(HOTCODEPUSH_BSPATCH_IO_ERROR);
	if (close(oldfd) != 0) {
		oldfd = -1;
		FAIL(HOTCODEPUSH_BSPATCH_IO_ERROR);
	}
	oldfd = -1;

	if ((new = malloc(newsize)) == NULL)
		FAIL(HOTCODEPUSH_BSPATCH_OUT_OF_MEMORY);

	oldpos = 0;
	newpos = 0;
	while (newpos < newsize) {
		/* Read control data */
		for (i = 0; i <= 2; i++) {
			lenread = BZ2_bzRead(&cbz2err, cpfbz2, buf, 8);
			if ((lenread < 8) || ((cbz2err != BZ_OK) &&
			    (cbz2err != BZ_STREAM_END)))
				FAIL(HOTCODEPUSH_BSPATCH_CORRUPT_PATCH);
			if (offtin(buf, &ctrl[i]))
				FAIL(HOTCODEPUSH_BSPATCH_CORRUPT_PATCH);
		}

		/* Sanity-check */
		if (ctrl[0] < 0 || ctrl[0] > INT_MAX ||
		    ctrl[1] < 0 || ctrl[1] > INT_MAX)
			FAIL(HOTCODEPUSH_BSPATCH_CORRUPT_PATCH);

		/* Sanity-check */
		if (ckd_add(&sum, newpos, ctrl[0]) || sum > newsize)
			FAIL(HOTCODEPUSH_BSPATCH_CORRUPT_PATCH);

		/* Read diff string */
		lenread = BZ2_bzRead(&dbz2err, dpfbz2, new + newpos, (int)ctrl[0]);
		if ((lenread < ctrl[0]) ||
		    ((dbz2err != BZ_OK) && (dbz2err != BZ_STREAM_END)))
			FAIL(HOTCODEPUSH_BSPATCH_CORRUPT_PATCH);

		/* Add old data to diff string */
		for (i = 0; i < ctrl[0]; i++) {
			if (ckd_add(&sum, oldpos, i))
				FAIL(HOTCODEPUSH_BSPATCH_CORRUPT_PATCH);
			if (sum >= 0 && sum < oldsize)
				new[newpos + i] += old[oldpos + i];
		}

		/* Adjust pointers */
		if (ckd_add(&newpos, newpos, ctrl[0]) ||
		    ckd_add(&oldpos, oldpos, ctrl[0]))
			FAIL(HOTCODEPUSH_BSPATCH_CORRUPT_PATCH);

		/* Sanity-check */
		if (ckd_add(&sum, newpos, ctrl[1]) || sum > newsize)
			FAIL(HOTCODEPUSH_BSPATCH_CORRUPT_PATCH);

		/* Read extra string */
		lenread = BZ2_bzRead(&ebz2err, epfbz2, new + newpos, (int)ctrl[1]);
		if ((lenread < ctrl[1]) ||
		    ((ebz2err != BZ_OK) && (ebz2err != BZ_STREAM_END)))
			FAIL(HOTCODEPUSH_BSPATCH_CORRUPT_PATCH);

		/* Adjust pointers */
		if (ckd_add(&newpos, newpos, ctrl[1]) ||
		    ckd_add(&oldpos, oldpos, ctrl[2]))
			FAIL(HOTCODEPUSH_BSPATCH_CORRUPT_PATCH);
	}

	/* Write the new file */
	if (write(newfd, new, newsize) != newsize)
		FAIL(HOTCODEPUSH_BSPATCH_IO_ERROR);
	if (close(newfd) == -1) {
		newfd = -1;
		FAIL(HOTCODEPUSH_BSPATCH_IO_ERROR);
	}
	newfd = -1;
	/* Disable the cleanup's unlink */
	newfile = NULL;

cleanup:
	/* Clean up the bzip2 reads */
	if (cpfbz2 != NULL)
		BZ2_bzReadClose(&cbz2err, cpfbz2);
	if (dpfbz2 != NULL)
		BZ2_bzReadClose(&dbz2err, dpfbz2);
	if (epfbz2 != NULL)
		BZ2_bzReadClose(&ebz2err, epfbz2);
	if (f != NULL)
		fclose(f);
	if (cpf != NULL)
		fclose(cpf);
	if (dpf != NULL)
		fclose(dpf);
	if (epf != NULL)
		fclose(epf);
	if (oldfd != -1)
		close(oldfd);
	if (newfd != -1)
		close(newfd);
	if (newfile != NULL)
		unlink(newfile);

	free(new);
	if (old != MAP_FAILED)
		munmap(old, oldsize+1);

	return (status);
}
