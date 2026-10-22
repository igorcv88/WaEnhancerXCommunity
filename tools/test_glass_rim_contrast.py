#!/usr/bin/env python3
"""Numerically execute production AGSL rim expressions; not an AGSL compiler."""
import re
import unittest
from pathlib import Path
import numpy as np
from agsl_check import extract

ROOT = Path(__file__).resolve().parents[1]
SHADER = extract(str(ROOT / 'app/src/main/java/com/waenhancer/theme/KyantLens.java'), 'SHADER')
BLOCK = SHADER[SHADER.index('float luminance ='):SHADER.index('} else if (uHighlight > 0.0)')]
# This bounded block is straight-line arithmetic; evaluate its actual expressions so
# the white-background regression fails if production reverts to max-only blending.
CODE = re.sub(r'\bfloat[23]?\s+', '', BLOCK).replace('col.rgb', 'body').replace('col.a', 'alpha')
CODE = '\n'.join(' '.join(statement.split()) for statement in CODE.split(';') if statement.strip())

def smoothstep(lo, hi, x):
    t = np.clip((x-lo)/(hi-lo), 0, 1)
    return t*t*(3-2*t)

def render(rgb, dark=False, alpha=1., depth=0., light=0., density=2.75):
    rgb = np.array(rgb, dtype=float)
    body = (rgb*.82 if dark else rgb*.82+.18)*alpha
    t = min(depth/(4*density), 1.)
    env = dict(transmitted=rgb, body=body.copy(), alpha=alpha, depth=depth,
               light=light, envelope=16*t*t*(1-t)*(1-t), uHighlight=.32,
               uStrokeW=density, dot=np.dot, float3=lambda *v: np.array(v[0] if len(v)==1 else v),
               mix=lambda a,b,t:a*(1-t)+b*t, clamp=np.clip, max=np.maximum,
               exp=np.exp, smoothstep=smoothstep)
    exec(CODE, {'__builtins__': {}}, env)
    return body, env['body']

class RimContrastTest(unittest.TestCase):
    def test_white_and_near_white_keep_visible_contour_in_light_theme(self):
        for y in (.9, .95, 1.):
            for light in (0., .5, 1.4):
                body, edge = render([y]*3, light=light)
                self.assertTrue(np.all(edge < body-.04), (y, light, body, edge))

    def test_dark_backdrop_still_has_illuminated_contour(self):
        for y in (0., .05, .2):
            body, edge = render([y]*3, dark=True)
            self.assertTrue(np.all(edge > body+.03))

    def test_body_outside_rim_is_unchanged(self):
        for y in (0., .5, 1.):
            body, output = render([y]*3, depth=30, light=1.4)
            np.testing.assert_allclose(output, body, atol=1e-12)

    def test_colour_and_alpha_bounds(self):
        rng = np.random.default_rng(88)
        for _ in range(1000):
            a = rng.uniform(.001,1.)
            _, out = render(rng.random(3), alpha=a, depth=rng.uniform(0,15), light=rng.uniform(0,1.4))
            self.assertTrue(np.all(np.isfinite(out)))
            self.assertTrue(np.all(out >= 0) and np.all(out <= a+1e-12))

    def test_bright_transition_is_continuous(self):
        outputs = [render([y]*3)[1][0] for y in np.linspace(.5,1.,1001)]
        self.assertLess(max(abs(np.diff(outputs))), .002)

if __name__ == '__main__':
    unittest.main()
