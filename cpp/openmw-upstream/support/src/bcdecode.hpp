#ifndef ALPHA3_SUPPORT_BCDECODE_H
#define ALPHA3_SUPPORT_BCDECODE_H

#include <osg/Image>
#include <osg/ref_ptr>

#include <vector>

namespace Alpha3
{
    /// Decodes a DXT1/3/5 (S3TC) image with all its mip levels into an uncompressed RGB (opaque DXT1) or RGBA image, for a GPU
    /// that cannot sample S3TC. Returns null when the image is not 2D DXT data or is cut short; the caller then decodes it
    /// another way. Block-wise (bcdec), so much faster than asking the image for each texel. Safe from several threads.
    osg::ref_ptr<osg::Image> decodeS3tc(const osg::Image& image);

    /// The top level of a DXT1/3/5 image as tight RGBA8 rows, for reading texels on the CPU. False when it is not one.
    bool decodeS3tcRgba8(const osg::Image& image, std::vector<unsigned char>& rgba);
}

#endif
