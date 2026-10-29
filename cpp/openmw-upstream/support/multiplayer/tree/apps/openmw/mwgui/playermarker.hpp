#ifndef MWGUI_PLAYERMARKER_H
#define MWGUI_PLAYERMARKER_H

#include <components/esm3/custommarkerstate.hpp>

#include <string>

namespace MWGui
{
    // Another player on the maps (set from Lua, never saved).
    struct PlayerMarker
    {
        ESM::CustomMarker mMarker; // mNote is the player's name
        bool mGroup = false; // in our group: orange; everybody else: green
        bool mExterior = true; // the global map only shows players who are outside
        std::string mInfo; // shown after the name in the tooltip, e.g. how far above or below us they are

        // Tooltip text: the maps are flat, so height goes here
        std::string caption() const { return mInfo.empty() ? mMarker.mNote : mMarker.mNote + ", " + mInfo; }
    };
}

#endif
