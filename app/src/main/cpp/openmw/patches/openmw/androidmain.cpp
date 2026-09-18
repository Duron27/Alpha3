#if defined(stderr) && (__ANDROID_API__ < 23)
int stderr = 0; // Hack: fix linker error
#endif

#include "SDL_main.h"
#include "engine.hpp"
#include "mwbase/environment.hpp"
#include "mwbase/statemanager.hpp"
#include "mwbase/windowmanager.hpp"
#include "mwbase/world.hpp"
#include "mwclass/classes.hpp"
#include "mwmechanics/creaturestats.hpp"
#include "mwmechanics/drawstate.hpp"
#include "mwmechanics/spells.hpp"
#include "mwstate/statemanagerimp.hpp"
#include "mwworld/cellstore.hpp"
#include "mwworld/class.hpp"
#include "mwworld/inventorystore.hpp"
#include "mwworld/ptr.hpp"
#include <SDL_events.h>
#include <SDL_gamecontroller.h>
#include <SDL_hints.h>
#include <SDL_mouse.h>
#include <components/vfs/pathutil.hpp>

#include <osg/GraphicsContext>
#include <osg/OperationThread>

/*******************************************************************************
 * JNA / JNI C Exports & SDL Hooks
 *******************************************************************************/

#include <jni.h>

extern void SDL_Android_Init(JNIEnv* env, jclass cls);
extern int argcData;
extern const char** argvData;
void releaseArgv();

namespace
{
inline MWWorld::Ptr getPlayerPtrSafely()
{
  try
  {
    auto& env = MWBase::Environment::get();
    auto world = env.getWorld();
    if (world)
      return world->getPlayerPtr();
  }
  catch (...)
  {
  }
  return MWWorld::Ptr();
}

inline jstring c_str_to_jstring(JNIEnv* env, const char* str)
{
  if (!str)
    return env->NewStringUTF("");
  return env->NewStringUTF(str);
}
}

static osg::GraphicsContext* ctx;

class CtxReleaseOperation : public osg::Operation
{
public:
  virtual void operator()(osg::Object* caller) { ctx->releaseContext(); }
};

class CtxAcquireOperation : public osg::Operation
{
public:
  virtual void operator()(osg::Object* caller) { ctx->makeCurrent(); }
};

