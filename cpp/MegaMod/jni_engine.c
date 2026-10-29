/* src/jni_engine.c – UPDATED FOR UQMKt */
#include <jni.h>
#include <stdlib.h>
#include <SDL.h>
#include "uqm/globdata.h"
#include "uqm/sis.h"
#include "uqm/encount.h"
#include "uqm/units.h"
#include "uqm/setup.h"
#include "uqm/ship.h"
#include "uqm/races.h"
#include "uqm/clock.h"
#include "uqm/starmap.h"
#include "uqm/displist.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ---------- Activity & Battle ---------- */
JNIEXPORT jint JNICALL
Java_org_openmw_utils_UQMKt_getActivity(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return (jint)GLOBAL(CurrentActivity);
}

JNIEXPORT jboolean JNICALL
Java_org_openmw_utils_UQMKt_isInBattle(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return (GLOBAL(CurrentActivity) & IN_BATTLE) != 0;
}

/* ---------- Ship screen position ---------- */
JNIEXPORT jint JNICALL
Java_org_openmw_utils_UQMKt_getShipScreenX(JNIEnv *env, jclass clazz) {
    return (jint)GLOBAL(ShipStamp.origin.x);
}

JNIEXPORT jint JNICALL
Java_org_openmw_utils_UQMKt_getShipScreenY(JNIEnv *env, jclass clazz) {
    return (jint)GLOBAL(ShipStamp.origin.y);
}

/* ---------- True universe position (log) ---------- */
JNIEXPORT jint JNICALL
Java_org_openmw_utils_UQMKt_getLogX(JNIEnv *env, jclass clazz) {
    return (jint)GLOBAL_SIS(log_x);
}

JNIEXPORT jint JNICALL
Java_org_openmw_utils_UQMKt_getLogY(JNIEnv *env, jclass clazz) {
    return (jint)GLOBAL_SIS(log_y);
}

/* ---------- Autopilot destination ---------- */
JNIEXPORT jint JNICALL
Java_org_openmw_utils_UQMKt_getAutopilotX(JNIEnv *env, jclass clazz) {
    return (jint)GLOBAL(autopilot.x);
}

JNIEXPORT jint JNICALL
Java_org_openmw_utils_UQMKt_getAutopilotY(JNIEnv *env, jclass clazz) {
    return (jint)GLOBAL(autopilot.y);
}

/* ---------- Inter-planetary cursor ---------- */
JNIEXPORT jint JNICALL
Java_org_openmw_utils_UQMKt_getIPLocationX(JNIEnv *env, jclass clazz) {
    return (jint)GLOBAL(ip_location.x);
}

JNIEXPORT jint JNICALL
Java_org_openmw_utils_UQMKt_getIPLocationY(JNIEnv *env, jclass clazz) {
    return (jint)GLOBAL(ip_location.y);
}

/* ---------- Resources ---------- */
JNIEXPORT jint JNICALL
Java_org_openmw_utils_UQMKt_getCrew(JNIEnv *env, jclass clazz) {
    return (jint)GLOBAL_SIS(CrewEnlisted);
}

JNIEXPORT jint JNICALL
Java_org_openmw_utils_UQMKt_getFuel(JNIEnv *env, jclass clazz) {
    return (jint)GLOBAL_SIS(FuelOnBoard);
}

JNIEXPORT jint JNICALL
Java_org_openmw_utils_UQMKt_getRU(JNIEnv *env, jclass clazz) {
    return (jint)GLOBAL_SIS(ResUnits);
}

/* ---------- Minerals ---------- */
JNIEXPORT void JNICALL
Java_org_openmw_utils_UQMKt_getMinerals(JNIEnv *env, jclass clazz, jintArray out) {
    jint tmp[8];
    for (int i = 0; i < 8; ++i)
        tmp[i] = (jint)GLOBAL_SIS(ElementAmounts[i]);
    (*env)->SetIntArrayRegion(env, out, 0, 8, tmp);
}

/* ---------- Drive Slots ---------- */
JNIEXPORT void JNICALL
Java_org_openmw_utils_UQMKt_getDriveSlots(JNIEnv *env, jclass clazz, jbyteArray out) {
    jbyte tmp[11]; // NUM_DRIVE_SLOTS
    for (int i = 0; i < 11; ++i)
        tmp[i] = (jbyte)GLOBAL_SIS(DriveSlots[i]);
    (*env)->SetByteArrayRegion(env, out, 0, 11, tmp);
}

