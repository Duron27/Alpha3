import torch, torch.nn as nn, torch.nn.functional as F
class Compact(nn.Module):
    """SRVGGNetCompact-style (Real-ESRGAN's compact net, BSD-3): 3x3 convs with PReLU, a pixel shuffle to twice the
    size, added to the input upscaled bilinearly. Small enough to run on a phone's CPU."""
    def __init__(self, feat=32, convs=8, scale=2):
        super().__init__()
        self.scale = scale
        layers = [nn.Conv2d(3, feat, 3, 1, 1), nn.PReLU(feat)]
        for _ in range(convs):
            layers += [nn.Conv2d(feat, feat, 3, 1, 1), nn.PReLU(feat)]
        layers += [nn.Conv2d(feat, 3 * scale * scale, 3, 1, 1)]
        self.body = nn.Sequential(*layers)
        self.shuffle = nn.PixelShuffle(scale)
    def forward(self, x):
        base = F.interpolate(x, scale_factor=self.scale, mode='bilinear', align_corners=False)
        return base + self.shuffle(self.body(x))
