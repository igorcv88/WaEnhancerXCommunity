#!/usr/bin/env python3
"""Static check of the AGSL lens shaders with glslangValidator.

AGSL cannot be compiled off-device. This translates each shader to GLSL ES 3.10 (types, the
child shader, layout(color), the colour-space intrinsics) and runs glslangValidator on it, which
catches syntax, type and undeclared-identifier errors. It does not prove the device's AGSL
compiler accepts the shader: AGSL-only rules (for example half/float conversions) are not
checked. Usage: tools/agsl_check.py  (needs glslangValidator on PATH)
"""
import re
import subprocess
import sys
import tempfile

SOURCES = [
    ("app/src/main/java/com/waenhancer/theme/LiquidLens.java", "SHADER"),
    ("app/src/main/java/com/waenhancer/theme/LensEffect.java", "SHADER_V2"),
]


def extract(path, name):
    text = open(path, encoding="utf-8").read()
    start = re.search(r"\b" + name + r"\s*=\s*\"\"", text)
    if not start:
        raise SystemExit("constant %s not found in %s" % (name, path))
    body = text[start.end():]
    end = re.search(r'"\s*;\s*\n', body).end()
    code = "\n".join(l for l in body[:end].splitlines() if not l.strip().startswith("//"))
    literals = re.findall(r'"((?:[^"\\]|\\.)*)"', code)
    return "".join(bytes(l, "utf-8").decode("unicode_escape") for l in literals)


def to_glsl(agsl):
    src = agsl
    src = src.replace("uniform shader content;",
                      "uniform sampler2D contentTex;\nvec4 content_eval(vec2 c) { return texture(contentTex, c); }")
    src = src.replace("content.eval(", "content_eval(")
    src = src.replace("layout(color) ", "")
    for t in ("2", "3", "4"):
        src = re.sub(r"\bfloat" + t + r"\b", "vec" + t, src)
        src = re.sub(r"\bhalf" + t + r"\b", "vec" + t, src)
    src = re.sub(r"\bhalf\b", "float", src)
    src = re.sub(r"\bvec4 main\(vec2 coord\)", "vec4 main_(vec2 coord)", src)
    header = ("#version 310 es\nprecision highp float;\nout vec4 fragColor;\n"
              "vec3 toLinearSrgb(vec3 c) { return c; }\nvec3 fromLinearSrgb(vec3 c) { return c; }\n")
    return header + src + "\nvoid main() { fragColor = main_(gl_FragCoord.xy); }\n"


def main():
    failed = False
    for path, name in SOURCES:
        glsl = to_glsl(extract(path, name))
        with tempfile.NamedTemporaryFile("w", suffix=".frag", delete=False) as f:
            f.write(glsl)
        result = subprocess.run(["glslangValidator", f.name], capture_output=True, text=True)
        ok = result.returncode == 0
        failed |= not ok
        print("%s %s: %s" % (name, path.split("/")[-1], "OK" if ok else "FAILED"))
        if not ok:
            print(result.stdout + result.stderr)
            for i, line in enumerate(glsl.splitlines(), 1):
                print("%4d %s" % (i, line))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
