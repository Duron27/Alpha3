#ifndef ALPHA3_SUPPORT_METAL_PROBE_H
#define ALPHA3_SUPPORT_METAL_PROBE_H

// The part of src/metal/device.hpp plain C++ may call (the rest is Objective-C++)
namespace Alpha3::Mtl
{
    // Whether this device can run the renderer at all, before the window is made: false means OpenGL ES
    bool probe();
}

#endif