/* ---------- Jet Slots ---------- */
JNIEXPORT void JNICALL
Java_org_openmw_utils_UQMKt_getJetSlots(JNIEnv *env, jclass clazz, jbyteArray out) {
    jbyte tmp[8]; // NUM_JET_SLOTS
    for (int i = 0; i < 8; ++i)
        tmp[i] = (jbyte)GLOBAL_SIS(JetSlots[i]);
    (*env)->SetByteArrayRegion(env, out, 0, 8, tmp);
}

/* ---------- Modules ---------- */
JNIEXPORT void JNICALL
Java_org_openmw_utils_UQMKt_getModules(JNIEnv *env, jclass clazz, jbyteArray out) {
    jbyte tmp[16];
    for (int i = 0; i < 16; ++i)
        tmp[i] = (jbyte)GLOBAL_SIS(ModuleSlots[i]);
    (*env)->SetByteArrayRegion(env, out, 0, 16, tmp);
}

/* ---------- Names ---------- */
JNIEXPORT jstring JNICALL
Java_org_openmw_utils_UQMKt_getShipName(JNIEnv *env, jclass clazz) {
    (void)clazz;
    return (*env)->NewStringUTF(env, GLOBAL_SIS(ShipName));
}

JNIEXPORT jstring JNICALL
Java_org_openmw_utils_UQMKt_getCommanderName(JNIEnv *env, jclass clazz) {
    (void)clazz;
    return (*env)->NewStringUTF(env, GLOBAL_SIS(CommanderName));
}

/* ---------- Ship movement & state ---------- */
JNIEXPORT jint JNICALL
Java_org_openmw_utils_UQMKt_getShipFacing(JNIEnv *env, jclass clazz) {
    return (jint)GLOBAL(ShipFacing);
}

JNIEXPORT jint JNICALL
Java_org_openmw_utils_UQMKt_getTravelAngle(JNIEnv *env, jclass clazz) {
    return (jint)GLOBAL(velocity.TravelAngle);
}

JNIEXPORT jfloat JNICALL
Java_org_openmw_utils_UQMKt_getSpeedX(JNIEnv *env, jclass clazz) {
    return (jfloat)GLOBAL(velocity.vector.width) / 32.0f;
}

JNIEXPORT jfloat JNICALL
Java_org_openmw_utils_UQMKt_getSpeedY(JNIEnv *env, jclass clazz) {
    return (jfloat)GLOBAL(velocity.vector.height) / 32.0f;
}

JNIEXPORT jboolean JNICALL
Java_org_openmw_utils_UQMKt_isInOrbit(JNIEnv *env, jclass clazz) {
    return (jboolean)GLOBAL(in_orbit);
}

JNIEXPORT jint JNICALL
Java_org_openmw_utils_UQMKt_getIPPlanet(JNIEnv *env, jclass clazz) {
    return (jint)GLOBAL(ip_planet);
}

JNIEXPORT jint JNICALL
Java_org_openmw_utils_UQMKt_getEncounterRace(JNIEnv *env, jclass clazz) {
    extern SIZE EncounterRace;
    return (jint)EncounterRace;
}

JNIEXPORT jint JNICALL
Java_org_openmw_utils_UQMKt_getBattleRace(JNIEnv *env, jclass clazz, jint side) {
    (void)env; (void)clazz;
    if (side < 0 || side >= 2) return -1;

    HSTARSHIP hStarShip = GetHeadLink(&race_q[side]);
    if (hStarShip == 0) return -1;

    STARSHIP *StarShipPtr = LockStarShip(&race_q[side], hStarShip);
    jint species = (jint)StarShipPtr->SpeciesID;
    UnlockStarShip(&race_q[side], hStarShip);

    return species;
}

/* ---------- More game state ----------
 * Everything below reads engine globals the same way the functions above do; nothing here writes to the game. */

/* Game clock: day, month, year, tick count, ticks in a day */
JNIEXPORT void JNICALL
Java_org_openmw_utils_UQMKt_getGameClock(JNIEnv *env, jclass clazz, jintArray out) {
    (void)clazz;
    jint tmp[5] = {
        (jint)GLOBAL(GameClock.day_index), (jint)GLOBAL(GameClock.month_index), (jint)GLOBAL(GameClock.year_index),
        (jint)GLOBAL(GameClock.tick_count), (jint)GLOBAL(GameClock.day_in_ticks)
    };
    (*env)->SetIntArrayRegion(env, out, 0, 5, tmp);
}

