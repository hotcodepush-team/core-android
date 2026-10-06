#include <jni.h>
#include <stdint.h>

#include "HotCodePushBspatch.h"

/* off_t is 32 bits on Android's 32-bit ABIs, where a bound past it is no bound. */
static off_t resolve_max_new_size(jlong max_new_size)
{
	if (sizeof(off_t) < sizeof(jlong) && max_new_size > INT32_MAX)
		return INT32_MAX;
	return (off_t)max_new_size;
}

/* Bspatch.applyPatch: the status of hotcodepush_bspatch(), or an OutOfMemoryError when a path cannot be read. */
JNIEXPORT jint JNICALL
Java_com_hotcodepush_core_Bspatch_applyPatch(JNIEnv *env, jclass bspatch,
    jstring old_path, jstring new_path, jstring patch_path, jlong max_new_size)
{
	const char *old_chars = NULL, *new_chars = NULL, *patch_chars = NULL;
	jint status = HOTCODEPUSH_BSPATCH_OUT_OF_MEMORY;

	(void)bspatch;
	if ((old_chars = (*env)->GetStringUTFChars(env, old_path, NULL)) != NULL &&
	    (new_chars = (*env)->GetStringUTFChars(env, new_path, NULL)) != NULL &&
	    (patch_chars = (*env)->GetStringUTFChars(env, patch_path, NULL)) != NULL)
		status = hotcodepush_bspatch(old_chars, new_chars, patch_chars,
		    resolve_max_new_size(max_new_size));
	if (patch_chars != NULL)
		(*env)->ReleaseStringUTFChars(env, patch_path, patch_chars);
	if (new_chars != NULL)
		(*env)->ReleaseStringUTFChars(env, new_path, new_chars);
	if (old_chars != NULL)
		(*env)->ReleaseStringUTFChars(env, old_path, old_chars);
	return status;
}
