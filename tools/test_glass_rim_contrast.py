#!/usr/bin/env python3
"""Numerically execute production AGSL rim expressions; not an AGSL compiler."""
import re
import unittest
from pathlib import Path
import numpy as np
from agsl_check import extract

ROOT = Path(__file__).resolve().parents[1]
SHADER = extract(str(ROOT / 'app/src/main/java/com/waenhancer/theme/KyantLens.java'), 'SHADER')
# The rim block ends where the outer-bloom branch starts; that branch's condition gained
# terms over time, so only its stable prefix is the anchor.
BLOCK = SHADER[SHADER.index('float luminance ='):SHADER.index('} else if (uHighlight > 0.0')]


def translate(block):
    '''The block's statements and its if/else on uniforms, as Python with the same branches.

    Since d93e3734 the block is no longer straight-line arithmetic: app chrome (uAppChrome)
    has its own contour gain and reflection. Every production statement still executes; only
    the C-style control flow is rewritten. Any construct not handled here fails loudly.
    '''
    text = re.sub(r'\bfloat[23]?\s+', '', block).replace('col.rgb', 'body').replace('col.a', 'alpha')
    text = ' '.join(text.split())
    out, depth, i = [], 0, 0
    while i < len(text):
        rest = text[i:].lstrip()
        i = len(text) - len(rest)
        if not rest:
            break
        if rest.startswith('}'):
            depth -= 1
            after = rest[1:].lstrip()
            if after.startswith('else {'):
                out.append('    ' * depth + 'else:')
                depth += 1
                i = len(text) - len(after) + len('else {')
            else:
                i += 1
            continue
        m = re.match(r'if \(([^()]*)\) \{', rest)
        if m:
            out.append('    ' * depth + 'if ' + m.group(1) + ':')
            depth += 1
            i += m.end()
            continue
        m = re.match(r'if \(([^()]*)\) ([^;{}]+);', rest)
        if m:
            out.append('    ' * depth + 'if ' + m.group(1) + ': ' + m.group(2))
            i += m.end()
            continue
        end = rest.find(';')
        if end < 0 or '{' in rest[:end] or '}' in rest[:end]:
            raise ValueError('unsupported shader construct: ' + rest[:60])
        out.append('    ' * depth + rest[:end].strip())
        i += end + 1
    if depth != 0:
        raise ValueError('unbalanced braces in rim block')
    return '\n'.join(out)


CODE = translate(BLOCK)
if 'uAppChrome' not in CODE:
    raise AssertionError('rim block no longer distinguishes app chrome; update the test')

def smoothstep(lo, hi, x):
    t = np.clip((x-lo)/(hi-lo), 0, 1)
    return t*t*(3-2*t)

def render(rgb, dark=False, alpha=1., depth=0., light=0., density=2.75, contour_gain=1., stroke_scale=1.,
           app_chrome=0.):
    rgb = np.array(rgb, dtype=float)
    body = (rgb*.82 if dark else rgb*.82+.18)*alpha
    t = min(depth/(4*density), 1.)
    env = dict(transmitted=rgb, body=body.copy(), alpha=alpha, depth=depth,
               light=light, envelope=16*t*t*(1-t)*(1-t), uHighlight=.32,
               uStrokeW=density*stroke_scale, uContourGain=contour_gain, uAppChrome=app_chrome,
               uBloom=density*2, band=density*4, dot=np.dot, float3=lambda *v: np.array(v[0] if len(v)==1 else v),
               mix=lambda a,b,t:a*(1-t)+b*t, clamp=np.clip, max=np.maximum,
               exp=np.exp, smoothstep=smoothstep)
    exec(CODE, {'__builtins__': {}}, env)
    return body, env['body']

class RimContrastTest(unittest.TestCase):
    def test_app_contour_is_stronger_without_lifting_body(self):
        for y, dark in ((0., True), (.05, True), (1., False)):
            body, bar = render([y]*3, dark=dark)
            # The APP profile (KyantLens APP_CONTOUR_GAIN/APP_STROKE_DP) also sets appChrome.
            _, app = render([y]*3, dark=dark, contour_gain=1.8, stroke_scale=1.25, app_chrome=1.)
            # Not relaxed: since d93e3734 the APP profile has its own (approved) optical branch,
            # whose dark-backdrop contour is about 1.49x the bar's; this check reports that.
            self.assertGreater(abs(app[0]-body[0]), abs(bar[0]-body[0])*1.6,
                               'APP profile contour vs floating bar at luminance %s' % y)
            inner, out = render([y]*3, dark=dark, depth=30., contour_gain=1.8, stroke_scale=1.25, app_chrome=1.)
            np.testing.assert_allclose(inner, out, atol=1e-12)

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

    def test_app_chrome_rim_only_lifts_towards_white_and_is_bounded(self):
        rng = np.random.default_rng(1030)
        for _ in range(1000):
            a = rng.uniform(.001, 1.)
            body, out = render(rng.random(3), alpha=a, depth=rng.uniform(0, 15),
                               light=rng.uniform(0, 1.4), app_chrome=1.)
            self.assertTrue(np.all(np.isfinite(out)))
            self.assertTrue(np.all(out >= body-1e-12), (body, out))
            self.assertTrue(np.all(out <= a+1e-12))

    def test_app_chrome_contour_is_visible_on_dark_backdrops(self):
        for y in (0., .05, .2):
            body, edge = render([y]*3, dark=True, app_chrome=1.)
            self.assertTrue(np.all(edge > body+.03), (y, body, edge))

    def test_app_chrome_body_outside_rim_is_unchanged(self):
        for y in (0., .5, 1.):
            body, output = render([y]*3, depth=30, light=1.4, app_chrome=1.)
            np.testing.assert_allclose(output, body, atol=1e-4)

    def test_bubble_and_app_chrome_rims_differ(self):
        _, bubble = render([.95]*3, light=.5)
        _, chrome = render([.95]*3, light=.5, app_chrome=1.)
        self.assertFalse(np.allclose(bubble, chrome))


if __name__ == '__main__':
    unittest.main()
