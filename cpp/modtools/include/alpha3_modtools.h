// Alpha3's native mod tools (cpp/modtools, Rust): LOOT's load order sorting and Wabbajack list plans. Each call takes
// a JSON request and returns a JSON answer (see src/lib.rs and src/wabbajack.rs), freed with alpha3_modtools_free.
#pragma once
#ifdef __cplusplus
extern "C" {
#endif

char *alpha3_loot_sort(const char *request_json);
char *alpha3_wabbajack_plan(const char *request_json);
void alpha3_modtools_free(char *answer);
const char *alpha3_modtools_version(void);

#ifdef __cplusplus
}
#endif