/* Ship and save details: element mass, bio mass, landers, difficulty, extended, nomad, ship seed, seed, save version,
 * glob_flags (combat speed, music/sound disabled, cyborg) */
JNIEXPORT void JNICALL
Java_org_openmw_utils_UQMKt_getSisInfo(JNIEnv *env, jclass clazz, jintArray out) {
    (void)clazz;
    jint tmp[10] = {
        (jint)GLOBAL_SIS(TotalElementMass), (jint)GLOBAL_SIS(TotalBioMass), (jint)GLOBAL_SIS(NumLanders),
        (jint)GLOBAL_SIS(Difficulty), (jint)GLOBAL_SIS(Extended), (jint)GLOBAL_SIS(Nomad),
        (jint)GLOBAL_SIS(ShipSeed), (jint)GLOBAL_SIS(Seed), (jint)GLOBAL_SIS(SaveVersion),
        (jint)GLOBAL(glob_flags)
    };
    (*env)->SetIntArrayRegion(env, out, 0, 10, tmp);
}

/* Name of the planet the lander is at / was last at */
JNIEXPORT jstring JNICALL
Java_org_openmw_utils_UQMKt_getPlanetName(JNIEnv *env, jclass clazz) {
    (void)clazz;
    return (*env)->NewStringUTF(env, GLOBAL_SIS(PlanetName));
}

/* Prices: crew, fuel, then every module, then every mineral category */
JNIEXPORT jintArray JNICALL
Java_org_openmw_utils_UQMKt_getCosts(JNIEnv *env, jclass clazz) {
    (void)clazz;
    enum { N = 2 + NUM_MODULES + NUM_ELEMENT_CATEGORIES };
    jint tmp[N];
    int n = 0, i;
    tmp[n++] = (jint)GLOBAL(CrewCost);
    tmp[n++] = (jint)GLOBAL(FuelCost);
    for (i = 0; i < NUM_MODULES; ++i) tmp[n++] = (jint)GLOBAL(ModuleCost[i]);
    for (i = 0; i < NUM_ELEMENT_CATEGORIES; ++i) tmp[n++] = (jint)GLOBAL(ElementWorth[i]);
    jintArray result = (*env)->NewIntArray(env, N);
    if (result) (*env)->SetIntArrayRegion(env, result, 0, N, tmp);
    return result;
}

/* Sizes of the engine's lists: escort ships, stowed ships, enemy ships, solar system groups, hyperspace encounters */
JNIEXPORT void JNICALL
Java_org_openmw_utils_UQMKt_getQueueCounts(JNIEnv *env, jclass clazz, jintArray out) {
    (void)clazz;
    jint tmp[5] = {
        (jint)CountLinks(&GLOBAL(built_ship_q)), (jint)CountLinks(&GLOBAL(stowed_ship_q)),
        (jint)CountLinks(&GLOBAL(npc_built_ship_q)), (jint)CountLinks(&GLOBAL(ip_group_q)),
        (jint)CountLinks(&GLOBAL(encounter_q))
    };
    (*env)->SetIntArrayRegion(env, out, 0, 5, tmp);
}

/* Velocity: travel angle, vector w/h, fraction w/h, increment w/h */
JNIEXPORT void JNICALL
Java_org_openmw_utils_UQMKt_getVelocity(JNIEnv *env, jclass clazz, jintArray out) {
    (void)clazz;
    jint tmp[7] = {
        (jint)GLOBAL(velocity.TravelAngle),
        (jint)GLOBAL(velocity.vector.width), (jint)GLOBAL(velocity.vector.height),
        (jint)GLOBAL(velocity.fract.width), (jint)GLOBAL(velocity.fract.height),
        (jint)GLOBAL(velocity.incr.width), (jint)GLOBAL(velocity.incr.height)
    };
    (*env)->SetIntArrayRegion(env, out, 0, 7, tmp);
}

