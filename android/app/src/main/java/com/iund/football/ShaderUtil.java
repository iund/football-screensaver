package com.iund.football;

import android.opengl.GLES20;

final class ShaderUtil {

    private ShaderUtil() {
    }

    static int buildProgram(String vertexSrc, String fragmentSrc) {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc);

        int program = GLES20.glCreateProgram();
        if (program == 0) {
            throw new RuntimeException("glCreateProgram failed");
        }
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new RuntimeException("Program link failed: " + log);
        }

        // Shaders are refcounted by the program once attached/linked; safe to delete here.
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);

        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        if (shader == 0) {
            throw new RuntimeException("glCreateShader failed for type " + type);
        }
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return shader;
    }
}