extern "C"
{

// JNA Surface Lifecycle management
void omw_surface_destroyed()
{
  try {
    osg::GraphicsContext::GraphicsContexts contexts = osg::GraphicsContext::getRegisteredGraphicsContexts(0);
    if (!contexts.empty() && contexts[0])
    {
      osg::ref_ptr<CtxReleaseOperation> op = new CtxReleaseOperation();
      ctx = contexts[0];
      ctx->add(op);
    }
  } catch (...) {}
}

void omw_surface_recreated()
{
  try {
    osg::GraphicsContext::GraphicsContexts contexts = osg::GraphicsContext::getRegisteredGraphicsContexts(0);
    if (!contexts.empty() && contexts[0])
    {
      osg::ref_ptr<CtxAcquireOperation> op = new CtxAcquireOperation();
      ctx = contexts[0];
      ctx->add(op);
    }
  } catch (...) {}
}

bool omw_is_game_running()
{
  try
  {
    auto& envRef = MWBase::Environment::get();
    auto stateMgr = envRef.getStateManager();
    return stateMgr && stateMgr->getState() == MWState::StateManager::State_Running;
  }
  catch (...)
  {
    return false;
  }
}

void omw_quit_game()
{
  try
  {
    auto& envRef = MWBase::Environment::get();
    auto stateMgr = envRef.getStateManager();
    if (stateMgr)
      stateMgr->requestQuit();
  }
  catch (...)
  {
  }
}

float omw_get_player_health_current()
{
  try
  {
    auto player = getPlayerPtrSafely();
    if (!player.isEmpty())
      return player.getClass().getCreatureStats(player).getHealth().getCurrent();
  }
  catch (...)
  {
  }
  return 0.f;
}

float omw_get_player_health_modified()
{
  try
  {
    auto player = getPlayerPtrSafely();
    if (!player.isEmpty())
      return player.getClass().getCreatureStats(player).getHealth().getModified();
  }
  catch (...)
  {
  }
  return 0.f;
}

float omw_get_player_health_base()
{
  try
  {
    auto player = getPlayerPtrSafely();
    if (!player.isEmpty())
      return player.getClass().getCreatureStats(player).getHealth().getBase();
  }
  catch (...)
  {
  }
  return 0.f;
}

float omw_get_player_magicka_current()
{
  try
  {
    auto player = getPlayerPtrSafely();
    if (!player.isEmpty())
      return player.getClass().getCreatureStats(player).getMagicka().getCurrent();
  }
  catch (...)
  {
  }
  return 0.f;
}

float omw_get_player_magicka_modified()
{
  try
  {
    auto player = getPlayerPtrSafely();
    if (!player.isEmpty())
      return player.getClass().getCreatureStats(player).getMagicka().getModified();
  }
  catch (...)
  {
  }
  return 0.f;
}

float omw_get_player_fatigue_current()
{
  try
  {
    auto player = getPlayerPtrSafely();
    if (!player.isEmpty())
      return player.getClass().getCreatureStats(player).getFatigue().getCurrent();
  }
  catch (...)
  {
  }
  return 0.f;
}

float omw_get_player_fatigue_modified()
{
  try
  {
    auto player = getPlayerPtrSafely();
    if (!player.isEmpty())
      return player.getClass().getCreatureStats(player).getFatigue().getModified();
  }
  catch (...)
  {
  }
  return 0.f;
}

int omw_get_player_level()
{
  try
  {
    auto player = getPlayerPtrSafely();
    if (!player.isEmpty())
      return player.getClass().getCreatureStats(player).getLevel();
  }
  catch (...)
  {
  }
  return 0;
}

float omw_get_player_x()
{
  try
  {
    auto player = getPlayerPtrSafely();
    if (!player.isEmpty())
      return player.getRefData().getPosition().asVec3().x();
  }
  catch (...)
  {
  }
  return 0.f;
}

float omw_get_player_y()
{
  try
  {
    auto player = getPlayerPtrSafely();
    if (!player.isEmpty())
      return player.getRefData().getPosition().asVec3().y();
  }
  catch (...)
  {
  }
  return 0.f;
}

float omw_get_player_z()
{
  try
  {
    auto player = getPlayerPtrSafely();
    if (!player.isEmpty())
      return player.getRefData().getPosition().asVec3().z();
  }
  catch (...)
  {
  }
  return 0.f;
}

static std::string g_cellNameCache;
const char* omw_get_player_cell_name()
{
  try
  {
    auto player = getPlayerPtrSafely();
    if (!player.isEmpty() && player.isInCell() && player.getCell())
    {
      auto cell = player.getCell()->getCell();
      if (cell)
      {
        auto name = cell->getDisplayName();
        if (name.empty())
          name = cell->getNameId();
        g_cellNameCache = std::string(name);
        return g_cellNameCache.c_str();
      }
    }
  }
  catch (...)
  {
  }
  return omw_is_game_running() ? "Unknown Cell" : "Main Menu / Loading";
}

static std::string g_inventorySummaryCache;
const char* omw_get_player_inventory_summary()
{
  try
  {
    auto player = getPlayerPtrSafely();
    if (!player.isEmpty() && player.getClass().hasInventoryStore(player))
    {
      auto& invStore = player.getClass().getInventoryStore(player);
      std::string summary;
      int count = 0;
      for (auto it = invStore.begin(); it != invStore.end(); ++it)
      {
        if (it->isEmpty()) continue;
        if (!it->getClass().showsInInventory(*it)) continue;
        if (count > 0) summary += ", ";
        int itemCount = 1;
        try {
          itemCount = it->getCellRef().getCount();
        } catch (...) {}
        summary += std::string(it->getClass().getName(*it)) + " (x" + std::to_string(itemCount) + ")";
        count++;
        if (count > 30) { summary += ", ..."; break; }
      }
      g_inventorySummaryCache = summary.empty() ? "(Empty Inventory)" : summary;
      return g_inventorySummaryCache.c_str();
    }
  }
  catch (...)
  {
  }
  return omw_is_game_running() ? "(No Inventory)" : "Main Menu / Loading";
}

int omw_get_draw_state()
{
  try
  {
    auto player = getPlayerPtrSafely();
    if (!player.isEmpty())
      return static_cast<int>(player.getClass().getCreatureStats(player).getDrawState());
  }
  catch (...)
  {
  }
  return 0;
}

static std::string g_selectedWeaponCache;
const char* omw_get_selected_weapon_name()
{
  try
  {
    auto player = getPlayerPtrSafely();
    if (!player.isEmpty() && player.getClass().hasInventoryStore(player))
    {
      auto& invStore = player.getClass().getInventoryStore(player);
      auto it = invStore.getSlot(MWWorld::InventoryStore::Slot_CarriedRight);
      if (it != invStore.end() && !it->isEmpty())
      {
        g_selectedWeaponCache = std::string(it->getClass().getName(*it));
        return g_selectedWeaponCache.c_str();
      }
    }
  }
  catch (...)
  {
  }
  return "";
}

static std::string g_selectedSpellCache;
const char* omw_get_selected_spell_id()
{
  try
  {
    auto player = getPlayerPtrSafely();
    if (!player.isEmpty())
    {
      auto& stats = player.getClass().getCreatureStats(player);
      auto spellId = stats.getSpells().getSelectedSpell();
      if (!spellId.empty())
      {
        g_selectedSpellCache = spellId.toDebugString();
        return g_selectedSpellCache.c_str();
      }
    }
  }
  catch (...)
  {
  }
  try
  {
    auto winMgr = MWBase::Environment::get().getWindowManager();
    if (winMgr)
    {
      auto spellId = winMgr->getSelectedSpell();
      if (!spellId.empty())
      {
        g_selectedSpellCache = spellId.toDebugString();
        return g_selectedSpellCache.c_str();
      }
    }
  }
  catch (...)
  {
  }
  return "";
}

JNIEXPORT jboolean JNICALL Java_org_openmw_EngineActivity_omwIsGameRunning(JNIEnv* env, jobject thiz)
{
  return omw_is_game_running() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_org_openmw_EngineActivity_omwQuitGame(JNIEnv* env, jobject thiz)
{
  omw_quit_game();
}

JNIEXPORT jfloat JNICALL Java_org_openmw_EngineActivity_omwGetPlayerHealthCurrent(JNIEnv* env, jobject thiz)
{
  return omw_get_player_health_current();
}

JNIEXPORT jfloat JNICALL Java_org_openmw_EngineActivity_omwGetPlayerHealthModified(JNIEnv* env, jobject thiz)
{
  return omw_get_player_health_modified();
}

JNIEXPORT jfloat JNICALL Java_org_openmw_EngineActivity_omwGetPlayerHealthBase(JNIEnv* env, jobject thiz)
{
  return omw_get_player_health_base();
}

JNIEXPORT jfloat JNICALL Java_org_openmw_EngineActivity_omwGetPlayerMagickaCurrent(JNIEnv* env, jobject thiz)
{
  return omw_get_player_magicka_current();
}

JNIEXPORT jfloat JNICALL Java_org_openmw_EngineActivity_omwGetPlayerMagickaModified(JNIEnv* env, jobject thiz)
{
  return omw_get_player_magicka_modified();
}

JNIEXPORT jfloat JNICALL Java_org_openmw_EngineActivity_omwGetPlayerFatigueCurrent(JNIEnv* env, jobject thiz)
{
  return omw_get_player_fatigue_current();
}

JNIEXPORT jfloat JNICALL Java_org_openmw_EngineActivity_omwGetPlayerFatigueModified(JNIEnv* env, jobject thiz)
{
  return omw_get_player_fatigue_modified();
}

JNIEXPORT jint JNICALL Java_org_openmw_EngineActivity_omwGetPlayerLevel(JNIEnv* env, jobject thiz)
{
  return omw_get_player_level();
}

JNIEXPORT jfloat JNICALL Java_org_openmw_EngineActivity_omwGetPlayerX(JNIEnv* env, jobject thiz)
{
  return omw_get_player_x();
}

JNIEXPORT jfloat JNICALL Java_org_openmw_EngineActivity_omwGetPlayerY(JNIEnv* env, jobject thiz)
{
  return omw_get_player_y();
}

JNIEXPORT jfloat JNICALL Java_org_openmw_EngineActivity_omwGetPlayerZ(JNIEnv* env, jobject thiz)
{
  return omw_get_player_z();
}

JNIEXPORT jstring JNICALL Java_org_openmw_EngineActivity_omwGetPlayerCellName(JNIEnv* env, jobject thiz)
{
  return c_str_to_jstring(env, omw_get_player_cell_name());
}

JNIEXPORT jstring JNICALL Java_org_openmw_EngineActivity_omwGetPlayerInventorySummary(JNIEnv* env, jobject thiz)
{
  return c_str_to_jstring(env, omw_get_player_inventory_summary());
}

JNIEXPORT jint JNICALL Java_org_openmw_EngineActivity_omwGetDrawState(JNIEnv* env, jobject thiz)
{
  return omw_get_draw_state();
}

JNIEXPORT jstring JNICALL Java_org_openmw_EngineActivity_omwGetSelectedWeaponName(JNIEnv* env, jobject thiz)
{
  return c_str_to_jstring(env, omw_get_selected_weapon_name());
}

JNIEXPORT jstring JNICALL Java_org_openmw_EngineActivity_omwGetSelectedSpellId(JNIEnv* env, jobject thiz)
{
  return c_str_to_jstring(env, omw_get_selected_spell_id());
}

} // extern "C"

extern "C" int Java_org_libsdl_app_SDLActivity_getMouseX(JNIEnv* env, jclass cls)
{
  int ret = 0;
  SDL_GetMouseState(&ret, nullptr);
  return ret;
}

extern "C" int Java_org_libsdl_app_SDLActivity_getMouseY(JNIEnv* env, jclass cls)
{
  int ret = 0;
  SDL_GetMouseState(nullptr, &ret);
  return ret;
}

extern "C" int Java_org_libsdl_app_SDLActivity_isMouseShown(JNIEnv* env, jclass cls)
{
  return SDL_ShowCursor(SDL_QUERY);
}

extern "C" int Java_org_libsdl_app_SDLActivity_nativeInit(JNIEnv* env, jclass cls)
{
  setenv("OPENMW_DECOMPRESS_TEXTURES", "1", 1);

  // On Android, we use a virtual controller with guid="Virtual"
  SDL_GameControllerAddMapping(
      "5669727475616c000000000000000000,Virtual,a:b0,b:b1,back:b15,dpdown:h0.4,dpleft:h0.8,dpright:h0.2,dpup:h0.1,"
      "guide:b16,leftshoulder:b6,leftstick:b13,lefttrigger:a5,leftx:a0,lefty:a1,rightshoulder:b7,rightstick:b14,"
      "righttrigger:a4,rightx:a2,righty:a3,start:b11,x:b3,y:b4");

  SDL_SetHint(SDL_HINT_ANDROID_BLOCK_ON_PAUSE, "0");
  SDL_SetHint(SDL_HINT_ORIENTATIONS, "LandscapeLeft LandscapeRight");

  return 0;
}

extern "C" void JNICALL Java_org_libsdl_app_SDLActivity_omwSurfaceDestroyed(JNIEnv* env, jclass cls)
{
  omw_surface_destroyed();
}

extern "C" void JNICALL Java_org_libsdl_app_SDLActivity_omwSurfaceRecreated(JNIEnv* env, jclass cls)
{
  omw_surface_recreated();
}
