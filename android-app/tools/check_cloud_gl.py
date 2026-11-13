"""Off-device check of CloudView's actual shaders using the Android SDK's SwiftShader.

Run on Windows: python tools/check_cloud_gl.py
No emulator, phone, network, or extra Python package is used.
"""
from __future__ import annotations

import ctypes as c
from array import array
import argparse
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import time
import zlib


def export_geometry(args, width: int, height: int) -> bytes:
    root = Path(__file__).resolve().parents[1]
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    # Reuse cached test tooling; never inspect provisioning assets.
    json_jars = sorted((cache / "org.json/json").glob("*/*/*.jar"))
    kotlin_jars = sorted((cache / "org.jetbrains.kotlin/kotlin-stdlib/2.3.20").glob("*/*.jar"))
    if not json_jars or not kotlin_jars:
        raise RuntimeError("Offline rendering needs cached org.json:json and Kotlin 2.3.20 jars")
    classes = root / "app/build/tmp/kotlin-classes/debug"
    work_root = root / "app/build/reports/cloud-gl"
    work_root.mkdir(parents=True, exist_ok=True)
    # Independent renders may run concurrently; each owns and cleans its export files.
    with tempfile.TemporaryDirectory(prefix="render-", dir=work_root) as directory:
        work = Path(directory)
        classpath = os.pathsep.join(map(str, (classes, json_jars[-1], kotlin_jars[-1], work)))
        java_home = Path(os.environ["JAVA_HOME"])
        subprocess.run([str(java_home / "bin/javac.exe"), "-cp", classpath, "-d", str(work),
            str(root / "tools/CloudPreview.java")], check=True)
        binary = work / "geometry.bin"
        subprocess.run([str(java_home / "bin/java.exe"), "-Xmx192m", "-cp", classpath, "CloudPreview",
            str(args.cloud.resolve()), str(binary), args.view, str(width), str(height)], check=True)
        return binary.read_bytes()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cloud", type=Path, help="Public cloud JSON (same schema as /cloud); render actual Kotlin geometry")
    parser.add_argument("--output", type=Path, default=Path("app/build/reports/cloud-preview.png"))
    parser.add_argument("--view", choices=("FRONT", "SIDE", "TOP"), default="FRONT")
    parser.add_argument("--width", type=int, default=600)
    parser.add_argument("--height", type=int, default=800)
    parser.add_argument("--point-size", type=float, default=3)
    args = parser.parse_args()
    width, height = (args.width, args.height) if args.cloud else (240, 240)
    if not (64 <= width <= 4096 and 64 <= height <= 4096):
        parser.error("Dimensions must be between 64 and 4096")
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "AppData/Local/Android/Sdk"))
    library_dir = sdk / "emulator/lib64/gles_swiftshader"
    with os.add_dll_directory(str(library_dir)):
        egl = c.WinDLL(str(library_dir / "libEGL.dll"))
        gl = c.WinDLL(str(library_dir / "libGLESv2.dll"))

    def bind(library, name, result, *args):
        function = getattr(library, name)
        function.restype = result
        function.argtypes = args
        return function

    ptr = c.c_void_p
    integer = c.c_int
    uint = c.c_uint
    floating = c.c_float
    egl_get_display = bind(egl, "eglGetDisplay", ptr, ptr)
    egl_initialize = bind(egl, "eglInitialize", uint, ptr, ptr, ptr)
    egl_choose_config = bind(egl, "eglChooseConfig", uint, ptr, ptr, ptr, integer, ptr)
    egl_create_surface = bind(egl, "eglCreatePbufferSurface", ptr, ptr, ptr, ptr)
    egl_create_context = bind(egl, "eglCreateContext", ptr, ptr, ptr, ptr, ptr)
    egl_make_current = bind(egl, "eglMakeCurrent", uint, ptr, ptr, ptr, ptr)
    egl_destroy_surface = bind(egl, "eglDestroySurface", uint, ptr, ptr)
    egl_destroy_context = bind(egl, "eglDestroyContext", uint, ptr, ptr)
    egl_terminate = bind(egl, "eglTerminate", uint, ptr)
    display = egl_get_display(None)
    assert egl_initialize(display, None, None), "SwiftShader EGL initialization failed"
    config = ptr()
    count = integer()
    attrs = (integer * 15)(0x3033, 1, 0x3040, 4, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3025, 16, 0x3021, 0, 0x3038)
    assert egl_choose_config(display, attrs, c.byref(config), 1, c.byref(count)) and count.value
    surface_attrs = (integer * 5)(0x3057, width, 0x3056, height, 0x3038)
    context_attrs = (integer * 3)(0x3098, 2, 0x3038)
    surface = egl_create_surface(display, config, surface_attrs)
    context = egl_create_context(display, config, None, context_attrs)
    assert surface and context and egl_make_current(display, surface, surface, context)
    try:
        create_shader = bind(gl, "glCreateShader", uint, uint)
        shader_source = bind(gl, "glShaderSource", None, uint, integer, ptr, ptr)
        compile_shader = bind(gl, "glCompileShader", None, uint)
        get_shader = bind(gl, "glGetShaderiv", None, uint, uint, ptr)
        shader_log = bind(gl, "glGetShaderInfoLog", None, uint, integer, ptr, ptr)
        create_program = bind(gl, "glCreateProgram", uint)
        attach_shader = bind(gl, "glAttachShader", None, uint, uint)
        link_program = bind(gl, "glLinkProgram", None, uint)
        get_program = bind(gl, "glGetProgramiv", None, uint, uint, ptr)
        use_program = bind(gl, "glUseProgram", None, uint)
        attribute = bind(gl, "glGetAttribLocation", integer, uint, c.c_char_p)
        uniform = bind(gl, "glGetUniformLocation", integer, uint, c.c_char_p)
        uniform_matrix = bind(gl, "glUniformMatrix4fv", None, integer, integer, c.c_ubyte, ptr)
        uniform_float = bind(gl, "glUniform1f", None, integer, floating)
        gen_buffers = bind(gl, "glGenBuffers", None, integer, ptr)
        bind_buffer = bind(gl, "glBindBuffer", None, uint, uint)
        buffer_data = bind(gl, "glBufferData", None, uint, c.c_ssize_t, ptr, uint)
        enable_attribute = bind(gl, "glEnableVertexAttribArray", None, uint)
        attribute_pointer = bind(gl, "glVertexAttribPointer", None, uint, integer, uint, c.c_ubyte, integer, ptr)
        enable = bind(gl, "glEnable", None, uint)
        depth_func = bind(gl, "glDepthFunc", None, uint)
        clear_color = bind(gl, "glClearColor", None, floating, floating, floating, floating)
        clear = bind(gl, "glClear", None, uint)
        viewport = bind(gl, "glViewport", None, integer, integer, integer, integer)
        draw = bind(gl, "glDrawArrays", None, uint, integer, integer)
        read_pixels = bind(gl, "glReadPixels", None, integer, integer, integer, integer, uint, uint, ptr)
        finish = bind(gl, "glFinish", None)
        get_error = bind(gl, "glGetError", uint)
        source = (Path(__file__).resolve().parents[1] / "app/src/main/java/com/roomscan/app/CloudView.kt").read_text()
        shaders = []
        for label, kind in (("vertex", 0x8B31), ("fragment", 0x8B30)):
            match = re.search(rf'val {label} = shader\([^,]+, """(.*?)"""', source, re.S)
            assert match, f"Could not locate {label} shader in CloudView.kt"
            shader = create_shader(kind)
            text = c.c_char_p(match.group(1).encode())
            shader_source(shader, 1, c.byref(text), None)
            compile_shader(shader)
            status = integer()
            get_shader(shader, 0x8B81, c.byref(status))
            log = c.create_string_buffer(4096)
            shader_log(shader, 4096, None, log)
            assert status.value, log.value.decode()
            shaders.append(shader)
        program = create_program()
        for shader in shaders:
            attach_shader(program, shader)
        link_program(program)
        status = integer()
        get_program(program, 0x8B82, c.byref(status))
        assert status.value, "Shader link failed"
        use_program(program)
        projection = (floating * 16)(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1)
        uniform_matrix(uniform(program, b"uTransform"), 1, 0, projection)
        uniform_float(uniform(program, b"uSize"), 40)
        buffer = uint()
        gen_buffers(1, c.byref(buffer))
        bind_buffer(0x8892, buffer)
        position = attribute(program, b"aPosition")
        color = attribute(program, b"aColor")
        for location, offset in ((position, 0), (color, 12)):
            enable_attribute(location)
            attribute_pointer(location, 3, 0x1406, 0, 24, ptr(offset))
        viewport(0, 0, width, height)
        clear_color(0, 0, 0, 1)
        enable(0x0B71)
        depth_func(0x0203)

        def upload(values):
            buffer_data(0x8892, len(values) * 4, values.buffer_info()[0], 0x88E4)

        def pixel(x, y):
            rgba = (c.c_ubyte * 4)()
            read_pixels(x, y, 1, 1, 0x1908, 0x1401, rgba)
            return list(rgba)

        if args.cloud:
            data = export_geometry(args, width, height)
            point_count = struct.unpack_from(">i", data)[0]
            projection = (floating * 16)(*struct.unpack_from(">16f", data, 4))
            values = array("f")
            values.frombytes(data[68:])
            if sys.byteorder == "little":
                values.byteswap()
            assert len(values) == point_count * 6
            uniform_matrix(uniform(program, b"uTransform"), 1, 0, projection)
            uniform_float(uniform(program, b"uSize"), args.point_size)
            clear_color(0.025, 0.04, 0.06, 1)
            upload(values)
            clear(0x4000 | 0x0100)
            draw(0, 0, point_count)
            rgba = (c.c_ubyte * (width * height * 4))()
            read_pixels(0, 0, width, height, 0x1908, 0x1401, rgba)
            assert get_error() == 0, "OpenGL error rendering actual cloud"
            pixels = bytes(rgba)
            scanlines = b"".join(b"\0" + pixels[row * width * 4:(row + 1) * width * 4]
                for row in reversed(range(height)))
            def chunk(kind, body):
                return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))
            png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            png += chunk(b"IDAT", zlib.compress(scanlines)) + chunk(b"IEND", b"")
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_bytes(png)
            print(f"Rendered {point_count:,} points: {args.output.resolve()}")
            return

        near = [0, 0, -0.5, 1, 0, 0]
        far = [0, 0, 0.5, 0, 0, 1]
        for order in (near + far, far + near):
            upload(array("f", order))
            clear(0x4000 | 0x0100)
            draw(0, 0, 2)
            assert pixel(120, 120)[:3] == [255, 0, 0], "Rear point overwrote near point"
            assert pixel(104, 104)[:3] == [0, 0, 0], "Round point fragment clipping failed"
        assert get_error() == 0, "OpenGL error during depth/shape checks"
        values = array("f")
        for i in range(200_000):
            values.extend(((i % 500) / 250 - 1, (i // 500) / 200 - 1, (i % 101) / 101, 0.3, 0.6, 0.8))
        upload(values)
        uniform_float(uniform(program, b"uSize"), 2)
        start = time.perf_counter()
        clear(0x4000 | 0x0100)
        draw(0, 0, 200_000)
        finish()
        elapsed = (time.perf_counter() - start) * 1000
        assert get_error() == 0, "OpenGL error drawing 200,000 points"
        assert pixel(120, 120)[:3] != [0, 0, 0], "Large cloud produced no image"
        print(f"PASS: exact app shaders compile/link; depth wins in both draw orders; round splats; 200,000-point VBO draw ({elapsed:.0f} ms on desktop SwiftShader, not a phone benchmark).")
    finally:
        egl_make_current(display, None, None, None)
        egl_destroy_context(display, context)
        egl_destroy_surface(display, surface)
        egl_terminate(display)


if __name__ == "__main__":
    main()
