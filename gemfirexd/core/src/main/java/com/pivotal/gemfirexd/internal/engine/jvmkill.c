/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <sys/types.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <stdlib.h>
#include <errno.h>
#include <jvmti.h>

#define SKIP_FILE_PATTERN "jvmkill_pid%d.skip"

static FILE* g_logFile = NULL;

int logMessage(jint flags, char *format, ...) {
   // check if file indicating skip OOME errors exists
   if (flags & (JVMTI_RESOURCE_EXHAUSTED_OOM_ERROR | JVMTI_RESOURCE_EXHAUSTED_JAVA_HEAP)) {
     char fname[100];
     sprintf(fname, SKIP_FILE_PATTERN, getpid());
     int result = access(fname, F_OK);
     errno = 0;
     if (result != -1) return 0;
   }
   FILE* logFile = g_logFile;
   if (logFile == NULL) {
     char fname[100];
     sprintf(fname, "jvmkill_pid%d.log", getpid());
     logFile = fopen(fname, "a");
     g_logFile = logFile;
   }
   va_list args;
   va_start(args, format);
   vfprintf(logFile, format, args);
   va_end(args);
   fflush(logFile);
   return 1;
}

static void JNICALL
resourceExhausted(
      jvmtiEnv *jvmti_env,
      JNIEnv *jni_env,
      jint flags,
      const void *reserved,
      const char *description) {
   if (logMessage(flags, "ResourceExhausted: %s: killing current process!", description)) {
     kill(getpid(), SIGKILL);
   }
}

JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
   jvmtiEnv *jvmti;
   jvmtiError err;
   jint rc = (*vm)->GetEnv(vm, (void **) &jvmti, JVMTI_VERSION);
   if (rc != JNI_OK) {
      logMessage(0, "ERROR: GetEnv failed: %d\n", rc);
      return JNI_ERR;
   }

   jvmtiEventCallbacks callbacks;
   memset(&callbacks, 0, sizeof(callbacks));

   callbacks.ResourceExhausted = &resourceExhausted;

   err = (*jvmti)->SetEventCallbacks(jvmti, &callbacks, sizeof(callbacks));
   if (err != JVMTI_ERROR_NONE) {
      logMessage(0, "ERROR: SetEventCallbacks failed: %d\n", err);
      return JNI_ERR;
   }

   err = (*jvmti)->SetEventNotificationMode(
         jvmti, JVMTI_ENABLE, JVMTI_EVENT_RESOURCE_EXHAUSTED, NULL);
   if (err != JVMTI_ERROR_NONE) {
      logMessage(0, "ERROR: SetEventNotificationMode failed: %d\n", err);
      return JNI_ERR;
   }

   // remove any .skip file
   char fname[100];
   sprintf(fname, SKIP_FILE_PATTERN, getpid());
   remove(fname);
   errno = 0;

   return JNI_OK;
}