/* Where the ship is on the star map: x, y, star type, star index, prefix, postfix. False when there is no current star. */
JNIEXPORT jboolean JNICALL
Java_org_openmw_utils_UQMKt_getCurrentStar(JNIEnv *env, jclass clazz, jintArray out) {
    (void)clazz;
    if (CurStarDescPtr == NULL) return JNI_FALSE;
    jint tmp[6] = {
        (jint)CurStarDescPtr->star_pt.x, (jint)CurStarDescPtr->star_pt.y,
        (jint)CurStarDescPtr->Type, (jint)CurStarDescPtr->Index,
        (jint)CurStarDescPtr->Prefix, (jint)CurStarDescPtr->Postfix
    };
    (*env)->SetIntArrayRegion(env, out, 0, 6, tmp);
    return JNI_TRUE;
}

/* Every alien race, FLEET_STRIDE numbers each: species id, allied state (0 good, 1 bad, 2 dead), days left, crew,
 * max crew, x, y, actual strength, known strength, known x, known y, destination x, destination y, can build */
#define FLEET_STRIDE 14
JNIEXPORT jintArray JNICALL
Java_org_openmw_utils_UQMKt_getFleets(JNIEnv *env, jclass clazz) {
    (void)clazz;
    int count = (int)CountLinks(&GLOBAL(avail_race_q));
    jint *tmp = (jint *)calloc(count > 0 ? (size_t)count * FLEET_STRIDE : 1, sizeof(jint));
    int n = 0;
    HFLEETINFO h, next;
    for (h = GetHeadLink(&GLOBAL(avail_race_q)); h != 0 && n < count * FLEET_STRIDE; h = next) {
        FLEET_INFO *f = LockFleetInfo(&GLOBAL(avail_race_q), h);
        next = _GetSuccLink(f);
        tmp[n++] = (jint)f->SpeciesID;      tmp[n++] = (jint)f->allied_state;
        tmp[n++] = (jint)f->days_left;      tmp[n++] = (jint)f->crew_level;
        tmp[n++] = (jint)f->max_crew;       tmp[n++] = (jint)f->loc.x;
        tmp[n++] = (jint)f->loc.y;          tmp[n++] = (jint)f->actual_strength;
        tmp[n++] = (jint)f->known_strength; tmp[n++] = (jint)f->known_loc.x;
        tmp[n++] = (jint)f->known_loc.y;    tmp[n++] = (jint)f->dest_loc.x;
        tmp[n++] = (jint)f->dest_loc.y;     tmp[n++] = (jint)f->can_build;
        UnlockFleetInfo(&GLOBAL(avail_race_q), h);
    }
    jintArray result = (*env)->NewIntArray(env, n);
    if (result && n > 0) (*env)->SetIntArrayRegion(env, result, 0, n, tmp);
    free(tmp);
    return result;
}

/* The escort ships: race id, crew, max crew per ship (stride 3) */
JNIEXPORT jintArray JNICALL
Java_org_openmw_utils_UQMKt_getEscortShips(JNIEnv *env, jclass clazz) {
    (void)clazz;
    int count = (int)CountLinks(&GLOBAL(built_ship_q));
    jint *tmp = (jint *)calloc(count > 0 ? (size_t)count * 3 : 1, sizeof(jint));
    int n = 0;
    HSHIPFRAG h, next;
    for (h = GetHeadLink(&GLOBAL(built_ship_q)); h != 0 && n < count * 3; h = next) {
        SHIP_FRAGMENT *f = LockShipFrag(&GLOBAL(built_ship_q), h);
        next = _GetSuccLink(f);
        tmp[n++] = (jint)f->race_id;
        tmp[n++] = (jint)f->crew_level;
        tmp[n++] = (jint)f->max_crew;
        UnlockShipFrag(&GLOBAL(built_ship_q), h);
    }
    jintArray result = (*env)->NewIntArray(env, n);
    if (result && n > 0) (*env)->SetIntArrayRegion(env, result, 0, n, tmp);
    free(tmp);
    return result;
}

/* ---------- The named game state ----------
 * The engine keeps its story flags (alliances, quests, what has been met or learned) as named values; the names come
 * from its Lua definitions. getGameStateNames and getGameStateValues are the same length and order. */
