#include "ValeBuiltins.h"
#include <string.h>
#include <stdio.h>

ValeInt vtest_extStrLen(ValeStr* haystackContainerStr) {
  char* haystackContainerChars = haystackContainerStr->chars;
  ValeInt result = strlen(haystackContainerChars);
  return result;
}