JNIEXPORT jobjectArray JNICALL
Java_org_openmw_utils_UQMKt_getGameStateNames(JNIEnv *env, jclass clazz) {
    (void)clazz;
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    size_t i, count = 0;
    if (gameStateBitMap != NULL)
        for (i = 0; i < gameStateBitMapCount; ++i) if (gameStateBitMap[i].name != NULL) ++count;
    jobjectArray result = (*env)->NewObjectArray(env, (jsize)count, stringClass, NULL);
    size_t n = 0;
    if (result && gameStateBitMap != NULL) {
        for (i = 0; i < gameStateBitMapCount; ++i) {
            if (gameStateBitMap[i].name == NULL) continue;
            jstring name = (*env)->NewStringUTF(env, gameStateBitMap[i].name);
            (*env)->SetObjectArrayElement(env, result, (jsize)n++, name);
            (*env)->DeleteLocalRef(env, name);
        }
    }
    return result;
}

JNIEXPORT jintArray JNICALL
Java_org_openmw_utils_UQMKt_getGameStateValues(JNIEnv *env, jclass clazz) {
    (void)clazz;
    size_t i, count = 0;
    if (gameStateBitMap != NULL)
        for (i = 0; i < gameStateBitMapCount; ++i) if (gameStateBitMap[i].name != NULL) ++count;
    jintArray result = (*env)->NewIntArray(env, (jsize)count);
    if (!result || count == 0) return result;
    jint *tmp = (jint *)malloc(count * sizeof(jint));
    size_t n = 0;
    for (i = 0; i < gameStateBitMapCount; ++i)
        if (gameStateBitMap[i].name != NULL) tmp[n++] = (jint)getGameStateUint(gameStateBitMap[i].name);
    (*env)->SetIntArrayRegion(env, result, 0, (jsize)n, tmp);
    free(tmp);
    return result;
}

JNIEXPORT jint JNICALL
Java_org_openmw_utils_UQMKt_getGameStateValue(JNIEnv *env, jclass clazz, jstring name) {
    (void)clazz;
    if (name == NULL) return -1;
    const char *chars = (*env)->GetStringUTFChars(env, name, NULL);
    if (chars == NULL) return -1;
    jint value = (jint)getGameStateUint(chars);
    (*env)->ReleaseStringUTFChars(env, name, chars);
    return value;
}

/* The ship a side is fighting with (or has just chosen going into battle): species id, crew, max crew, captain name
 * index, original queue index, player number, control flags, flagship (1) or escort (0), ships that side has queued
 * including this one. False when that side has no ship. side 0 is the player, 1 the opponent. */
JNIEXPORT jboolean JNICALL
Java_org_openmw_utils_UQMKt_getBattleShip(JNIEnv *env, jclass clazz, jint side, jintArray out) {
    (void)clazz;
    if (side < 0 || side >= 2) return JNI_FALSE;
    HSTARSHIP h = GetHeadLink(&race_q[side]);
    if (h == 0) return JNI_FALSE;
    STARSHIP *ship = LockStarShip(&race_q[side], h);
    jint tmp[9] = {
        (jint)ship->SpeciesID, (jint)ship->crew_level, (jint)ship->max_crew, (jint)ship->captains_name_index,
        (jint)ship->index, (jint)ship->playerNr, (jint)ship->control, (jint)(ship->captains_name_index == 0),
        (jint)CountLinks(&race_q[side])
    };
    UnlockStarShip(&race_q[side], h);
    (*env)->SetIntArrayRegion(env, out, 0, 9, tmp);
    return JNI_TRUE;
}

/* ---------- Mouse (SDLActivity natives the launcher polls) ----------
 * UQM has no pointer of its own; it only wants one while its ImGui menu is up. The launcher shows its mouse controls
 * (and the cursor) when isMouseShown() is non-zero. */
extern int UQM_ImGui_IsMenuVisible (void);

JNIEXPORT jint JNICALL
Java_org_libsdl_app_SDLActivity_isMouseShown(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return UQM_ImGui_IsMenuVisible () ? 1 : 0;
}

JNIEXPORT jint JNICALL
Java_org_libsdl_app_SDLActivity_getMouseX(JNIEnv *env, jclass clazz) {
    int x = 0;
    (void)env; (void)clazz;
    SDL_GetMouseState (&x, NULL);
    return (jint)x;
}

JNIEXPORT jint JNICALL
Java_org_libsdl_app_SDLActivity_getMouseY(JNIEnv *env, jclass clazz) {
    int y = 0;
    (void)env; (void)clazz;
    SDL_GetMouseState (NULL, &y);
    return (jint)y;
}

#ifdef __cplusplus
}
#endif
